package client

import (
	"context"
	"fmt"
	"io"
	"math"
	"strings"
	"sync"
	"time"

	ojpgrpc "github.com/open-j-proxy/ojp-client/internal/gen/go/com/openjproxy/grpc"
	grpc "google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// DefaultOJPGRPCPort is the default gRPC port for OJP servers.
const DefaultOJPGRPCPort = 1059

type StatementService interface {
	Connect(ctx context.Context, details *ojpgrpc.ConnectionDetails) (*ojpgrpc.SessionInfo, error)
	ExecuteUpdate(ctx context.Context, session *ojpgrpc.SessionInfo, sql string) (*ojpgrpc.OpResult, error)
	ExecuteQuery(ctx context.Context, session *ojpgrpc.SessionInfo, sql string) ([]*ojpgrpc.OpResult, error)
	TerminateSession(ctx context.Context, session *ojpgrpc.SessionInfo) error
	StartTransaction(ctx context.Context, session *ojpgrpc.SessionInfo) (*ojpgrpc.SessionInfo, error)
	CommitTransaction(ctx context.Context, session *ojpgrpc.SessionInfo) (*ojpgrpc.SessionInfo, error)
	RollbackTransaction(ctx context.Context, session *ojpgrpc.SessionInfo) (*ojpgrpc.SessionInfo, error)
	Shutdown()
	XAStart(ctx context.Context, xid *ojpgrpc.XidProto, flags int) (*ojpgrpc.XaResponse, error)
	XAEnd(ctx context.Context, xid *ojpgrpc.XidProto, flags int) (*ojpgrpc.XaResponse, error)
	XAPrepare(ctx context.Context, xid *ojpgrpc.XidProto) (*ojpgrpc.XaPrepareResponse, error)
	XACommit(ctx context.Context, xid *ojpgrpc.XidProto, onePhase bool) (*ojpgrpc.XaResponse, error)
	XARollback(ctx context.Context, xid *ojpgrpc.XidProto) (*ojpgrpc.XaResponse, error)
	XARecover(ctx context.Context, flag int) (*ojpgrpc.XaRecoverResponse, error)
	XAForget(ctx context.Context, xid *ojpgrpc.XidProto) (*ojpgrpc.XaResponse, error)
	XASetTransactionTimeout(ctx context.Context, seconds int) (*ojpgrpc.XaSetTransactionTimeoutResponse, error)
	XAGetTransactionTimeout(ctx context.Context) (*ojpgrpc.XaGetTransactionTimeoutResponse, error)
}

type MultinodeStatementService struct {
	connManager *MultinodeConnectionManager
	clients     map[*ServerEndpoint]*EndpointClient
	clientLock  sync.RWMutex
}

type EndpointClient struct {
	client StatementService
	server *ServerEndpoint
}

func NewMultinodeStatementService(connManager *MultinodeConnectionManager) *MultinodeStatementService {
	return &MultinodeStatementService{
		connManager: connManager,
		clients:     make(map[*ServerEndpoint]*EndpointClient),
	}
}

func (s *MultinodeStatementService) getClient(server *ServerEndpoint) (*EndpointClient, error) {
	s.clientLock.RLock()
	if cached, ok := s.clients[server]; ok {
		s.clientLock.RUnlock()
		return cached, nil
	}
	s.clientLock.RUnlock()

	s.clientLock.Lock()
	defer s.clientLock.Unlock()

	if cached, ok := s.clients[server]; ok {
		return cached, nil
	}

	client := NewGrpcStatementServiceClient(server.Address())
	epClient := &EndpointClient{
		client: client,
		server: server,
	}
	s.clients[server] = epClient
	return epClient, nil
}

func (s *MultinodeStatementService) Connect(ctx context.Context, details *ojpgrpc.ConnectionDetails) (*ojpgrpc.SessionInfo, error) {
	return s.connManager.Connect(ctx, details)
}

func (s *MultinodeStatementService) ExecuteUpdate(ctx context.Context, session *ojpgrpc.SessionInfo, sql string) (*ojpgrpc.OpResult, error) {
	server := s.connManager.selectServer(session.GetSessionUUID())
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}

	server.IncrConnection()
	defer server.DecrConnection()
	result, err := epClient.client.ExecuteUpdate(ctx, session, sql)
	if err != nil {
		if IsConnectionLevelError(err) {
			server.MarkUnhealthy()
			s.connManager.handleServerFailure(server, err)
		}
		return nil, err
	}
	return result, nil
}

