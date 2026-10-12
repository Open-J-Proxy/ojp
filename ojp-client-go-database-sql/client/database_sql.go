package client

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/csv"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"

	pb "github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/com/openjproxy/grpc"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/timestamppb"
)

const DriverName = "ojp"

func init() {
	sql.Register(DriverName, sqlDriver{})
}

type sqlDriver struct{}

func (sqlDriver) Open(name string) (driver.Conn, error) {
	connector, err := sqlConnectorFor(name)
	if err != nil {
		return nil, err
	}
	return connector.Connect(context.Background())
}

func (sqlDriver) OpenConnector(name string) (driver.Connector, error) {
	return sqlConnectorFor(name)
}

type sqlConnector struct {
	endpoint      string
	config        connectionConfig
	clientFactory func(string) (*rpcClient, error)
}

func sqlConnectorFor(dataSourceName string) (*sqlConnector, error) {
	dataSourceName = strings.TrimSpace(dataSourceName)
	reader := csv.NewReader(strings.NewReader(dataSourceName))
	record, err := reader.Read()
	if err != nil {
		return nil, fmt.Errorf("parse OJP data source name: %w", err)
	}
	if len(record) != 3 {
		return nil, errors.New("OJP data source name must contain a JDBC URL, username, and password")
	}
	if _, err := reader.Read(); !errors.Is(err, io.EOF) {
		if err != nil {
			return nil, fmt.Errorf("parse OJP data source name: %w", err)
		}
		return nil, errors.New("OJP data source name must contain exactly one CSV record")
	}

	const prefix = "jdbc:ojp["
	ojpURL := strings.TrimSpace(record[0])
	if !strings.HasPrefix(ojpURL, prefix) {
		return nil, fmt.Errorf("OJP data source name must start with %q", prefix)
	}
	endpointEnd := strings.Index(ojpURL[len(prefix):], "]_")
	if endpointEnd < 0 {
		return nil, errors.New("OJP data source name is missing the endpoint separator")
	}
	endpointEnd += len(prefix)
	endpoint := strings.TrimSpace(ojpURL[len(prefix):endpointEnd])
	if endpoint == "" {
		return nil, errors.New("OJP data source name has an empty endpoint")
	}
	if strings.Contains(endpoint, ",") {
		return nil, errors.New("OJP database/sql data source names currently support only one endpoint")
	}
	backendURL := strings.TrimSpace(ojpURL[endpointEnd+2:])
	if backendURL == "" {
		return nil, errors.New("OJP data source name has an empty backend JDBC URL")
	}
	if !strings.HasPrefix(backendURL, "jdbc:") {
		backendURL = "jdbc:" + backendURL
	}
	return &sqlConnector{
		endpoint: endpoint,
		config: connectionConfig{
			URL:      backendURL,
			User:     strings.TrimSpace(record[1]),
			Password: record[2],
		},
	}, nil
}

func (c *sqlConnector) Connect(ctx context.Context) (driver.Conn, error) {
	clientFactory := c.clientFactory
	if clientFactory == nil {
		clientFactory = newRPCClient
	}
	client, err := clientFactory(c.endpoint)
	if err != nil {
		return nil, err
	}
	connection, err := client.connect(ctx, c.config)
	if err != nil {
		_ = client.close()
		return nil, err
	}
	return &sqlConn{client: client, connection: connection}, nil
}

func (c *sqlConnector) Driver() driver.Driver {
	return sqlDriver{}
}

type sqlConn struct {
	client     *rpcClient
	connection *rpcConnection
}

func (c *sqlConn) Prepare(query string) (driver.Stmt, error) {
	if strings.TrimSpace(query) == "" {
		return nil, errors.New("SQL query must not be empty")
	}
	return &sqlStmt{connection: c.connection, query: query}, nil
}

func (c *sqlConn) Close() error {
	err := c.connection.close(context.Background())
	clientErr := c.client.close()
	if err != nil {
		return err
	}
	return clientErr
}

func (c *sqlConn) Begin() (driver.Tx, error) {
	return c.BeginTx(context.Background(), driver.TxOptions{})
}

func (c *sqlConn) BeginTx(ctx context.Context, options driver.TxOptions) (driver.Tx, error) {
	if options.Isolation != driver.IsolationLevel(sql.LevelDefault) {
		return nil, fmt.Errorf("OJP does not support setting transaction isolation through database/sql")
	}
	if options.ReadOnly {
		return nil, errors.New("OJP does not support read-only transaction options")
	}
	return withConnectionLock(c.connection, func() (driver.Tx, error) {
		var trailer metadata.MD
		session, err := c.client.rpc.StartTransaction(ctx, cloneSession(c.connection.session), grpc.Trailer(&trailer))
		if err != nil {
			return nil, grpcError(ctx, err, trailer)
		}
		if session == nil {
			return nil, errors.New("OJP server returned an empty transaction session")
		}
		c.connection.applySession(session)
		return &sqlTx{connection: c.connection}, nil
	})
}

