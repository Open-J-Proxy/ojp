package client

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"io"
	"strings"
	"testing"

	pb "github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/com/openjproxy/grpc"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
)

func TestSQLConnectorShouldParseOjpDataSourceName(t *testing.T) {
	connector, err := sqlConnectorFor(`"jdbc:ojp[localhost:1059,other:1060]_h2:mem:test",sa,`)
	if err == nil {
		t.Fatal("expected multi-endpoint DSN to fail")
	}
	connector, err = sqlConnectorFor(`"jdbc:ojp[[::1]:1059]_h2:mem:test",sa,`)
	if err != nil {
		t.Fatalf("parse IPv6 OJP data source name: %v", err)
	}
	if connector.endpoint != "[::1]:1059" || connector.config.URL != "jdbc:h2:mem:test" ||
		connector.config.User != "sa" || connector.config.Password != "" {
		t.Fatalf("unexpected connector config: %+v", connector)
	}
	for _, dataSourceName := range []string{
		"",
		"jdbc:postgresql://localhost/db,user,password",
		"jdbc:ojp[]_h2:mem:test,user,password",
		"jdbc:ojp[localhost:1059]h2:mem:test,user,password",
		"jdbc:ojp[localhost:1059]_,user,password",
		"jdbc:ojp[localhost:1059]_h2:mem:test,user,password\nextra,record,fields",
	} {
		if _, err := sqlConnectorFor(dataSourceName); err == nil {
			t.Errorf("expected malformed DSN %q to fail", dataSourceName)
		}
	}
}