func (s *MultinodeStatementService) ExecuteQuery(ctx context.Context, session *ojpgrpc.SessionInfo, sql string) ([]*ojpgrpc.OpResult, error) {
	server := s.connManager.selectServer(session.GetSessionUUID())
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}

	server.IncrConnection()
	results, err := epClient.client.ExecuteQuery(ctx, session, sql)
	if err != nil {
		server.DecrConnection()
		if IsConnectionLevelError(err) {
			server.MarkUnhealthy()
			s.connManager.handleServerFailure(server, err)
		}
		return nil, err
	}

	if results != nil {
		currentLoad := server.LoadMetric()
		server.SetLoadMetric(currentLoad + int64(len(results)))
	}
	server.DecrConnection()

	return results, nil
}

func (s *MultinodeStatementService) TerminateSession(ctx context.Context, session *ojpgrpc.SessionInfo) error {
	sessUUID := session.GetSessionUUID()
	if sessUUID != "" {
		if bound := s.connManager.connTracker.GetBoundServer(sessUUID); bound != nil {
			epClient, err := s.getClient(bound)
			if err == nil {
				return epClient.client.TerminateSession(ctx, session)
			}
		}
	}
	return nil
}

func (s *MultinodeStatementService) StartTransaction(ctx context.Context, session *ojpgrpc.SessionInfo) (*ojpgrpc.SessionInfo, error) {
	server := s.connManager.selectServer("")
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}

	result, err := epClient.client.StartTransaction(ctx, session)
	if err != nil {
		return nil, err
	}

	if result.GetSessionUUID() != "" {
		s.connManager.connTracker.BindSession(result.GetSessionUUID(), server)
	}

	return result, nil
}

func (s *MultinodeStatementService) CommitTransaction(ctx context.Context, session *ojpgrpc.SessionInfo) (*ojpgrpc.SessionInfo, error) {
	server := s.connManager.selectServer(session.GetSessionUUID())
	if server == nil {
		return nil, fmt.Errorf("no server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}
	return epClient.client.CommitTransaction(ctx, session)
}

func (s *MultinodeStatementService) RollbackTransaction(ctx context.Context, session *ojpgrpc.SessionInfo) (*ojpgrpc.SessionInfo, error) {
	server := s.connManager.selectServer(session.GetSessionUUID())
	if server == nil {
		return nil, fmt.Errorf("no server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}
	return epClient.client.RollbackTransaction(ctx, session)
}

func (s *MultinodeStatementService) Shutdown() {
	s.connManager.Shutdown()
	s.clientLock.Lock()
	defer s.clientLock.Unlock()
	for _, c := range s.clients {
		c.client.Shutdown()
	}
	s.clients = make(map[*ServerEndpoint]*EndpointClient)
}

func (s *MultinodeStatementService) XAStart(ctx context.Context, xid *ojpgrpc.XidProto, flags int) (*ojpgrpc.XaResponse, error) {
	server := s.connManager.selectServer("")
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}
	return epClient.client.XAStart(ctx, xid, flags)
}

func (s *MultinodeStatementService) XAEnd(ctx context.Context, xid *ojpgrpc.XidProto, flags int) (*ojpgrpc.XaResponse, error) {
	server := s.connManager.selectServer("")
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}
	return epClient.client.XAEnd(ctx, xid, flags)
}

func (s *MultinodeStatementService) XAPrepare(ctx context.Context, xid *ojpgrpc.XidProto) (*ojpgrpc.XaPrepareResponse, error) {
	server := s.connManager.selectServer("")
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}
	return epClient.client.XAPrepare(ctx, xid)
}

func (s *MultinodeStatementService) XACommit(ctx context.Context, xid *ojpgrpc.XidProto, onePhase bool) (*ojpgrpc.XaResponse, error) {
	server := s.connManager.selectServer("")
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}
	return epClient.client.XACommit(ctx, xid, onePhase)
}

func (s *MultinodeStatementService) XARollback(ctx context.Context, xid *ojpgrpc.XidProto) (*ojpgrpc.XaResponse, error) {
	server := s.connManager.selectServer("")
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}
	return epClient.client.XARollback(ctx, xid)
}

