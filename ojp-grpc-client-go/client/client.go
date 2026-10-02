package client

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"

	pb "github.com/open-j-proxy/ojp-client/internal/gen/go/com/openjproxy/grpc"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/proto"
)

var (
	ErrClientClosed     = errors.New("OJP client is closed")
	ErrConnectionClosed = errors.New("OJP connection is closed")
)

type Config struct {
	URL        string
	User       string
	Password   string
	Properties map[string]string
}

type Client struct {
	rpc    pb.StatementServiceClient
	closer func() error
	mu     sync.RWMutex
	closed bool
}

type Connection struct {
	client  *Client
	session *pb.SessionInfo
	mu      sync.Mutex
	closed  bool
}

type SessionState struct {
	ClientUUID        string
	ConnHash          string
	SessionUUID       string
	TargetServer      string
	TransactionUUID   string
	TransactionStatus string
}

type Result struct {
	Columns []string
	Rows    [][]any
}

var processUUIDOnce sync.Once
var processUUID string
var processUUIDErr error

func NewClient(endpoint string) (*Client, error) {
	endpoint = strings.TrimSpace(endpoint)
	host, port, err := net.SplitHostPort(endpoint)
	if err != nil || host == "" {
		return nil, fmt.Errorf("invalid OJP endpoint %q: expected host:port", endpoint)
	}
	portNumber, err := strconv.Atoi(port)
	if err != nil || portNumber < 1 || portNumber > 65535 {
		return nil, fmt.Errorf("invalid OJP endpoint port %q", port)
	}
	conn, err := grpc.NewClient(endpoint, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return nil, fmt.Errorf("create OJP gRPC client: %w", err)
	}
	return newClient(pb.NewStatementServiceClient(conn), conn.Close), nil
}

func newClient(rpc pb.StatementServiceClient, closer func() error) *Client {
	return &Client{rpc: rpc, closer: closer}
}

func (c *Client) Connect(ctx context.Context, config Config) (*Connection, error) {
	if strings.TrimSpace(config.URL) == "" {
		return nil, errors.New("database URL is required")
	}
	clientUUID, err := getProcessUUID()
	if err != nil {
		return nil, fmt.Errorf("generate OJP client UUID: %w", err)
	}
	propertyKeys := make([]string, 0, len(config.Properties))
	for key := range config.Properties {
		propertyKeys = append(propertyKeys, key)
	}
	sort.Strings(propertyKeys)
	properties := make([]*pb.PropertyEntry, 0, len(propertyKeys))
	for _, key := range propertyKeys {
		value := config.Properties[key]
		properties = append(properties, &pb.PropertyEntry{
			Key:   key,
			Value: &pb.PropertyEntry_StringValue{StringValue: value},
		})
	}

	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.closed {
		return nil, ErrClientClosed
	}
	var trailer metadata.MD
	session, err := c.rpc.Connect(ctx, &pb.ConnectionDetails{
		Url:        config.URL,
		User:       config.User,
		Password:   config.Password,
		ClientUUID: clientUUID,
		Properties: properties,
	}, grpc.Trailer(&trailer))
	if err != nil {
		return nil, grpcError(ctx, err, trailer)
	}
	if session == nil {
		return nil, errors.New("OJP server returned an empty session")
	}
	return &Connection{client: c, session: cloneSession(session)}, nil
}

func (c *Client) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil
	}
	c.closed = true
	if c.closer != nil {
		return c.closer()
	}
	return nil
}

func (c *Connection) ExecuteUpdate(ctx context.Context, sql string) (int64, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, ErrConnectionClosed
	}
	c.client.mu.RLock()
	defer c.client.mu.RUnlock()
	if c.client.closed {
		return 0, ErrClientClosed
	}

	var trailer metadata.MD
	result, err := c.client.rpc.ExecuteUpdate(ctx, &pb.StatementRequest{
		Session: cloneSession(c.session),
		Sql:     sql,
	}, grpc.Trailer(&trailer))
	if err != nil {
		return 0, grpcError(ctx, err, trailer)
	}
	if result == nil {
		return 0, errors.New("OJP server returned an empty update result")
	}
	c.applySession(result.GetSession())
	if result.GetType() != pb.ResultType_INTEGER {
		return 0, fmt.Errorf("unexpected update result type %s", result.GetType())
	}
	return int64(result.GetIntValue()), nil
}

