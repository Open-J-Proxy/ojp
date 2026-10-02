package client

import "fmt"

type SQLError struct {
	SQLState   string
	VendorCode int32
	Message    string
	Cause      error
}

func (e *SQLError) Error() string {
	if e.SQLState == "" {
		return fmt.Sprintf("OJP SQL error: %s (vendor code %d)", e.Message, e.VendorCode)
	}
	return fmt.Sprintf("OJP SQL error %s: %s (vendor code %d)", e.SQLState, e.Message, e.VendorCode)
}

func (e *SQLError) Unwrap() error {
	return e.Cause
}
