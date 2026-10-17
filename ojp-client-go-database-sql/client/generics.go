package client

import (
	"errors"
	"fmt"
	"reflect"
	"time"

	pb "github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/com/openjproxy/grpc"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// driverVal constrains Go types that map to OJP parameter types.
// ~ lets named types satisfy the constraint; time.Time is a struct.
type driverVal interface {
	~bool | ~int | ~int64 | ~float32 | ~float64 | ~string | ~[]byte | time.Time
}

func encodeParam[T driverVal](value T) *pb.ParameterValue {
	return encodeParamAny(any(value))
}

func encodeParamAny(value any) *pb.ParameterValue {
	switch v := value.(type) {
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
		zone := v.Location().String()
		if zone == "" {
			zone = "UTC"
		}
		return &pb.ParameterValue{Value: &pb.ParameterValue_TimestampValue{
			TimestampValue: &pb.TimestampWithZone{Instant: ts, Timezone: zone},
		}}
	default:
		return encodeParamAny(normalizeType(v))
	}
}

// normalizeType converts named scalar types to their underlying type.
func normalizeType(v any) any {
	rv := reflect.ValueOf(v)
	switch rv.Kind() {
	case reflect.Bool:
		return rv.Bool()
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return rv.Int()
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return int64(rv.Uint())
	case reflect.Float32, reflect.Float64:
		return rv.Float()
	case reflect.String:
		return rv.String()
	default:
		return v
	}
}

func encodeParamTyped[T driverVal](index int, value T) *pb.ParameterProto {
	param := &pb.ParameterProto{Index: int32(index)}
	switch v := normalizeType(any(value)).(type) {
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
		param.Values = []*pb.ParameterValue{{Value: &pb.ParameterValue_IntValue{IntValue: 0}}}
	}
	return param
}

func decodeResult[T any](value any) (T, error) {
	var zero T
	v, ok := value.(T)
	if !ok {
		return zero, fmt.Errorf("cannot decode %T as %T", value, zero)
	}
	return v, nil
}

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

func (r *queryResult) ScanInto(dest ...any) error {
	if r.cursor >= len(r.Rows) {
		return errNoMoreRows
	}
	row := r.Rows[r.cursor]
	if len(dest) != len(row) {
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