func (c *Connection) Query(ctx context.Context, sql string) (*Result, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil, ErrConnectionClosed
	}
	c.client.mu.RLock()
	defer c.client.mu.RUnlock()
	if c.client.closed {
		return nil, ErrClientClosed
	}

	var trailer metadata.MD
	stream, err := c.client.rpc.ExecuteQuery(ctx, &pb.StatementRequest{
		Session: cloneSession(c.session),
		Sql:     sql,
	}, grpc.Trailer(&trailer))
	if err != nil {
		return nil, grpcError(ctx, err, trailer)
	}

	result := &Result{}
	for {
		message, recvErr := stream.Recv()
		if errors.Is(recvErr, io.EOF) {
			return result, nil
		}
		if recvErr != nil {
			return nil, grpcError(ctx, recvErr, trailer)
		}
		c.applySession(message.GetSession())
		queryResult := message.GetQueryResult()
		if queryResult == nil {
			continue
		}
		if len(result.Columns) == 0 {
			result.Columns = append(result.Columns, queryResult.GetLabels()...)
		}
		for _, row := range queryResult.GetRows() {
			columns := make([]any, len(row.GetColumns()))
			for index, value := range row.GetColumns() {
				columns[index], err = decodeValue(value)
				if err != nil {
					return nil, fmt.Errorf("decode query result column %d: %w", index, err)
				}
			}
			result.Rows = append(result.Rows, columns)
		}
	}
}

func (c *Connection) State() SessionState {
	c.mu.Lock()
	defer c.mu.Unlock()
	state := SessionState{
		ClientUUID:   c.session.GetClientUUID(),
		ConnHash:     c.session.GetConnHash(),
		SessionUUID:  c.session.GetSessionUUID(),
		TargetServer: c.session.GetTargetServer(),
	}
	if transaction := c.session.GetTransactionInfo(); transaction != nil {
		state.TransactionUUID = transaction.GetTransactionUUID()
		state.TransactionStatus = transaction.GetTransactionStatus().String()
	}
	return state
}

func (c *Connection) Close(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil
	}
	c.client.mu.RLock()
	defer c.client.mu.RUnlock()
	if c.client.closed {
		return ErrClientClosed
	}

	var trailer metadata.MD
	response, err := c.client.rpc.TerminateSession(ctx, cloneSession(c.session), grpc.Trailer(&trailer))
	if err != nil {
		return grpcError(ctx, err, trailer)
	}
	if response == nil || !response.GetTerminated() {
		return errors.New("OJP server did not terminate the session")
	}
	c.closed = true
	c.session = cloneSession(c.session)
	c.session.SessionStatus = pb.SessionStatus_SESSION_TERMINATED
	return nil
}

func (c *Connection) applySession(session *pb.SessionInfo) {
	if session != nil {
		c.session = cloneSession(session)
	}
}

func cloneSession(session *pb.SessionInfo) *pb.SessionInfo {
	if session == nil {
		return nil
	}
	return proto.Clone(session).(*pb.SessionInfo)
}

func getProcessUUID() (string, error) {
	processUUIDOnce.Do(func() {
		var value [16]byte
		if _, processUUIDErr = rand.Read(value[:]); processUUIDErr != nil {
			return
		}
		value[6] = (value[6] & 0x0f) | 0x40
		value[8] = (value[8] & 0x3f) | 0x80
		encoded := hex.EncodeToString(value[:])
		processUUID = encoded[:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:]
	})
	return processUUID, processUUIDErr
}

func grpcError(ctx context.Context, err error, trailer metadata.MD) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}
	const trailerName = "com.openjproxy.grpc.sqlerrorresponse-bin"
	for key, values := range trailer {
		if strings.EqualFold(key, trailerName) || strings.HasSuffix(strings.ToLower(key), ".sqlerrorresponse-bin") {
			for _, value := range values {
				response := new(pb.SqlErrorResponse)
				if proto.Unmarshal([]byte(value), response) == nil &&
					(response.GetReason() != "" || response.GetSqlState() != "" || response.GetVendorCode() != 0) {
					return &SQLError{
						SQLState:   response.GetSqlState(),
						VendorCode: response.GetVendorCode(),
						Message:    response.GetReason(),
						Cause:      err,
					}
				}
			}
		}
	}
	return err
}
