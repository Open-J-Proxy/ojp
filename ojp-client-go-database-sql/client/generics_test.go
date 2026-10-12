package client

import (
	"errors"
	"testing"
	"time"

	pb "github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/com/openjproxy/grpc"
)

func TestEncodeParamShouldMapGoTypesToProtoVariants(t *testing.T) {
	t.Run("bool", func(t *testing.T) {
		v := encodeParam(true)
		if !v.GetBoolValue() {
			t.Errorf("GetBoolValue() = %v, want true", v.GetBoolValue())
		}
	})
	t.Run("int64", func(t *testing.T) {
		v := encodeParam(int64(42))
		if v.GetLongValue() != 42 {
			t.Errorf("GetLongValue() = %v, want 42", v.GetLongValue())
		}
	})
	t.Run("float64", func(t *testing.T) {
		v := encodeParam(3.14)
		if v.GetDoubleValue() != 3.14 {
			t.Errorf("GetDoubleValue() = %v, want 3.14", v.GetDoubleValue())
		}
	})
	t.Run("string", func(t *testing.T) {
		v := encodeParam("hello")
		if v.GetStringValue() != "hello" {
			t.Errorf("GetStringValue() = %v, want hello", v.GetStringValue())
		}
	})
	t.Run("bytes", func(t *testing.T) {
		v := encodeParam([]byte("data"))
		if len(v.GetBytesValue()) != 4 || string(v.GetBytesValue()) != "data" {
			t.Errorf("GetBytesValue() = %v, want data", v.GetBytesValue())
		}
	})
	t.Run("time", func(t *testing.T) {
		v := encodeParam(time.Date(2026, 10, 12, 12, 0, 0, 0, time.UTC))
		if v.GetTimestampValue() == nil || v.GetTimestampValue().GetInstant() == nil {
			t.Error("GetTimestampValue() returned nil")
		}
	})
}

func TestDecodeResultShouldConvertTypes(t *testing.T) {
	s, err := decodeResult[string]("hello")
	if err != nil {
		t.Fatalf("decodeResult[string] returned error: %v", err)
	}
	if s != "hello" {
		t.Errorf("decodeResult[string] = %q, want %q", s, "hello")
	}

	n, err := decodeResult[int64](int64(7))
	if err != nil {
		t.Fatalf("decodeResult[int64] returned error: %v", err)
	}
	if n != 7 {
		t.Errorf("decodeResult[int64] = %d, want 7", n)
	}

	if _, err := decodeResult[int64]("not an int"); err == nil {
		t.Error("decodeResult[int64] with string should fail")
	}
}

func TestQueryResultScan2ShouldExtractTypedValues(t *testing.T) {
	result := &queryResult{
		Columns: []string{"id", "name"},
		Rows: [][]any{
			{int64(1), "alice"},
			{int64(2), "bob"},
		},
	}

	var id int64
	var name string
	if err := result.Scan2(&id, &name); err != nil {
		t.Fatalf("first Scan2 returned error: %v", err)
	}
	if id != 1 || name != "alice" {
		t.Errorf("first row = (%d, %q), want (1, %q)", id, name, "alice")
	}

	if err := result.Scan2(&id, &name); err != nil {
		t.Fatalf("second Scan2 returned error: %v", err)
	}
	if id != 2 || name != "bob" {
		t.Errorf("second row = (%d, %q), want (2, %q)", id, name, "bob")
	}

	if err := result.Scan2(&id, &name); !errors.Is(err, errNoMoreRows) {
		t.Errorf("Scan2 beyond last row = %v, want errNoMoreRows", err)
	}
}

func TestQueryResultScan2ShouldRejectTypeMismatch(t *testing.T) {
	result := &queryResult{
		Columns: []string{"id"},
		Rows:    [][]any{{int64(42)}},
	}

	var s string
	if err := result.Scan2(&s, &s); err == nil {
		t.Error("Scan2 int64 into string should fail")
	}
}

func TestQueryResultScan1ShouldExtractSingleValue(t *testing.T) {
	result := &queryResult{
		Columns: []string{"id"},
		Rows:    [][]any{{int64(99)}},
	}

	var id int64
	if err := result.Scan1(&id); err != nil {
		t.Fatalf("Scan1 returned error: %v", err)
	}
	if id != 99 {
		t.Errorf("Scan1 = %d, want 99", id)
	}

	if err := result.Scan1(&id); !errors.Is(err, errNoMoreRows) {
		t.Errorf("Scan1 beyond last row = %v, want errNoMoreRows", err)
	}
}

func TestQueryResultScan3ShouldExtractThreeValues(t *testing.T) {
	result := &queryResult{
		Columns: []string{"id", "name", "score"},
		Rows:    [][]any{{int64(1), "alice", 99.5}},
	}

	var id int64
	var name string
	var score float64
	if err := result.Scan3(&id, &name, &score); err != nil {
		t.Fatalf("Scan3 returned error: %v", err)
	}
	if id != 1 || name != "alice" || score != 99.5 {
		t.Errorf("Scan3 = (%d, %q, %v), want (1, %q, %v)", id, name, score, "alice", 99.5)
	}
}

func TestBuildParameterShouldSetFields(t *testing.T) {
	value := &pb.ParameterValue{Value: &pb.ParameterValue_StringValue{StringValue: "x"}}
	p := buildParameter(3, pb.ParameterTypeProto_PT_STRING, value)
	if p.GetIndex() != 3 {
		t.Errorf("Index = %d, want 3", p.GetIndex())
	}
	if p.GetType() != pb.ParameterTypeProto_PT_STRING {
		t.Errorf("Type = %v, want PT_STRING", p.GetType())
	}
	if len(p.GetValues()) != 1 || p.GetValues()[0].GetStringValue() != "x" {
		t.Errorf("Values = %+v, want one string value 'x'", p.GetValues())
	}
}

func TestDriverValConstraintShouldAcceptNamedTypes(t *testing.T) {
	// compile-time check: named types satisfy driverVal via ~ operator
	type MyInt int64
	type MyString string
	type MyBool bool

	_ = encodeParam(MyInt(1))
	_ = encodeParam(MyString("s"))
	_ = encodeParam(MyBool(true))
}
