package client

import (
	"context"
	"errors"
	"io"
	"reflect"
	"strings"
	"testing"
	"time"

	pb "github.com/open-j-proxy/ojp-client/internal/gen/go/com/openjproxy/grpc"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"
)

func TestConnectionShouldApplyServerSessionUpdatesAndRejectUseAfterClose(t *testing.T) {
	closeCalls := 0
	rpc := &stubStatementService{
		connect: func(_ context.Context, details *pb.ConnectionDetails, _ ...grpc.CallOption) (*pb.SessionInfo, error) {
			if details.GetClientUUID() == "" {
				t.Fatal("expected generated process client UUID")
			}
			return &pb.SessionInfo{ConnHash: "pool-1", ClientUUID: details.GetClientUUID()}, nil
		},
		update: func(_ context.Context, request *pb.StatementRequest, _ ...grpc.CallOption) (*pb.OpResult, error) {
			if request.GetSession().GetConnHash() != "pool-1" {
				t.Fatalf("unexpected request session: %v", request.GetSession())
			}
			return &pb.OpResult{
				Type:   pb.ResultType_INTEGER,
				Result: &pb.OpResult_IntValue{IntValue: 1},
				Session: &pb.SessionInfo{
					ConnHash: "pool-1", ClientUUID: request.GetSession().GetClientUUID(),
					SessionUUID: "session-1", TargetServer: "localhost:1059",
				},
			}, nil
		},
		query: func(_ context.Context, _ *pb.StatementRequest, _ ...grpc.CallOption) (grpc.ServerStreamingClient[pb.OpResult], error) {
			return &stubQueryStream{results: []*pb.OpResult{{
				Session: &pb.SessionInfo{
					ConnHash: "pool-1", ClientUUID: "client-1", SessionUUID: "session-2",
					TargetServer: "localhost:1059",
				},
				Result: &pb.OpResult_QueryResult{QueryResult: &pb.OpQueryResultProto{
					Labels: []string{"id", "name"},
					Rows: []*pb.ResultRow{{
						Columns: []*pb.ParameterValue{
							{Value: &pb.ParameterValue_IntValue{IntValue: 42}},
							{Value: &pb.ParameterValue_StringValue{StringValue: "hello"}},
						},
					}},
				}},
			}}}, nil
		},
		terminate: func(_ context.Context, _ *pb.SessionInfo, _ ...grpc.CallOption) (*pb.SessionTerminationStatus, error) {
			return &pb.SessionTerminationStatus{Terminated: true}, nil
		},
	}
	client := newClient(rpc, func() error {
		closeCalls++
		return nil
	})
	connection, err := client.Connect(context.Background(), Config{URL: "jdbc:h2:mem:test", User: "sa"})
	if err != nil {
		t.Fatalf("Connect returned error: %v", err)
	}
	updated, err := connection.ExecuteUpdate(context.Background(), "UPDATE sample SET name='hello'")
	if err != nil {
		t.Fatalf("ExecuteUpdate returned error: %v", err)
	}
	if updated != 1 {
		t.Fatalf("expected one updated row, got %d", updated)
	}
	state := connection.State()
	if state.SessionUUID != "session-1" || state.TargetServer != "localhost:1059" {
		t.Fatalf("session update was not applied: %+v", state)
	}

	result, err := connection.Query(context.Background(), "SELECT id, name FROM sample")
	if err != nil {
		t.Fatalf("Query returned error: %v", err)
	}
	if !reflect.DeepEqual(result.Columns, []string{"id", "name"}) ||
		!reflect.DeepEqual(result.Rows, [][]any{{int32(42), "hello"}}) {
		t.Fatalf("unexpected query result: %+v", result)
	}
	if state = connection.State(); state.SessionUUID != "session-2" {
		t.Fatalf("query session update was not applied: %+v", state)
	}

	if err := connection.Close(context.Background()); err != nil {
		t.Fatalf("Close returned error: %v", err)
	}
	if err := connection.Close(context.Background()); err != nil {
		t.Fatalf("second Close should be idempotent: %v", err)
	}
	if _, err := connection.Query(context.Background(), "SELECT 1"); !errors.Is(err, ErrConnectionClosed) {
		t.Fatalf("expected closed-connection error, got %v", err)
	}
	if _, err := connection.ExecuteUpdate(context.Background(), "UPDATE sample SET name='x'"); !errors.Is(err, ErrConnectionClosed) {
		t.Fatalf("expected closed-connection error, got %v", err)
	}
	if closeCalls != 0 {
		t.Fatalf("closing a connection closed the shared client %d times", closeCalls)
	}
	if err := client.Close(); err != nil {
		t.Fatalf("client Close returned error: %v", err)
	}
	if err := client.Close(); err != nil {
		t.Fatalf("second client Close returned error: %v", err)
	}
	if closeCalls != 1 {
		t.Fatalf("expected the shared channel to close once, got %d", closeCalls)
	}
}

func TestClientShouldValidateConfigAndRejectConnectAfterClose(t *testing.T) {
	rpc := &stubStatementService{}
	client := newClient(rpc, func() error { return nil })
	if _, err := client.Connect(context.Background(), Config{}); err == nil {
		t.Fatal("expected empty database URL to fail validation")
	}
	if err := client.Close(); err != nil {
		t.Fatalf("Close returned error: %v", err)
	}
	if _, err := client.Connect(context.Background(), Config{URL: "jdbc:h2:mem:test"}); !errors.Is(err, ErrClientClosed) {
		t.Fatalf("expected closed-client error, got %v", err)
	}
}

