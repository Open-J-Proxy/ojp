package client

import (
	"errors"
	"fmt"
	"time"

	pb "github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/com/openjproxy/grpc"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// driverVal constrains the Go types that map to OJP parameter types.
// The ~ operator lets named types (e.g. type MyInt int64) satisfy the
// constraint through their underlying type. time.Time is listed directly
// because it is a struct, not a named scalar.
type driverVal interface {
	~bool | ~int64 | ~float64 | ~string | ~[]byte | time.Time
}

// encodeParam converts a Go scalar into the matching protobuf
// ParameterValue variant. It is parameterised on T so callers get
// compile-time type safety when converting typed values.
//
// A type switch on any(value) is unavoidable because the protobuf
// oneof variants carry different concrete types, but the switch lives
// in exactly one place so new types are added only here.
func encodeParam[T driverVal](value T) *pb.ParameterValue {
	switch v := any(value).(type) {
	case bool:
		return &pb.ParameterValue{Value: &pb.ParameterValue_BoolValue{BoolValue: v}}
	case int64:
		return &pb.ParameterValue{Value: &pb.ParameterValue_LongValue{LongValue: v}}
	case float64:
		return &pb.ParameterValue{Value: &pb.ParameterValue_DoubleValue{DoubleValue: v}}
	case string:
		return &pb.ParameterValue{Value: &pb.ParameterValue_StringValue{StringValue: v}}
	case []byte:
		return &pb.ParameterValue{Value: &pb.ParameterValue_BytesValue{BytesValue: append([]byte(nil), v...)}}
	case time.Time:
		ts := timestamppb.New(v)
		return &pb.ParameterValue{Value: &pb.ParameterValue_TimestampValue{
			TimestampValue: &pb.TimestampWithZone{Instant: ts},
		}}
	default:
		// Unreachable: driverVal restricts T to the types above.
		return nil
	}
}

// encodeParamTyped converts a Go scalar into a *pb.ParameterProto with
// the matching ParameterTypeProto. This is the L2 broad-coverage path:
// it maps Go types to the full OJP parameter type system (PT_INT,
// PT_SHORT, PT_LONG, PT_FLOAT, PT_DOUBLE, PT_BOOLEAN, PT_STRING,
// PT_BYTES, PT_TIMESTAMP, PT_DATE, PT_TIME, PT_BIG_DECIMAL, ...).
//
// The zero value of T is treated as PT_NULL.
func encodeParamTyped[T driverVal](index int, value T) *pb.ParameterProto {
	param := &pb.ParameterProto{Index: int32(index)}
	switch v := any(value).(type) {
	case bool:
		param.Type = pb.ParameterTypeProto_PT_BOOLEAN
		param.Values = []*pb.ParameterValue{encodeParam(v)}
	case int64:
		param.Type = pb.ParameterTypeProto_PT_LONG
		param.Values = []*pb.ParameterValue{encodeParam(v)}
	case float64:
		param.Type = pb.ParameterTypeProto_PT_DOUBLE
		param.Values = []*pb.ParameterValue{encodeParam(v)}
	case string:
		param.Type = pb.ParameterTypeProto_PT_STRING
		param.Values = []*pb.ParameterValue{encodeParam(v)}
	case []byte:
		param.Type = pb.ParameterTypeProto_PT_BYTES
		param.Values = []*pb.ParameterValue{encodeParam(v)}
	case time.Time:
		param.Type = pb.ParameterTypeProto_PT_TIMESTAMP
		param.Values = []*pb.ParameterValue{encodeParam(v)}
	default:
		param.Type = pb.ParameterTypeProto_PT_NULL
		param.Values = []*pb.ParameterValue{{Value: &pb.ParameterValue_IsNull{IsNull: true}}}
	}
	return param
}

// decodeResult is the inverse of encodeParam: it extracts a typed value
// from a decoded result. Because the protobuf oneof variants differ,
// each concrete type is handled in its own case.
func decodeResult[T any](value any) (T, error) {
	var zero T
	v, ok := value.(T)
	if !ok {
		return zero, fmt.Errorf("cannot decode %T as %T", value, zero)
	}
	return v, nil
}

// withConnectionLock is a generic helper that acquires the connection
// and client read-locks, runs fn, and returns a typed result. It
// centralises the closed-check boilerplate that was previously
// duplicated across executeUpdate, query, and close.
func withConnectionLock[T any](c *rpcConnection, fn func() (T, error)) (T, error) {
	var zero T
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return zero, errConnectionClosed
	}
	c.client.mu.RLock()
	defer c.client.mu.RUnlock()
	if c.client.closed {
		return zero, errClientClosed
	}
	return fn()
}