func TestDatabaseSQLDriverShouldRegisterForSqlOpen(t *testing.T) {
	db, err := sql.Open(DriverName, `"jdbc:ojp[localhost:1059]_h2:mem:test",sa,`)
	if err != nil {
		t.Fatalf("sql.Open returned error: %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatalf("database Close returned error: %v", err)
	}
}

func TestDatabaseSQLDriverShouldExecuteQueryAndTransactions(t *testing.T) {
	startCalls := 0
	commitCalls := 0
	rollbackCalls := 0
	rpc := &sqlTestRPC{
		connect: func(details *pb.ConnectionDetails) (*pb.SessionInfo, error) {
			if details.GetUrl() != "jdbc:h2:mem:test" || details.GetUser() != "sa" {
				t.Fatalf("unexpected connection details: %+v", details)
			}
			return &pb.SessionInfo{ConnHash: "pool-1"}, nil
		},
		update: func(request *pb.StatementRequest) (*pb.OpResult, error) {
			if request.GetSession().GetConnHash() != "pool-1" {
				t.Fatalf("unexpected update session: %v", request.GetSession())
			}
			if strings.Contains(request.GetSql(), "?") &&
				(len(request.GetParameters()) != 1 || request.GetParameters()[0].GetIndex() != 1 ||
					request.GetParameters()[0].GetType() != pb.ParameterTypeProto_PT_LONG ||
					request.GetParameters()[0].GetValues()[0].GetLongValue() != 10) {
				t.Fatalf("unexpected update parameters: %+v", request.GetParameters())
			}
			return &pb.OpResult{
				Type:   pb.ResultType_INTEGER,
				Result: &pb.OpResult_IntValue{IntValue: 1},
				Session: &pb.SessionInfo{
					ConnHash: "pool-1", SessionUUID: "session-1",
				},
			}, nil
		},
		query: func(request *pb.StatementRequest) (grpc.ServerStreamingClient[pb.OpResult], error) {
			if strings.Contains(request.GetSql(), "?") &&
				(len(request.GetParameters()) != 1 || request.GetParameters()[0].GetIndex() != 1 ||
					request.GetParameters()[0].GetType() != pb.ParameterTypeProto_PT_LONG ||
					request.GetParameters()[0].GetValues()[0].GetLongValue() != 7) {
				t.Fatalf("unexpected query parameters: %+v", request.GetParameters())
			}
			return &stubQueryStream{results: []*pb.OpResult{{
				Result: &pb.OpResult_QueryResult{QueryResult: &pb.OpQueryResultProto{
					Labels: []string{"id", "name"},
					Rows: []*pb.ResultRow{{Columns: []*pb.ParameterValue{
						{Value: &pb.ParameterValue_IntValue{IntValue: 7}},
						{Value: &pb.ParameterValue_StringValue{StringValue: "seven"}},
					}}},
				}},
			}}}, nil
		},
		terminate: func() (*pb.SessionTerminationStatus, error) {
			return &pb.SessionTerminationStatus{Terminated: true}, nil
		},
		start: func(session *pb.SessionInfo) (*pb.SessionInfo, error) {
			startCalls++
			return &pb.SessionInfo{ConnHash: session.GetConnHash(), SessionUUID: "session-1"}, nil
		},
		commit: func(session *pb.SessionInfo) (*pb.SessionInfo, error) {
			commitCalls++
			return session, nil
		},
		rollback: func(session *pb.SessionInfo) (*pb.SessionInfo, error) {
			rollbackCalls++
			return session, nil
		},
	}
	connector := &sqlConnector{
		endpoint: "localhost:1059",
		config:   connectionConfig{URL: "jdbc:h2:mem:test", User: "sa"},
		clientFactory: func(string) (*rpcClient, error) {
			return newRPCClientWithRPC(rpc, func() error { return nil }), nil
		},
	}
	db := sql.OpenDB(connector)
	db.SetMaxOpenConns(1)
	defer db.Close()

	if err := db.PingContext(context.Background()); err != nil {
		t.Fatalf("PingContext returned error: %v", err)
	}
	result, err := db.ExecContext(context.Background(), "UPDATE sample SET name='seven'")
	if err != nil {
		t.Fatalf("ExecContext returned error: %v", err)
	}
	if affected, err := result.RowsAffected(); err != nil || affected != 1 {
		t.Fatalf("expected one affected row, got %d (error %v)", affected, err)
	}
	if _, err := db.ExecContext(context.Background(), "UPDATE sample SET id=?", int64(10)); err != nil {
		t.Fatalf("parameterized ExecContext returned error: %v", err)
	}
	var id int64
	var name string
	if err := db.QueryRowContext(context.Background(), "SELECT id, name FROM sample WHERE id=?", int64(7)).Scan(&id, &name); err != nil {
		t.Fatalf("QueryRowContext returned error: %v", err)
	}
	if id != 7 || name != "seven" {
		t.Fatalf("unexpected scanned row: (%d, %q)", id, name)
	}

	tx, err := db.BeginTx(context.Background(), nil)
	if err != nil {
		t.Fatalf("BeginTx returned error: %v", err)
	}
	if _, err := tx.ExecContext(context.Background(), "UPDATE sample SET name='committed'"); err != nil {
		t.Fatalf("transaction ExecContext returned error: %v", err)
	}
	if err := tx.Commit(); err != nil {
		t.Fatalf("Commit returned error: %v", err)
	}
	tx, err = db.BeginTx(context.Background(), nil)
	if err != nil {
		t.Fatalf("second BeginTx returned error: %v", err)
	}
	if err := tx.Rollback(); err != nil {
		t.Fatalf("Rollback returned error: %v", err)
	}
	if startCalls != 2 || commitCalls != 1 || rollbackCalls != 1 {
		t.Fatalf("unexpected transaction RPC counts: start=%d commit=%d rollback=%d", startCalls, commitCalls, rollbackCalls)
	}
	if err := db.Close(); err != nil {
		t.Fatalf("Close returned error: %v", err)
	}
}

func TestDatabaseSQLDriverShouldRejectUnsupportedArgumentsAndOptions(t *testing.T) {
	connection := &sqlConn{connection: &rpcConnection{
		client:  newRPCClientWithRPC(&sqlTestRPC{}, func() error { return nil }),
		session: &pb.SessionInfo{},
	}}
	if _, err := connection.ExecContext(context.Background(), "UPDATE sample SET id=:id", []driver.NamedValue{{Name: "id", Ordinal: 1, Value: int64(1)}}); err == nil {
		t.Fatal("expected named query arguments to be rejected")
	}
	if _, err := toProtoParameters([]driver.NamedValue{{Ordinal: 1, Value: struct{}{}}}); err == nil {
		t.Fatal("expected unsupported parameter type to be rejected")
	}
	if _, err := connection.BeginTx(context.Background(), driver.TxOptions{ReadOnly: true}); err == nil {
		t.Fatal("expected read-only transaction option to be rejected")
	}
	if _, err := connection.BeginTx(context.Background(), driver.TxOptions{Isolation: driver.IsolationLevel(sql.LevelSerializable)}); err == nil {
		t.Fatal("expected isolation option to be rejected")
	}
}

type sqlTestRPC struct {
	pb.StatementServiceClient
	connect   func(*pb.ConnectionDetails) (*pb.SessionInfo, error)
	update    func(*pb.StatementRequest) (*pb.OpResult, error)
	query     func(*pb.StatementRequest) (grpc.ServerStreamingClient[pb.OpResult], error)
	terminate func() (*pb.SessionTerminationStatus, error)
	start     func(*pb.SessionInfo) (*pb.SessionInfo, error)
	commit    func(*pb.SessionInfo) (*pb.SessionInfo, error)
	rollback  func(*pb.SessionInfo) (*pb.SessionInfo, error)
}

func (s *sqlTestRPC) Connect(_ context.Context, details *pb.ConnectionDetails, _ ...grpc.CallOption) (*pb.SessionInfo, error) {
	return s.connect(details)
}

func (s *sqlTestRPC) ExecuteUpdate(_ context.Context, request *pb.StatementRequest, _ ...grpc.CallOption) (*pb.OpResult, error) {
	return s.update(request)
}

func (s *sqlTestRPC) ExecuteQuery(_ context.Context, request *pb.StatementRequest, _ ...grpc.CallOption) (grpc.ServerStreamingClient[pb.OpResult], error) {
	return s.query(request)
}

func (s *sqlTestRPC) TerminateSession(_ context.Context, _ *pb.SessionInfo, _ ...grpc.CallOption) (*pb.SessionTerminationStatus, error) {
	return s.terminate()
}

func (s *sqlTestRPC) StartTransaction(_ context.Context, session *pb.SessionInfo, _ ...grpc.CallOption) (*pb.SessionInfo, error) {
	return s.start(session)
}

func (s *sqlTestRPC) CommitTransaction(_ context.Context, session *pb.SessionInfo, _ ...grpc.CallOption) (*pb.SessionInfo, error) {
	return s.commit(session)
}

func (s *sqlTestRPC) RollbackTransaction(_ context.Context, session *pb.SessionInfo, _ ...grpc.CallOption) (*pb.SessionInfo, error) {
	return s.rollback(session)
}

type stubQueryStream struct {
	results []*pb.OpResult
	index   int
}

func (s *stubQueryStream) Recv() (*pb.OpResult, error) {
	if s.index == len(s.results) {
		return nil, io.EOF
	}
	result := s.results[s.index]
	s.index++
	return result, nil
}

func (*stubQueryStream) Header() (metadata.MD, error) { return metadata.MD{}, nil }
func (*stubQueryStream) Trailer() metadata.MD         { return metadata.MD{} }
func (*stubQueryStream) CloseSend() error             { return nil }
func (*stubQueryStream) Context() context.Context     { return context.Background() }
func (*stubQueryStream) SendMsg(any) error            { return nil }
func (*stubQueryStream) RecvMsg(any) error            { return nil }