func TestNewClientShouldRejectInvalidEndpoints(t *testing.T) {
	for _, endpoint := range []string{"", "localhost", "localhost:0", "localhost:65536", "[::1]:abc"} {
		if _, err := NewClient(endpoint); err == nil {
			t.Errorf("NewClient(%q) expected an error", endpoint)
		}
	}
}

func TestProcessUUIDShouldBeStableUUIDv4(t *testing.T) {
	first, err := getProcessUUID()
	if err != nil {
		t.Fatalf("getProcessUUID returned error: %v", err)
	}
	second, err := getProcessUUID()
	if err != nil {
		t.Fatalf("second getProcessUUID returned error: %v", err)
	}
	if first != second || len(first) != 36 || first[14] != '4' || !strings.Contains(first, "-") {
		t.Fatalf("expected stable UUIDv4, got %q and %q", first, second)
	}
}

func TestDecodeValueShouldPreserveScalarAndNullValues(t *testing.T) {
	tests := []struct {
		name  string
		value *pb.ParameterValue
		want  any
	}{
		{name: "boolean false", value: &pb.ParameterValue{Value: &pb.ParameterValue_BoolValue{BoolValue: false}}, want: false},
		{name: "integer", value: &pb.ParameterValue{Value: &pb.ParameterValue_IntValue{IntValue: -2}}, want: int32(-2)},
		{name: "long", value: &pb.ParameterValue{Value: &pb.ParameterValue_LongValue{LongValue: 1 << 40}}, want: int64(1 << 40)},
		{name: "string", value: &pb.ParameterValue{Value: &pb.ParameterValue_StringValue{StringValue: "value"}}, want: "value"},
		{name: "null", value: &pb.ParameterValue{Value: &pb.ParameterValue_IsNull{IsNull: true}}, want: nil},
		{
			name: "timestamp",
			value: &pb.ParameterValue{Value: &pb.ParameterValue_TimestampValue{
				TimestampValue: &pb.TimestampWithZone{Instant: timestamppb.New(time.Date(2026, time.October, 2, 12, 30, 45, 123, time.UTC))},
			}},
			want: time.Date(2026, time.October, 2, 12, 30, 45, 123, time.UTC),
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, err := decodeValue(test.value)
			if err != nil {
				t.Fatalf("decodeValue returned error: %v", err)
			}
			if !reflect.DeepEqual(got, test.want) {
				t.Fatalf("expected %#v, got %#v", test.want, got)
			}
		})
	}
}

func TestGrpcErrorShouldDecodeSQLTrailerAndPreserveContextErrors(t *testing.T) {
	message := &pb.SqlErrorResponse{Reason: "duplicate key", SqlState: "23505", VendorCode: 23505}
	encoded, err := proto.Marshal(message)
	if err != nil {
		t.Fatalf("marshal SQL error: %v", err)
	}
	cause := status.Error(codes.Internal, "database error")
	got := grpcError(context.Background(), cause, metadata.Pairs("com.openjproxy.grpc.sqlerrorresponse-bin", string(encoded)))
	var sqlErr *SQLError
	if !errors.As(got, &sqlErr) {
		t.Fatalf("expected SQLError, got %T: %v", got, got)
	}
	if sqlErr.SQLState != "23505" || sqlErr.VendorCode != 23505 || sqlErr.Message != "duplicate key" {
		t.Fatalf("unexpected SQL error: %+v", sqlErr)
	}
	if !errors.Is(got, cause) {
		t.Fatalf("expected the underlying gRPC error to be retained: %v", got)
	}

	ctx, cancel := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
	defer cancel()
	if got := grpcError(ctx, status.Error(codes.DeadlineExceeded, "deadline"), nil); !errors.Is(got, context.DeadlineExceeded) {
		t.Fatalf("expected context deadline to be preserved, got %v", got)
	}
}

type stubStatementService struct {
	pb.StatementServiceClient
	connect   func(context.Context, *pb.ConnectionDetails, ...grpc.CallOption) (*pb.SessionInfo, error)
	update    func(context.Context, *pb.StatementRequest, ...grpc.CallOption) (*pb.OpResult, error)
	query     func(context.Context, *pb.StatementRequest, ...grpc.CallOption) (grpc.ServerStreamingClient[pb.OpResult], error)
	terminate func(context.Context, *pb.SessionInfo, ...grpc.CallOption) (*pb.SessionTerminationStatus, error)
}

func (s *stubStatementService) Connect(ctx context.Context, details *pb.ConnectionDetails, options ...grpc.CallOption) (*pb.SessionInfo, error) {
	if s.connect == nil {
		return nil, errors.New("unexpected Connect call")
	}
	return s.connect(ctx, details, options...)
}

func (s *stubStatementService) ExecuteUpdate(ctx context.Context, request *pb.StatementRequest, options ...grpc.CallOption) (*pb.OpResult, error) {
	if s.update == nil {
		return nil, errors.New("unexpected ExecuteUpdate call")
	}
	return s.update(ctx, request, options...)
}

func (s *stubStatementService) ExecuteQuery(ctx context.Context, request *pb.StatementRequest, options ...grpc.CallOption) (grpc.ServerStreamingClient[pb.OpResult], error) {
	if s.query == nil {
		return nil, errors.New("unexpected ExecuteQuery call")
	}
	return s.query(ctx, request, options...)
}

func (s *stubStatementService) TerminateSession(ctx context.Context, session *pb.SessionInfo, options ...grpc.CallOption) (*pb.SessionTerminationStatus, error) {
	if s.terminate == nil {
		return nil, errors.New("unexpected TerminateSession call")
	}
	return s.terminate(ctx, session, options...)
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