func (c *sqlConn) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	parameters, err := toProtoParameters(args)
	if err != nil {
		return nil, err
	}
	count, err := c.connection.executeUpdate(ctx, query, parameters)
	if err != nil {
		return nil, err
	}
	return sqlResult{rowsAffected: count}, nil
}

func (c *sqlConn) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	parameters, err := toProtoParameters(args)
	if err != nil {
		return nil, err
	}
	result, err := c.connection.query(ctx, query, parameters)
	if err != nil {
		return nil, err
	}
	return &sqlRows{columns: result.Columns, rows: result.Rows}, nil
}

func (c *sqlConn) Ping(ctx context.Context) error {
	_, err := c.connection.query(ctx, "SELECT 1", nil)
	return err
}

func (c *sqlConn) IsValid() bool {
	return !c.connection.isClosed()
}

func (c *sqlConn) ResetSession(context.Context) error {
	if !c.IsValid() {
		return driver.ErrBadConn
	}
	return nil
}

func (c *sqlConn) checkOpen() error {
	if c.connection.closed {
		return errConnectionClosed
	}
	c.client.mu.RLock()
	defer c.client.mu.RUnlock()
	if c.client.closed {
		return errClientClosed
	}
	return nil
}

type sqlStmt struct {
	connection *rpcConnection
	query      string
}

func (s *sqlStmt) Close() error { return nil }

func (s *sqlStmt) NumInput() int { return -1 }

func (s *sqlStmt) Exec(args []driver.Value) (driver.Result, error) {
	return s.ExecContext(context.Background(), namedValues(args))
}

func (s *sqlStmt) Query(args []driver.Value) (driver.Rows, error) {
	return s.QueryContext(context.Background(), namedValues(args))
}

func (s *sqlStmt) ExecContext(ctx context.Context, args []driver.NamedValue) (driver.Result, error) {
	parameters, err := toProtoParameters(args)
	if err != nil {
		return nil, err
	}
	count, err := s.connection.executeUpdate(ctx, s.query, parameters)
	if err != nil {
		return nil, err
	}
	return sqlResult{rowsAffected: count}, nil
}

func (s *sqlStmt) QueryContext(ctx context.Context, args []driver.NamedValue) (driver.Rows, error) {
	parameters, err := toProtoParameters(args)
	if err != nil {
		return nil, err
	}
	result, err := s.connection.query(ctx, s.query, parameters)
	if err != nil {
		return nil, err
	}
	return &sqlRows{columns: result.Columns, rows: result.Rows}, nil
}

type sqlRows struct {
	columns []string
	rows    [][]any
	index   int
}

func (r *sqlRows) Columns() []string {
	return append([]string(nil), r.columns...)
}

func (r *sqlRows) Close() error {
	r.rows = nil
	r.index = 0
	return nil
}

func (r *sqlRows) Next(dest []driver.Value) error {
	if r.index >= len(r.rows) {
		return io.EOF
	}
	row := r.rows[r.index]
	if len(dest) != len(row) {
		return fmt.Errorf("database/sql supplied %d destinations for %d columns", len(dest), len(row))
	}
	for index, value := range row {
		converted, err := toDriverValue(value)
		if err != nil {
			return fmt.Errorf("convert query result column %d: %w", index, err)
		}
		dest[index] = converted
	}
	r.index++
	return nil
}

type sqlResult struct {
	rowsAffected int64
}

func (r sqlResult) LastInsertId() (int64, error) {
	return 0, errors.New("OJP does not return last-insert IDs")
}

func (r sqlResult) RowsAffected() (int64, error) {
	return r.rowsAffected, nil
}

type sqlTx struct {
	connection *rpcConnection
	done       bool
}

func (t *sqlTx) Commit() error {
	return t.finish(true)
}

func (t *sqlTx) Rollback() error {
	return t.finish(false)
}