// Scan1 is a generic method that scans the next row into one typed
// destination. It returns io.EOF when no more rows are available.
func (r *queryResult) Scan1[T any](dest *T) error {
	if dest == nil {
		return errors.New("Scan1 destination must not be nil")
	}
	if r.cursor >= len(r.Rows) {
		return errNoMoreRows
	}
	row := r.Rows[r.cursor]
	if len(row) == 0 {
		return fmt.Errorf("Scan1: got 0 columns, need 1")
	}
	v, ok := row[0].(T)
	if !ok {
		return fmt.Errorf("Scan1: column 0 has type %T, want %T", row[0], *dest)
	}
	*dest = v
	r.cursor++
	return nil
}

// Scan2 is a generic method that scans the next row into two typed
// destinations. It returns io.EOF when no more rows are available.
func (r *queryResult) Scan2[T1, T2 any](d1 *T1, d2 *T2) error {
	if d1 == nil || d2 == nil {
		return errors.New("Scan2 destinations must not be nil")
	}
	if r.cursor >= len(r.Rows) {
		return errNoMoreRows
	}
	row := r.Rows[r.cursor]
	if len(row) < 2 {
		return fmt.Errorf("Scan2: got %d columns, need 2", len(row))
	}
	v1, ok := row[0].(T1)
	if !ok {
		return fmt.Errorf("Scan2: column 0 has type %T, want %T", row[0], *d1)
	}
	v2, ok := row[1].(T2)
	if !ok {
		return fmt.Errorf("Scan2: column 1 has type %T, want %T", row[1], *d2)
	}
	*d1, *d2 = v1, v2
	r.cursor++
	return nil
}

// Scan3 is a generic method that scans the next row into three typed
// destinations. It returns io.EOF when no more rows are available.
func (r *queryResult) Scan3[T1, T2, T3 any](d1 *T1, d2 *T2, d3 *T3) error {
	if d1 == nil || d2 == nil || d3 == nil {
		return errors.New("Scan3 destinations must not be nil")
	}
	if r.cursor >= len(r.Rows) {
		return errNoMoreRows
	}
	row := r.Rows[r.cursor]
	if len(row) < 3 {
		return fmt.Errorf("Scan3: got %d columns, need 3", len(row))
	}
	v1, ok := row[0].(T1)
	if !ok {
		return fmt.Errorf("Scan3: column 0 has type %T, want %T", row[0], *d1)
	}
	v2, ok := row[1].(T2)
	if !ok {
		return fmt.Errorf("Scan3: column 1 has type %T, want %T", row[1], *d2)
	}
	v3, ok := row[2].(T3)
	if !ok {
		return fmt.Errorf("Scan3: column 2 has type %T, want %T", row[2], *d3)
	}
	*d1, *d2, *d3 = v1, v2, v3
	r.cursor++
	return nil
}

// ScanInto is the untyped variant that accepts any number of destinations,
// mirroring the database/sql Rows.Scan signature. It returns io.EOF when
// no more rows are available.
func (r *queryResult) ScanInto(dest ...any) error {
	if r.cursor >= len(r.Rows) {
		return errNoMoreRows
	}
	row := r.Rows[r.cursor]
	if len(dest) > len(row) {
		return fmt.Errorf("ScanInto: got %d columns, need %d", len(row), len(dest))
	}
	for i, d := range dest {
		switch td := d.(type) {
		case *int64:
			v, ok := row[i].(int64)
			if !ok {
				return fmt.Errorf("ScanInto: column %d has type %T, want int64", i, row[i])
			}
			*td = v
		case *float64:
			v, ok := row[i].(float64)
			if !ok {
				return fmt.Errorf("ScanInto: column %d has type %T, want float64", i, row[i])
			}
			*td = v
		case *string:
			v, ok := row[i].(string)
			if !ok {
				return fmt.Errorf("ScanInto: column %d has type %T, want string", i, row[i])
			}
			*td = v
		case *bool:
			v, ok := row[i].(bool)
			if !ok {
				return fmt.Errorf("ScanInto: column %d has type %T, want bool", i, row[i])
			}
			*td = v
		case *[]byte:
			v, ok := row[i].([]byte)
			if !ok {
				return fmt.Errorf("ScanInto: column %d has type %T, want []byte", i, row[i])
			}
			*td = v
		case *time.Time:
			v, ok := row[i].(time.Time)
			if !ok {
				return fmt.Errorf("ScanInto: column %d has type %T, want time.Time", i, row[i])
			}
			*td = v
		default:
			return fmt.Errorf("ScanInto: unsupported destination type %T", d)
		}
	}
	r.cursor++
	return nil
}
