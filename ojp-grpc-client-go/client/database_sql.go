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

	pb "github.com/open-j-proxy/ojp-client/internal/gen/go/com/openjproxy/grpc"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
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
	endpoint string
	config   Config
}

func sqlConnectorFor(dataSourceName string) (*sqlConnector, error) {
	dataSourceName = strings.TrimSpace(dataSourceName)
	record, err := csv.NewReader(strings.NewReader(dataSourceName)).Read()
	if err != nil {
		return nil, fmt.Errorf("parse OJP data source name: %w", err)
	}
	if len(record) != 3 {
		return nil, errors.New("OJP data source name must contain a JDBC URL, username, and password")
	}

	const prefix = "jdbc:ojp["
	ojpURL := strings.TrimSpace(record[0])
	if !strings.HasPrefix(ojpURL, prefix) {
		return nil, fmt.Errorf("OJP data source name must start with %q", prefix)
	}
	endpointEnd := strings.IndexByte(ojpURL[len(prefix):], ']')
	if endpointEnd < 0 {
		return nil, errors.New("OJP data source name is missing the closing endpoint bracket")
	}
	endpointEnd += len(prefix)
	endpoint := strings.TrimSpace(strings.SplitN(ojpURL[len(prefix):endpointEnd], ",", 2)[0])
	if endpoint == "" {
		return nil, errors.New("OJP data source name has an empty endpoint")
	}
	if endpointEnd+1 >= len(ojpURL) || ojpURL[endpointEnd+1] != '_' {
		return nil, errors.New("OJP data source name must separate the endpoint and JDBC URL with an underscore")
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
		config: Config{
			URL:      backendURL,
			User:     strings.TrimSpace(record[1]),
			Password: record[2],
		},
	}, nil
}

func (c *sqlConnector) Connect(ctx context.Context) (driver.Conn, error) {
	client, err := NewClient(c.endpoint)
	if err != nil {
		return nil, err
	}
	connection, err := client.Connect(ctx, c.config)
	if err != nil {
		_ = client.Close()
		return nil, err
	}
	return &sqlConn{client: client, connection: connection}, nil
}

func (c *sqlConnector) Driver() driver.Driver {
	return sqlDriver{}
}

type sqlConn struct {
	client     *Client
	connection *Connection
}

func (c *sqlConn) Prepare(query string) (driver.Stmt, error) {
	if strings.TrimSpace(query) == "" {
		return nil, errors.New("SQL query must not be empty")
	}
	return &sqlStmt{connection: c.connection, query: query}, nil
}

func (c *sqlConn) Close() error {
	err := c.connection.Close(context.Background())
	clientErr := c.client.Close()
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
	c.connection.mu.Lock()
	defer c.connection.mu.Unlock()
	if err := c.checkOpen(); err != nil {
		return nil, err
	}
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
}

func (c *sqlConn) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	if len(args) != 0 {
		return nil, errors.New("OJP database/sql driver does not support query parameters")
	}
	count, err := c.connection.ExecuteUpdate(ctx, query)
	if err != nil {
		return nil, err
	}
	return sqlResult{rowsAffected: count}, nil
}

func (c *sqlConn) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	if len(args) != 0 {
		return nil, errors.New("OJP database/sql driver does not support query parameters")
	}
	result, err := c.connection.Query(ctx, query)
	if err != nil {
		return nil, err
	}
	return &sqlRows{columns: result.Columns, rows: result.Rows}, nil
}

func (c *sqlConn) Ping(ctx context.Context) error {
	_, err := c.connection.Query(ctx, "SELECT 1")
	return err
}

func (c *sqlConn) IsValid() bool {
	return !c.connection.State().Closed
}

func (c *sqlConn) ResetSession(context.Context) error {
	if !c.IsValid() {
		return driver.ErrBadConn
	}
	return nil
}

func (c *sqlConn) checkOpen() error {
	if c.connection.closed {
		return ErrConnectionClosed
	}
	c.client.mu.RLock()
	defer c.client.mu.RUnlock()
	if c.client.closed {
		return ErrClientClosed
	}
	return nil
}

type sqlStmt struct {
	connection *Connection
	query      string
}

func (s *sqlStmt) Close() error { return nil }

func (s *sqlStmt) NumInput() int { return 0 }

func (s *sqlStmt) Exec(args []driver.Value) (driver.Result, error) {
	return s.ExecContext(context.Background(), namedValues(args))
}

func (s *sqlStmt) Query(args []driver.Value) (driver.Rows, error) {
	return s.QueryContext(context.Background(), namedValues(args))
}

func (s *sqlStmt) ExecContext(ctx context.Context, args []driver.NamedValue) (driver.Result, error) {
	if len(args) != 0 {
		return nil, errors.New("OJP database/sql driver does not support query parameters")
	}
	count, err := s.connection.ExecuteUpdate(ctx, s.query)
	if err != nil {
		return nil, err
	}
	return sqlResult{rowsAffected: count}, nil
}

func (s *sqlStmt) QueryContext(ctx context.Context, args []driver.NamedValue) (driver.Rows, error) {
	if len(args) != 0 {
		return nil, errors.New("OJP database/sql driver does not support query parameters")
	}
	result, err := s.connection.Query(ctx, s.query)
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
	connection *Connection
	done       bool
}

func (t *sqlTx) Commit() error {
	return t.finish(true)
}

func (t *sqlTx) Rollback() error {
	return t.finish(false)
}

func (t *sqlTx) finish(commit bool) error {
	t.connection.mu.Lock()
	defer t.connection.mu.Unlock()
	if t.done {
		return errors.New("transaction has already completed")
	}
	c := t.connection
	c.client.mu.RLock()
	defer c.client.mu.RUnlock()
	if c.client.closed {
		return ErrClientClosed
	}
	if c.closed {
		return ErrConnectionClosed
	}
	ctx := context.Background()
	var trailer metadata.MD
	var session *pb.SessionInfo
	var err error
	if commit {
		session, err = c.client.rpc.CommitTransaction(ctx, cloneSession(c.session), grpc.Trailer(&trailer))
	} else {
		session, err = c.client.rpc.RollbackTransaction(ctx, cloneSession(c.session), grpc.Trailer(&trailer))
	}
	if err != nil {
		return grpcError(ctx, err, trailer)
	}
	if session == nil {
		return errors.New("OJP server returned an empty transaction session")
	}
	c.applySession(session)
	t.done = true
	return nil
}

func namedValues(args []driver.Value) []driver.NamedValue {
	values := make([]driver.NamedValue, len(args))
	for index, value := range args {
		values[index] = driver.NamedValue{Ordinal: index + 1, Value: value}
	}
	return values
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