func (t *sqlTx) finish(commit bool) error {
	_, err := withConnectionLock(t.connection, func() (any, error) {
		if t.done {
			return nil, errors.New("transaction has already completed")
		}
		c := t.connection
		ctx := context.Background()
		var trailer metadata.MD
		var session *pb.SessionInfo
		var rpcErr error
		if commit {
			session, rpcErr = c.client.rpc.CommitTransaction(ctx, cloneSession(c.session), grpc.Trailer(&trailer))
		} else {
			session, rpcErr = c.client.rpc.RollbackTransaction(ctx, cloneSession(c.session), grpc.Trailer(&trailer))
		}
		if rpcErr != nil {
			return nil, grpcError(ctx, rpcErr, trailer)
		}
		if session == nil {
			return nil, errors.New("OJP server returned an empty transaction session")
		}
		c.applySession(session)
		t.done = true
		return nil, nil
	})
	return err
}

func namedValues(args []driver.Value) []driver.NamedValue {
	values := make([]driver.NamedValue, len(args))
	for index, value := range args {
		values[index] = driver.NamedValue{Ordinal: index + 1, Value: value}
	}
	return values
}

func toProtoParameters(args []driver.NamedValue) ([]*pb.ParameterProto, error) {
	parameters := make([]*pb.ParameterProto, 0, len(args))
	for index, argument := range args {
		if argument.Name != "" {
			return nil, errors.New("OJP database/sql driver does not support named query parameters")
		}
		parameter := &pb.ParameterProto{Index: int32(index + 1)}
		switch value := argument.Value.(type) {
		case nil:
			parameter = buildParameter(index+1, pb.ParameterTypeProto_PT_NULL, &pb.ParameterValue{Value: &pb.ParameterValue_IsNull{IsNull: true}})
		case bool:
			parameter = buildParameter(index+1, pb.ParameterTypeProto_PT_BOOLEAN, encodeParam(value))
		case int64:
			parameter = buildParameter(index+1, pb.ParameterTypeProto_PT_LONG, encodeParam(value))
		case float64:
			parameter = buildParameter(index+1, pb.ParameterTypeProto_PT_DOUBLE, encodeParam(value))
		case string:
			parameter = buildParameter(index+1, pb.ParameterTypeProto_PT_STRING, encodeParam(value))
		case []byte:
			parameter = buildParameter(index+1, pb.ParameterTypeProto_PT_BYTES, encodeParam(value))
		case time.Time:
			timestamp := timestamppb.New(value)
			if err := timestamp.CheckValid(); err != nil {
				return nil, fmt.Errorf("invalid timestamp parameter: %w", err)
			}
			parameter = buildParameter(index+1, pb.ParameterTypeProto_PT_TIMESTAMP, encodeParam(value))
		default:
			return nil, fmt.Errorf("unsupported database/sql parameter type %T", argument.Value)
		}
		parameters = append(parameters, parameter)
	}
	return parameters, nil
}

func toDriverValue(value any) (driver.Value, error) {
	switch typed := value.(type) {
	case nil, bool, int64, float64, string, []byte, time.Time:
		return typed, nil
	case int32:
		return int64(typed), nil
	case float32:
		return float64(typed), nil
	default:
		return nil, fmt.Errorf("unsupported database/sql value %T", value)
	}
}

// buildParameter is a small generic helper that combines an index, a
// protobuf parameter type, and a pre-encoded value into a
// *pb.ParameterProto. It removes the boilerplate of constructing the
// struct literal at each call site.
func buildParameter[T pb.ParameterTypeProto](index int, paramType T, value *pb.ParameterValue) *pb.ParameterProto {
	return &pb.ParameterProto{
		Index:  int32(index),
		Type:   pb.ParameterTypeProto(paramType),
		Values: []*pb.ParameterValue{value},
	}
}

var (
	_ driver.Driver           = sqlDriver{}
	_ driver.DriverContext    = sqlDriver{}
	_ driver.Connector        = (*sqlConnector)(nil)
	_ driver.Conn             = (*sqlConn)(nil)
	_ driver.ConnBeginTx      = (*sqlConn)(nil)
	_ driver.ExecerContext    = (*sqlConn)(nil)
	_ driver.QueryerContext   = (*sqlConn)(nil)
	_ driver.Pinger           = (*sqlConn)(nil)
	_ driver.Validator        = (*sqlConn)(nil)
	_ driver.SessionResetter  = (*sqlConn)(nil)
	_ driver.Stmt             = (*sqlStmt)(nil)
	_ driver.StmtExecContext  = (*sqlStmt)(nil)
	_ driver.StmtQueryContext = (*sqlStmt)(nil)
	_ driver.Rows             = (*sqlRows)(nil)
	_ driver.Result           = sqlResult{}
	_ driver.Tx               = (*sqlTx)(nil)
)
