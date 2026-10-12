package client

import (
	"errors"
	"fmt"
	"math/big"
	"time"

	pb "github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/com/openjproxy/grpc"
)

// decodeValue converts a protobuf ParameterValue into the matching Go
// type. It covers the full OJP parameter type system:
//
//	PT_BOOLEAN       → bool
//	PT_INT           → int32
//	PT_LONG          → int64
//	PT_FLOAT         → float32
//	PT_DOUBLE        → float64
//	PT_STRING        → string
//	PT_BYTES         → []byte
//	PT_TIMESTAMP     → time.Time
//	PT_DATE          → time.Time (date component, UTC midnight)
//	PT_TIME          → time.Time (time component, Jan 1 year 0)
//	PT_INT_ARRAY     → []int32
//	PT_LONG_ARRAY    → []int64
//	PT_STRING_ARRAY  → []string
//	PT_URL           → string
//	PT_ROW_ID        → string
//	PT_UUID          → string
//	PT_BIG_INTEGER   → *big.Int
//	PT_NULL          → nil
func decodeValue(value *pb.ParameterValue) (any, error) {
	if value == nil {
		return nil, nil
	}
	switch typed := value.GetValue().(type) {
	case *pb.ParameterValue_BoolValue:
		return typed.BoolValue, nil
	case *pb.ParameterValue_IntValue:
		return typed.IntValue, nil
	case *pb.ParameterValue_LongValue:
		return typed.LongValue, nil
	case *pb.ParameterValue_FloatValue:
		return typed.FloatValue, nil
	case *pb.ParameterValue_DoubleValue:
		return typed.DoubleValue, nil
	case *pb.ParameterValue_StringValue:
		return typed.StringValue, nil
	case *pb.ParameterValue_BytesValue:
		return append([]byte(nil), typed.BytesValue...), nil
	case *pb.ParameterValue_IntArrayValue:
		if typed.IntArrayValue == nil {
			return []int32(nil), nil
		}
		return append([]int32(nil), typed.IntArrayValue.GetValues()...), nil
	case *pb.ParameterValue_LongArrayValue:
		if typed.LongArrayValue == nil {
			return []int64(nil), nil
		}
		return append([]int64(nil), typed.LongArrayValue.GetValues()...), nil
	case *pb.ParameterValue_IsNull:
		if typed.IsNull {
			return nil, nil
		}
		return nil, errors.New("invalid false SQL null marker")
	case *pb.ParameterValue_TimestampValue:
		if typed.TimestampValue == nil || typed.TimestampValue.GetInstant() == nil {
			return nil, errors.New("timestamp result has no instant")
		}
		return typed.TimestampValue.GetInstant().AsTime(), nil
	case *pb.ParameterValue_DateValue:
		if typed.DateValue == nil {
			return nil, errors.New("date result is empty")
		}
		date := typed.DateValue
		return time.Date(int(date.GetYear()), time.Month(date.GetMonth()), int(date.GetDay()), 0, 0, 0, 0, time.UTC), nil
	case *pb.ParameterValue_TimeValue:
		if typed.TimeValue == nil {
			return nil, errors.New("time result is empty")
		}
		value := typed.TimeValue
		return time.Date(0, time.January, 1, int(value.GetHours()), int(value.GetMinutes()), int(value.GetSeconds()), int(value.GetNanos()), time.UTC), nil
	case *pb.ParameterValue_UrlValue:
		if typed.UrlValue == nil {
			return nil, nil
		}
		return typed.UrlValue.GetValue(), nil
	case *pb.ParameterValue_RowidValue:
		if typed.RowidValue == nil {
			return nil, nil
		}
		return typed.RowidValue.GetValue(), nil
	case *pb.ParameterValue_UuidValue:
		if typed.UuidValue == nil {
			return nil, nil
		}
		return typed.UuidValue.GetValue(), nil
	case *pb.ParameterValue_BigintegerValue:
		if typed.BigintegerValue == nil {
			return nil, nil
		}
		return new(big.Int).SetBytes([]byte(typed.BigintegerValue.GetValue())), nil
	case *pb.ParameterValue_StringArrayValue:
		if typed.StringArrayValue == nil {
			return []string(nil), nil
		}
		return append([]string(nil), typed.StringArrayValue.GetValues()...), nil
	case nil:
		return nil, nil
	default:
		return nil, fmt.Errorf("unsupported result value %T", typed)
	}
}

// decodeValueTo decodes a protobuf ParameterValue into a specific Go type.
// It is the typed counterpart to decodeValue and is used by the L2
// metadata and generated-keys paths where the caller knows the target
// type.
func decodeValueTo[T any](value *pb.ParameterValue) (T, error) {
	var zero T
	decoded, err := decodeValue(value)
	if err != nil {
		return zero, err
	}
	v, ok := decoded.(T)
	if !ok {
		return zero, fmt.Errorf("cannot decode %T as %T", decoded, zero)
	}
	return v, nil
}