func (s *MultinodeStatementService) XARecover(ctx context.Context, flag int) (*ojpgrpc.XaRecoverResponse, error) {
	server := s.connManager.selectServer("")
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}
	return epClient.client.XARecover(ctx, flag)
}

func (s *MultinodeStatementService) XAForget(ctx context.Context, xid *ojpgrpc.XidProto) (*ojpgrpc.XaResponse, error) {
	server := s.connManager.selectServer("")
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}
	return epClient.client.XAForget(ctx, xid)
}

func (s *MultinodeStatementService) XASetTransactionTimeout(ctx context.Context, seconds int) (*ojpgrpc.XaSetTransactionTimeoutResponse, error) {
	server := s.connManager.selectServer("")
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}
	return epClient.client.XASetTransactionTimeout(ctx, seconds)
}

func (s *MultinodeStatementService) XAGetTransactionTimeout(ctx context.Context) (*ojpgrpc.XaGetTransactionTimeoutResponse, error) {
	server := s.connManager.selectServer("")
	if server == nil {
		return nil, fmt.Errorf("no healthy server available")
	}

	epClient, err := s.getClient(server)
	if err != nil {
		return nil, err
	}
	return epClient.client.XAGetTransactionTimeout(ctx)
}

type GrpcStatementServiceClient struct {
	address string
	client  ojpgrpc.StatementServiceClient
	conn    *grpc.ClientConn
	connMu  sync.Mutex
}

func NewGrpcStatementServiceClient(address string) *GrpcStatementServiceClient {
	return &GrpcStatementServiceClient{address: address}
}

func (c *GrpcStatementServiceClient) EnsureConnected() error {
	c.connMu.Lock()
	defer c.connMu.Unlock()

	if c.conn != nil {
		return nil
	}

	parts := strings.Split(c.address, ":")
	var host string
	port := DefaultOJPGRPCPort
	if len(parts) == 2 {
		host = parts[0]
		if n, scanErr := fmt.Sscanf(parts[1], "%d", &port); scanErr != nil || n != 1 || port <= 0 || port > 65535 {
         	return fmt.Errorf("invalid port in address %q", c.address)
        }
	} else {
		host = c.address
	}

    if host == "" {
    	host = "localhost"
    }

	conn, err := grpc.NewClient(fmt.Sprintf("%s:%d", host, port),
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		return err
	}

	c.conn = conn
	c.client = ojpgrpc.NewStatementServiceClient(conn)
	return nil
}

func (c *GrpcStatementServiceClient) Connect(ctx context.Context, details *ojpgrpc.ConnectionDetails) (*ojpgrpc.SessionInfo, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}
	return c.client.Connect(ctx, details)
}

func (c *GrpcStatementServiceClient) ExecuteUpdate(ctx context.Context, session *ojpgrpc.SessionInfo, sql string) (*ojpgrpc.OpResult, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}

	req := &ojpgrpc.StatementRequest{
		Session: session,
		Sql:     sql,
	}
	return c.client.ExecuteUpdate(ctx, req)
}

func (c *GrpcStatementServiceClient) ExecuteQuery(ctx context.Context, session *ojpgrpc.SessionInfo, sql string) ([]*ojpgrpc.OpResult, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}

	req := &ojpgrpc.StatementRequest{
		Session: session,
		Sql:     sql,
	}
	stream, err := c.client.ExecuteQuery(ctx, req)
	if err != nil {
		return nil, err
	}

	var results []*ojpgrpc.OpResult
	for {
		msg, err := stream.Recv()
		if err == io.EOF {
			break
		}
		if err != nil {
			return results, err
		}
		results = append(results, msg)
	}
	return results, nil
}

func (c *GrpcStatementServiceClient) TerminateSession(ctx context.Context, session *ojpgrpc.SessionInfo) error {
	if c.client == nil {
		return nil
	}
	_, err := c.client.TerminateSession(ctx, session)
	return err
}

func (c *GrpcStatementServiceClient) StartTransaction(ctx context.Context, session *ojpgrpc.SessionInfo) (*ojpgrpc.SessionInfo, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}
	return c.client.StartTransaction(ctx, session)
}

