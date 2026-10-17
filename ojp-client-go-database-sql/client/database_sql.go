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
	return c.PrepareContext(context.Background(), query)
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
	count, generatedKeysUUID, err := c.connection.executeUpdate(ctx, query, parameters)
	if err != nil {
		return nil, err
	}
	var lastID int64
	if generatedKeysUUID != "" {
		lastID, err = c.connection.fetchGeneratedKeysAndClose(ctx, generatedKeysUUID)
		if err != nil {
			return nil, err
		}
	}
	return sqlResult{rowsAffected: count, lastID: lastID}, nil
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
	return newSQLRows(ctx, c.connection, result)
}

func (c *sqlConn) Ping(ctx context.Context) error {
	result, err := c.connection.query(ctx, "SELECT 1", nil)
	if err != nil {
		return err
	}
	if result.resultSetUUID != "" {
		return c.connection.closeResultSet(ctx, result.resultSetUUID)
	}
	return nil
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

type sqlRows struct {
	columns        []string
	rows           [][]any
	index          int
	resultSetUUID  string
	connection     *rpcConnection
	columnMetadata []ColumnMetadata
	closed         bool
}

func (r *sqlRows) Columns() []string {
	return append([]string(nil), r.columns...)
}

func (r *sqlRows) Close() error {
	if r.closed {
		return nil
	}
	r.closed = true
	r.rows = nil
	r.index = 0
	if r.connection != nil && r.resultSetUUID != "" {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return r.connection.closeResultSet(ctx, r.resultSetUUID)
	}
	return nil
}

func (r *sqlRows) Next(dest []driver.Value) error {
	if r.closed {
		return io.EOF
	}
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

func (r *sqlRows) ColumnTypeDatabaseTypeName(index int) string {
	if index < 0 || index >= len(r.columnMetadata) {
		return ""
	}
	return r.columnMetadata[index].TypeName
}

func (r *sqlRows) ColumnTypeNullable(index int) (nullable, ok bool) {
	if index < 0 || index >= len(r.columnMetadata) {
		return false, false
	}
	column := r.columnMetadata[index]
	return column.Nullable, column.NullableKnown
}

func newSQLRows(ctx context.Context, connection *rpcConnection, result *queryResult) (*sqlRows, error) {
	rows := &sqlRows{
		columns:       result.Columns,
		rows:          result.Rows,
		resultSetUUID: result.resultSetUUID,
		connection:    connection,
	}
	if result.resultSetUUID != "" {
		metadata, err := connection.Metadata(ctx, &MetadataRequest{
			ResultSetUUID: result.resultSetUUID,
			Columns:       result.Columns,
		})
		if err != nil {
			_ = rows.Close()
			return nil, err
		}
		rows.columnMetadata = metadata
	}
	return rows, nil
}

type sqlResult struct {
	rowsAffected int64
	lastID       int64
}

func (r sqlResult) LastInsertId() (int64, error) {
	return r.lastID, nil
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
		var parameter *pb.ParameterProto
		switch value := argument.Value.(type) {
		case nil:
			parameter = &pb.ParameterProto{Index: int32(index + 1), Type: pb.ParameterTypeProto_PT_NULL, Values: []*pb.ParameterValue{{Value: &pb.ParameterValue_IntValue{IntValue: 0}}}}
		case bool:
			parameter = encodeParamTyped(index+1, value)
		case int64:
			parameter = encodeParamTyped(index+1, value)
		case float64:
			parameter = encodeParamTyped(index+1, value)
		case string:
			parameter = encodeParamTyped(index+1, value)
		case []byte:
			parameter = encodeParamTyped(index+1, value)
		case time.Time:
			timestamp := timestamppb.New(value)
			if err := timestamp.CheckValid(); err != nil {
				return nil, fmt.Errorf("invalid timestamp parameter: %w", err)
			}
			parameter = encodeParamTyped(index+1, value)
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

func buildParameter[T pb.ParameterTypeProto](index int, paramType T, value *pb.ParameterValue) *pb.ParameterProto {
	return &pb.ParameterProto{
		Index:  int32(index),
		Type:   pb.ParameterTypeProto(paramType),
		Values: []*pb.ParameterValue{value},
	}
}

// sqlPreparedStatement carries a StatementUUID for server-side plan reuse.
type sqlPreparedStatement struct {
	connection    *rpcConnection
	query         string
	statementUUID string
}

func (s *sqlPreparedStatement) Close() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return s.connection.closePreparedStatement(ctx, s.statementUUID)
}

func (s *sqlPreparedStatement) NumInput() int { return -1 }

func (s *sqlPreparedStatement) Exec(args []driver.Value) (driver.Result, error) {
	return s.ExecContext(context.Background(), namedValues(args))
}

func (s *sqlPreparedStatement) Query(args []driver.Value) (driver.Rows, error) {
	return s.QueryContext(context.Background(), namedValues(args))
}

func (s *sqlPreparedStatement) ExecContext(ctx context.Context, args []driver.NamedValue) (driver.Result, error) {
	parameters, err := toProtoParameters(args)
	if err != nil {
		return nil, err
	}
	count, generatedKeysUUID, err := s.connection.executeUpdateWithStatement(ctx, s.query, parameters, s.statementUUID)
	if err != nil {
		return nil, err
	}
	var lastID int64
	if generatedKeysUUID != "" {
		lastID, err = s.connection.fetchGeneratedKeys(ctx, generatedKeysUUID)
		if err != nil {
			return nil, err
		}
	}
	return sqlResult{rowsAffected: count, lastID: lastID}, nil
}

func (s *sqlPreparedStatement) QueryContext(ctx context.Context, args []driver.NamedValue) (driver.Rows, error) {
	parameters, err := toProtoParameters(args)
	if err != nil {
		return nil, err
	}
	result, err := s.connection.query(ctx, s.query, parameters)
	if err != nil {
		return nil, err
	}
	return newSQLRows(ctx, s.connection, result)
}

type sqlCallableStatement struct {
	*sqlPreparedStatement
	outParams []driver.NamedValue
}

func (s *sqlCallableStatement) Close() error { return s.sqlPreparedStatement.Close() }

func (s *sqlCallableStatement) NumInput() int { return -1 }

func (s *sqlCallableStatement) Exec(args []driver.Value) (driver.Result, error) {
	return s.ExecContext(context.Background(), namedValues(args))
}

func (s *sqlCallableStatement) Query(args []driver.Value) (driver.Rows, error) {
	return s.QueryContext(context.Background(), namedValues(args))
}

func (s *sqlCallableStatement) ExecContext(ctx context.Context, args []driver.NamedValue) (driver.Result, error) {
	parameters, err := toProtoParameters(args)
	if err != nil {
		return nil, err
	}
	count, generatedKeysUUID, err := s.connection.executeUpdateWithStatement(ctx, s.query, parameters, s.statementUUID)
	if err != nil {
		return nil, err
	}
	var lastID int64
	if generatedKeysUUID != "" {
		lastID, err = s.connection.fetchGeneratedKeys(ctx, generatedKeysUUID)
		if err != nil {
			return nil, err
		}
	}
	return sqlResult{rowsAffected: count, lastID: lastID}, nil
}

func (s *sqlCallableStatement) QueryContext(ctx context.Context, args []driver.NamedValue) (driver.Rows, error) {
	parameters, err := toProtoParameters(args)
	if err != nil {
		return nil, err
	}
	result, err := s.connection.queryWithStatement(ctx, s.query, parameters, s.statementUUID)
	if err != nil {
		return nil, err
	}
	return newSQLRows(ctx, s.connection, result)
}

func (c *sqlConn) PrepareContext(ctx context.Context, query string) (driver.Stmt, error) {
	if strings.TrimSpace(query) == "" {
		return nil, errors.New("SQL query must not be empty")
	}
	stmtUUID, err := c.connection.prepareStatement(ctx, query)
	if err != nil {
		return nil, err
	}
	stmt := &sqlPreparedStatement{connection: c.connection, query: query, statementUUID: stmtUUID}
	return stmt, nil
}

type GeneratedKeysRequest struct {
	StatementUUID string
	ColumnNames   []string
	ColumnIndexes []int32
}

// GeneratedKeys returns key rows for a prepared-statement UUID.
func (c *rpcConnection) GeneratedKeys(ctx context.Context, req *GeneratedKeysRequest) ([][]any, error) {
	rsUUID, err := withConnectionLock(c, func() (string, error) {
		var trailer metadata.MD
		resp, err := c.client.rpc.CallResource(ctx, &pb.CallResourceRequest{
			Session:      cloneSession(c.session),
			ResourceType: pb.ResourceType_RES_PREPARED_STATEMENT,
			ResourceUUID: req.StatementUUID,
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
		return decodeValueTo[string](resp.GetValues()[0])
	})
	if err != nil || rsUUID == "" {
		return nil, err
	}
	rows, err := c.fetchNextRows(ctx, rsUUID, 100)
	if err != nil {
		return nil, err
	}
	return rows.Rows, nil
}

type MetadataRequest struct {
	ResultSetUUID string
	Columns       []string
}

type ColumnMetadata struct {
	Name          string
	SQLType       int32
	TypeName      string
	Nullable      bool
	NullableKnown bool
	ReadOnly      bool
}

func (c *rpcConnection) Metadata(ctx context.Context, req *MetadataRequest) ([]ColumnMetadata, error) {
	countValue, err := c.resultSetMetadataValue(ctx, req.ResultSetUUID, "ColumnCount", pb.CallType_CALL_GET, 0)
	if err != nil {
		return nil, err
	}
	count, ok := metadataInteger(countValue)
	if !ok || count < 0 {
		return nil, fmt.Errorf("OJP server returned an invalid metadata column count: %v", countValue)
	}
	columns := make([]ColumnMetadata, 0, count)
	for index := 1; index <= int(count); index++ {
		column := ColumnMetadata{}
		if index <= len(req.Columns) {
			column.Name = req.Columns[index-1]
		}
		name, err := c.resultSetMetadataValue(ctx, req.ResultSetUUID, "ColumnLabel", pb.CallType_CALL_GET, index)
		if err != nil {
			return nil, err
		}
		if value, ok := name.(string); ok {
			column.Name = value
		}
		typeName, err := c.resultSetMetadataValue(ctx, req.ResultSetUUID, "ColumnTypeName", pb.CallType_CALL_GET, index)
		if err != nil {
			return nil, err
		}
		column.TypeName, _ = typeName.(string)
		sqlType, err := c.resultSetMetadataValue(ctx, req.ResultSetUUID, "ColumnType", pb.CallType_CALL_GET, index)
		if err != nil {
			return nil, err
		}
		if value, ok := metadataInteger(sqlType); ok {
			column.SQLType = value
		}
		nullable, err := c.resultSetMetadataValue(ctx, req.ResultSetUUID, "Nullable", pb.CallType_CALL_IS, index)
		if err != nil {
			return nil, err
		}
		if value, ok := metadataInteger(nullable); ok {
			column.Nullable = value == 1
			column.NullableKnown = value != 2
		}
		readOnly, err := c.resultSetMetadataValue(ctx, req.ResultSetUUID, "ReadOnly", pb.CallType_CALL_IS, index)
		if err != nil {
			return nil, err
		}
		column.ReadOnly, _ = readOnly.(bool)
		columns = append(columns, column)
	}
	return columns, nil
}

func metadataInteger(value any) (int32, bool) {
	switch typed := value.(type) {
	case int32:
		return typed, true
	case int64:
		return int32(typed), true
	default:
		return 0, false
	}
}

func (c *rpcConnection) resultSetMetadataValue(
	ctx context.Context,
	resultSetUUID string,
	name string,
	callType pb.CallType,
	index int,
) (any, error) {
	nextCall := &pb.TargetCall{CallType: callType, ResourceName: name}
	if index > 0 {
		nextCall.Params = []*pb.ParameterValue{{Value: &pb.ParameterValue_IntValue{IntValue: int32(index)}}}
	}
	var value any
	err := withConnectionLockError(c, func() error {
		var trailer metadata.MD
		response, err := c.client.rpc.CallResource(ctx, &pb.CallResourceRequest{
			Session:      cloneSession(c.session),
			ResourceType: pb.ResourceType_RES_RESULT_SET,
			ResourceUUID: resultSetUUID,
			Target: &pb.TargetCall{
				CallType:     pb.CallType_CALL_GET,
				ResourceName: "MetaData",
				NextCall:     nextCall,
			},
		}, grpc.Trailer(&trailer))
		if err != nil {
			return grpcError(ctx, err, trailer)
		}
		if response == nil || len(response.GetValues()) != 1 {
			return errors.New("OJP server returned an invalid result-set metadata response")
		}
		c.applySession(response.GetSession())
		value, err = decodeValue(response.GetValues()[0])
		return err
	})
	return value, err
}

var (
	_ driver.Driver                         = sqlDriver{}
	_ driver.DriverContext                  = sqlDriver{}
	_ driver.Connector                      = (*sqlConnector)(nil)
	_ driver.Conn                           = (*sqlConn)(nil)
	_ driver.ConnPrepareContext             = (*sqlConn)(nil)
	_ driver.ConnBeginTx                    = (*sqlConn)(nil)
	_ driver.ExecerContext                  = (*sqlConn)(nil)
	_ driver.QueryerContext                 = (*sqlConn)(nil)
	_ driver.Pinger                         = (*sqlConn)(nil)
	_ driver.Validator                      = (*sqlConn)(nil)
	_ driver.SessionResetter                = (*sqlConn)(nil)
	_ driver.Stmt                           = (*sqlPreparedStatement)(nil)
	_ driver.StmtExecContext                = (*sqlPreparedStatement)(nil)
	_ driver.StmtQueryContext               = (*sqlPreparedStatement)(nil)
	_ driver.Stmt                           = (*sqlCallableStatement)(nil)
	_ driver.StmtExecContext                = (*sqlCallableStatement)(nil)
	_ driver.StmtQueryContext               = (*sqlCallableStatement)(nil)
	_ driver.Rows                           = (*sqlRows)(nil)
	_ driver.RowsColumnTypeDatabaseTypeName = (*sqlRows)(nil)
	_ driver.RowsColumnTypeNullable         = (*sqlRows)(nil)
	_ driver.Result                         = sqlResult{}
	_ driver.Tx                             = (*sqlTx)(nil)
)
