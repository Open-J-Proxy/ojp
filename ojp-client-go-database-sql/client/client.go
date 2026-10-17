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

	pb "github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/com/openjproxy/grpc"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/proto"
)

var (
	errClientClosed     = errors.New("OJP client is closed")
	errConnectionClosed = errors.New("OJP connection is closed")
	errStatementClosed  = errors.New("OJP prepared statement is closed")
	errNoMoreRows       = errors.New("no more rows")
)

type connectionConfig struct {
	URL        string
	User       string
	Password   string
	Properties map[string]string
}

type rpcClient struct {
	rpc    pb.StatementServiceClient
	closer func() error
	mu     sync.RWMutex
	closed bool
}

type rpcConnection struct {
	client  *rpcClient
	session *pb.SessionInfo
	mu      sync.Mutex
	closed  bool
}

type queryResult struct {
	Columns       []string
	Rows          [][]any
	cursor        int
	resultSetUUID string
}

var processUUIDOnce sync.Once
var processUUID string
var processUUIDErr error

func newRPCClient(endpoint string) (*rpcClient, error) {
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
	return newRPCClientWithRPC(pb.NewStatementServiceClient(conn), conn.Close), nil
}

func newRPCClientWithRPC(rpc pb.StatementServiceClient, closer func() error) *rpcClient {
	return &rpcClient{rpc: rpc, closer: closer}
}

func (c *rpcClient) connect(ctx context.Context, config connectionConfig) (*rpcConnection, error) {
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
		return nil, errClientClosed
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
	return &rpcConnection{client: c, session: cloneSession(session)}, nil
}