func (c *GrpcStatementServiceClient) CommitTransaction(ctx context.Context, session *ojpgrpc.SessionInfo) (*ojpgrpc.SessionInfo, error) {
	if c.client == nil {
		return nil, fmt.Errorf("client not connected")
	}
	return c.client.CommitTransaction(ctx, session)
}

func (c *GrpcStatementServiceClient) RollbackTransaction(ctx context.Context, session *ojpgrpc.SessionInfo) (*ojpgrpc.SessionInfo, error) {
	if c.client == nil {
		return nil, fmt.Errorf("client not connected")
	}
	return c.client.RollbackTransaction(ctx, session)
}

func (c *GrpcStatementServiceClient) XAStart(ctx context.Context, xid *ojpgrpc.XidProto, flags int) (*ojpgrpc.XaResponse, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}
	req := &ojpgrpc.XaStartRequest{
		Xid:   xid,
		Flags: int32(flags),
	}
	return c.client.XaStart(ctx, req)
}

func (c *GrpcStatementServiceClient) XAEnd(ctx context.Context, xid *ojpgrpc.XidProto, flags int) (*ojpgrpc.XaResponse, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}
	req := &ojpgrpc.XaEndRequest{
		Xid:   xid,
		Flags: int32(flags),
	}
	return c.client.XaEnd(ctx, req)
}

func (c *GrpcStatementServiceClient) XAPrepare(ctx context.Context, xid *ojpgrpc.XidProto) (*ojpgrpc.XaPrepareResponse, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}
	req := &ojpgrpc.XaPrepareRequest{Xid: xid}
	return c.client.XaPrepare(ctx, req)
}

func (c *GrpcStatementServiceClient) XACommit(ctx context.Context, xid *ojpgrpc.XidProto, onePhase bool) (*ojpgrpc.XaResponse, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}
	req := &ojpgrpc.XaCommitRequest{
		Xid:      xid,
		OnePhase: onePhase,
	}
	return c.client.XaCommit(ctx, req)
}

func (c *GrpcStatementServiceClient) XARollback(ctx context.Context, xid *ojpgrpc.XidProto) (*ojpgrpc.XaResponse, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}
	req := &ojpgrpc.XaRollbackRequest{Xid: xid}
	return c.client.XaRollback(ctx, req)
}

func (c *GrpcStatementServiceClient) XARecover(ctx context.Context, flag int) (*ojpgrpc.XaRecoverResponse, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}
	req := &ojpgrpc.XaRecoverRequest{Flag: int32(flag)}
	return c.client.XaRecover(ctx, req)
}

func (c *GrpcStatementServiceClient) XAForget(ctx context.Context, xid *ojpgrpc.XidProto) (*ojpgrpc.XaResponse, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}
	req := &ojpgrpc.XaForgetRequest{Xid: xid}
	return c.client.XaForget(ctx, req)
}

func (c *GrpcStatementServiceClient) XASetTransactionTimeout(ctx context.Context, seconds int) (*ojpgrpc.XaSetTransactionTimeoutResponse, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}
	req := &ojpgrpc.XaSetTransactionTimeoutRequest{Seconds: int32(seconds)}
	return c.client.XaSetTransactionTimeout(ctx, req)
}

func (c *GrpcStatementServiceClient) XAGetTransactionTimeout(ctx context.Context) (*ojpgrpc.XaGetTransactionTimeoutResponse, error) {
	if c.client == nil {
		if err := c.EnsureConnected(); err != nil {
			return nil, err
		}
	}
	req := &ojpgrpc.XaGetTransactionTimeoutRequest{}
	return c.client.XaGetTransactionTimeout(ctx, req)
}

func (c *GrpcStatementServiceClient) Shutdown() {
	if c.conn != nil {
		c.conn.Close()
	}
}

// RetryWithBackoff attempts an operation up to maxRetries times with exponential backoff.
func RetryWithBackoff(ctx context.Context, maxRetries int, initialBackoff, maxBackoff time.Duration, fn func() error) error {
	var err error
	backoff := initialBackoff

	for attempt := 0; attempt < maxRetries; attempt++ {
		if attempt > 0 {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(backoff):
			}
			backoff = time.Duration(math.Min(float64(maxBackoff), float64(backoff)*2))
		}

		err = fn()
		if err == nil {
			return nil
		}
	}
	return err
}
