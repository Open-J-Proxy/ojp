package client

import (
	"fmt"
	"strings"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type SqlError struct {
	Code    string
	Message string
	Vendor  int
}

func (e *SqlError) Error() string {
	return fmt.Sprintf("SQL error: %s - %s", e.Code, e.Message)
}

func NewSqlError(sqlState, message string, vendor int) error {
	return &SqlError{
		Code:    sqlState,
		Message: message,
		Vendor:  vendor,
	}
}

type GrpcExceptionHandler struct{}

func (h GrpcExceptionHandler) Handle(err error) error {
	s, ok := status.FromError(err)
	if !ok {
		return NewSqlError("08006", err.Error(), 0)
	}
	return h.fromStatus(s)
}

func (h GrpcExceptionHandler) fromStatus(s *status.Status) error {
	code := s.Code()
	errMsg := s.Message()
	
	var sqlState string
	var vendor int
	
	switch code {
	case codes.OK:
		return nil
	case codes.NotFound:
		sqlState = "02000"
	case codes.AlreadyExists:
		sqlState = "23505"
	case codes.FailedPrecondition:
		sqlState = "23505"
	case codes.Aborted:
		sqlState = "40001"
	case codes.DeadlineExceeded:
		sqlState = "HYT00"
	case codes.Unavailable:
		sqlState = "08003"
	case codes.Unauthenticated:
		sqlState = "28000"
	case codes.PermissionDenied:
		sqlState = "42501"
	case codes.ResourceExhausted:
		sqlState = "53300"
	default:
		sqlState = "HY000"
	}
	
	return NewSqlError(sqlState, errMsg, vendor)
}

func IsConnectionLevelError(err error) bool {
	if err == nil {
		return false
	}
	s, ok := status.FromError(err)
	if !ok {
		return true
	}
	switch s.Code() {
	case codes.Unavailable,
		codes.DeadlineExceeded,
		codes.Internal,
		codes.Unauthenticated:
		return true
	default:
		return false
	}
}

func IsConnectionError(err error) bool {
	if err == nil {
		return false
	}
	errStr := strings.ToLower(err.Error())
	return strings.Contains(errStr, "connection") ||
		strings.Contains(errStr, "unavailable") ||
		strings.Contains(errStr, "timeout")
}