func (c *rpcClient) close() error {
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

// propAutoGenKeys maps to java.sql.Statement.RETURN_GENERATED_KEYS.
const propAutoGenKeys = "STATEMENT_AUTO_GENERATED_KEYS_KEY"

func (c *rpcConnection) executeUpdate(ctx context.Context, sql string, parameters []*pb.ParameterProto) (int64, string, error) {
	return c.executeUpdateWithStatement(ctx, sql, parameters, "")
}

func (c *rpcConnection) executeUpdateWithStatement(
	ctx context.Context,
	sql string,
	parameters []*pb.ParameterProto,
	statementUUID string,
) (int64, string, error) {
	var generatedKeysUUID string
	var updateCount int64
	err := withConnectionLockError(c, func() error {
		var trailer metadata.MD
		result, err := c.client.rpc.ExecuteUpdate(ctx, &pb.StatementRequest{
			Session:       cloneSession(c.session),
			Sql:           sql,
			Parameters:    parameters,
			StatementUUID: statementUUID,
			Properties: []*pb.PropertyEntry{{
				Key:   propAutoGenKeys,
				Value: &pb.PropertyEntry_IntValue{IntValue: 1},
			}},
		}, grpc.Trailer(&trailer))
		if err != nil {
			return grpcError(ctx, err, trailer)
		}
		if result == nil {
			return errors.New("OJP server returned an empty update result")
		}
		c.applySession(result.GetSession())
		generatedKeysUUID = result.GetUuid()
		if v, ok := result.GetResult().(*pb.OpResult_IntValue); ok {
			updateCount = int64(v.IntValue)
		}
		return nil
	})
	return updateCount, generatedKeysUUID, err
}

func (c *rpcConnection) callResource(
	ctx context.Context,
	resourceType pb.ResourceType,
	resourceUUID string,
	target *pb.TargetCall,
) error {
	_, err := withConnectionLock(c, func() (any, error) {
		var trailer metadata.MD
		response, rpcErr := c.client.rpc.CallResource(ctx, &pb.CallResourceRequest{
			Session:      cloneSession(c.session),
			ResourceType: resourceType,
			ResourceUUID: resourceUUID,
			Target:       target,
		}, grpc.Trailer(&trailer))
		if rpcErr != nil {
			return nil, grpcError(ctx, rpcErr, trailer)
		}
		if response == nil {
			return nil, errors.New("OJP server returned an empty resource response")
		}
		c.applySession(response.GetSession())
		return nil, nil
	})
	return err
}

func (c *rpcConnection) closePreparedStatement(ctx context.Context, statementUUID string) error {
	return c.callResource(ctx, pb.ResourceType_RES_PREPARED_STATEMENT, statementUUID, &pb.TargetCall{
		CallType: pb.CallType_CALL_CLOSE,
	})
}

func withConnectionLockError(c *rpcConnection, fn func() error) error {
	_, err := withConnectionLock(c, func() (any, error) {
		return nil, fn()
	})
	return err
}

func (c *rpcConnection) query(ctx context.Context, sql string, parameters []*pb.ParameterProto) (*queryResult, error) {
	return withConnectionLock(c, func() (*queryResult, error) {
		var trailer metadata.MD
		stream, err := c.client.rpc.ExecuteQuery(ctx, &pb.StatementRequest{
			Session:    cloneSession(c.session),
			Sql:        sql,
			Parameters: parameters,
		}, grpc.Trailer(&trailer))
		if err != nil {
			return nil, grpcError(ctx, err, trailer)
		}

		result := &queryResult{}
		for {
			message, recvErr := stream.Recv()
			if errors.Is(recvErr, io.EOF) {
				return result, nil
			}
			if recvErr != nil {
				return nil, grpcError(ctx, recvErr, stream.Trailer())
			}
			c.applySession(message.GetSession())
			queryResult := message.GetQueryResult()
			if queryResult == nil {
				continue
			}
			if result.resultSetUUID == "" {
				result.resultSetUUID = queryResult.GetResultSetUUID()
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
	})
}

func (c *rpcConnection) fetchNextRows(ctx context.Context, resultSetUUID string, size int32) (*queryResult, error) {
	return withConnectionLock(c, func() (*queryResult, error) {
		var trailer metadata.MD
		result, err := c.client.rpc.FetchNextRows(ctx, &pb.ResultSetFetchRequest{
			Session:       cloneSession(c.session),
			ResultSetUUID: resultSetUUID,
			Size:          size,
		}, grpc.Trailer(&trailer))
		if err != nil {
			return nil, grpcError(ctx, err, trailer)
		}
		if result == nil {
			return nil, errors.New("OJP server returned an empty fetch result")
		}
		c.applySession(result.GetSession())
		qr := &queryResult{resultSetUUID: resultSetUUID}
		queryResult := result.GetQueryResult()
		if queryResult == nil {
			return qr, nil
		}
		if len(qr.Columns) == 0 {
			qr.Columns = append(qr.Columns, queryResult.GetLabels()...)
		}
		for _, row := range queryResult.GetRows() {
			columns := make([]any, len(row.GetColumns()))
			for index, value := range row.GetColumns() {
				columns[index], err = decodeValue(value)
				if err != nil {
					return nil, fmt.Errorf("decode fetch result column %d: %w", index, err)
				}
			}
			qr.Rows = append(qr.Rows, columns)
		}
		return qr, nil
	})
}

// fetchGeneratedKeys resolves one generated key via the PS UUID.
func (c *rpcConnection) fetchGeneratedKeys(ctx context.Context, psUUID string) (int64, error) {
	rsUUID, err := withConnectionLock(c, func() (string, error) {
		var trailer metadata.MD
		resp, err := c.client.rpc.CallResource(ctx, &pb.CallResourceRequest{
			Session:      cloneSession(c.session),
			ResourceType: pb.ResourceType_RES_PREPARED_STATEMENT,
			ResourceUUID: psUUID,
			Target: &pb.TargetCall{
				CallType:     pb.CallType_CALL_GET,
				ResourceName: "GeneratedKeys",
			},
		}, grpc.Trailer(&trailer))
		if err != nil {
			return "", grpcError(ctx, err, trailer)
		}
		if resp == nil {
			return "", errors.New("OJP server returned an empty generated-keys response")
		}
		c.applySession(resp.GetSession())
		if len(resp.GetValues()) == 0 {
			return "", nil
		}
		uuid, err := decodeValueTo[string](resp.GetValues()[0])
		if err != nil {
			return "", err
		}
		return uuid, nil
	})
	if err != nil {
		return 0, err
	}
	if rsUUID == "" {
		return 0, nil
	}
	rows, err := c.fetchNextRows(ctx, rsUUID, 100)
	resultSetCloseErr := c.closeResultSet(ctx, rsUUID)
	if err != nil {
		return 0, err
	}
	if resultSetCloseErr != nil {
		return 0, resultSetCloseErr
	}
	if len(rows.Rows) == 0 || len(rows.Rows[0]) == 0 {
		return 0, nil
	}
	switch value := rows.Rows[0][0].(type) {
	case int32:
		return int64(value), nil
	case int64:
		return value, nil
	}
	return 0, nil
}

func (c *rpcConnection) fetchGeneratedKeysAndClose(ctx context.Context, psUUID string) (int64, error) {
	lastID, fetchErr := c.fetchGeneratedKeys(ctx, psUUID)
	closeErr := c.closePreparedStatement(ctx, psUUID)
	if fetchErr != nil {
		return 0, fetchErr
	}
	if closeErr != nil {
		return 0, closeErr
	}
	return lastID, nil
}

func (c *rpcConnection) closeResultSet(ctx context.Context, resultSetUUID string) error {
	return c.callResource(ctx, pb.ResourceType_RES_RESULT_SET, resultSetUUID, &pb.TargetCall{
		CallType: pb.CallType_CALL_CLOSE,
	})
}

func (c *rpcConnection) close(ctx context.Context) error {
	_, err := withConnectionLock(c, func() (any, error) {
		if c.closed {
			return nil, nil
		}
		var trailer metadata.MD
		response, rpcErr := c.client.rpc.TerminateSession(ctx, cloneSession(c.session), grpc.Trailer(&trailer))
		if rpcErr != nil {
			return nil, grpcError(ctx, rpcErr, trailer)
		}
		if response == nil || !response.GetTerminated() {
			return nil, errors.New("OJP server did not terminate the session")
		}
		c.closed = true
		c.session = cloneSession(c.session)
		c.session.SessionStatus = pb.SessionStatus_SESSION_TERMINATED
		return nil, nil
	})
	return err
}

func (c *rpcConnection) isClosed() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.closed
}

func (c *rpcConnection) applySession(session *pb.SessionInfo) {
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
		if strings.EqualFold(key, trailerName) || strings.HasSuffix(strings.ToLower(key), "sqlerrorresponse-bin") {
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
