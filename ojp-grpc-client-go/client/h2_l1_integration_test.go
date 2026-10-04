package client_test

import (
	"context"
	"crypto/rand"
	"encoding/csv"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"testing"
	"time"

	ojpclient "github.com/open-j-proxy/ojp-client/client"
)

// TestH2ConnectionShouldSupportL1CRUDAndLifecycle covers the L1 capabilities
// defined in ../../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md.
func TestH2ConnectionShouldSupportL1CRUDAndLifecycle(t *testing.T) {
	// Keep the real-server suite opt-in so ordinary Go unit tests need no database.
	enabled, err := integrationEnabled(os.Getenv("OJP_TEST_H2"))
	if err != nil {
		t.Fatal(err)
	}
	if !enabled {
		t.Skip("set OJP_TEST_H2=true to run the real-server H2 L1 suite")
	}

	// Read backend connection details from the same kind of CSV fixture used by JDBC integration tests.
	endpoint := strings.TrimSpace(os.Getenv("OJP_TEST_H2_ADDR"))
	jdbcURL, user, password, err := readH2ConnectionConfig()
	if err != nil {
		t.Fatalf("read H2 connection CSV: %v", err)
	}
	if endpoint == "" {
		t.Fatal("OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true")
	}

	// Use one bounded context for setup and data operations, then create the shared client.
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	client, err := ojpclient.NewClient(endpoint)
	if err != nil {
		t.Fatalf("create OJP client: %v", err)
	}
	t.Cleanup(func() {
		if err := client.Close(); err != nil {
			t.Errorf("close OJP client: %v", err)
		}
	})

	// Connect through OJP and verify the server returned process-level session state.
	connection, err := client.Connect(ctx, ojpclient.Config{
		URL:      jdbcURL,
		User:     user,
		Password: password,
	})
	if err != nil {
		t.Fatalf("connect to H2 through OJP: %v", err)
	}
	t.Cleanup(func() {
		closeCtx, closeCancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer closeCancel()
		if err := connection.Close(closeCtx); err != nil {
			t.Errorf("close H2 session: %v", err)
		}
	})
	if connection.State().ClientUUID == "" {
		t.Fatal("connect returned no process client UUID")
	}

	// Confirm the database protocol is ready before creating test data.
	readiness, err := connection.Query(ctx, "SELECT 1")
	if err != nil {
		t.Fatalf("protocol/database readiness query failed: %v", err)
	}
	if len(readiness.Rows) != 1 || len(readiness.Rows[0]) != 1 || readiness.Rows[0][0] != int32(1) {
		t.Fatalf("unexpected readiness query result: %+v", readiness)
	}

	// Give this run a unique table so repeated or parallel runs do not collide.
	randomSuffix := make([]byte, 6)
	if _, err := rand.Read(randomSuffix); err != nil {
		t.Fatalf("generate isolated table name: %v", err)
	}
	table := "ojp_go_l1_" + hex.EncodeToString(randomSuffix)
	if _, err := connection.ExecuteUpdate(ctx, fmt.Sprintf(
		"CREATE TABLE %s (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)",
		table,
	)); err != nil {
		t.Fatalf("create isolated table: %v", err)
	}
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cleanupCancel()
		if _, err := connection.ExecuteUpdate(cleanupCtx, "DROP TABLE IF EXISTS "+table); err != nil &&
			!errors.Is(err, ojpclient.ErrConnectionClosed) {
			t.Errorf("drop isolated table: %v", err)
		}
	})

	// Exercise basic insert, read, and update behavior with exact row and count checks.
	insertCount, err := connection.ExecuteUpdate(ctx, fmt.Sprintf(
		"INSERT INTO %s (id, name) VALUES (1, 'before')",
		table,
	))
	if err != nil {
		t.Fatalf("insert row: %v", err)
	}
	if insertCount != 1 {
		t.Fatalf("expected insert count 1, got %d", insertCount)
	}
	assertH2Row(t, ctx, connection, fmt.Sprintf("SELECT id, name FROM %s WHERE id=1", table), int32(1), "before")

	updateCount, err := connection.ExecuteUpdate(ctx, fmt.Sprintf(
		"UPDATE %s SET name='after' WHERE id=1",
		table,
	))
	if err != nil {
		t.Fatalf("update row: %v", err)
	}
	if updateCount != 1 {
		t.Fatalf("expected update count 1, got %d", updateCount)
	}
	assertH2Row(t, ctx, connection, fmt.Sprintf("SELECT id, name FROM %s WHERE id=1", table), int32(1), "after")

	// Verify constraint violations and malformed SQL preserve their database SQL errors.
	_, err = connection.ExecuteUpdate(ctx, fmt.Sprintf(
		"INSERT INTO %s (id, name) VALUES (1, 'duplicate')",
		table,
	))
	assertH2SQLError(t, err, "23505")

	_, err = connection.ExecuteUpdate(ctx, "THIS IS NOT VALID SQL")
	assertH2SQLError(t, err, "42001")

	_, err = connection.Query(ctx, "SELECT FROM "+table)
	assertH2SQLError(t, err, "42001")

	// Delete the row and verify an empty query still reports its result columns.
	deleteCount, err := connection.ExecuteUpdate(ctx, fmt.Sprintf("DELETE FROM %s WHERE id=1", table))
	if err != nil {
		t.Fatalf("delete row: %v", err)
	}
	if deleteCount != 1 {
		t.Fatalf("expected delete count 1, got %d", deleteCount)
	}
	empty, err := connection.Query(ctx, fmt.Sprintf("SELECT id, name FROM %s WHERE id=1", table))
	if err != nil {
		t.Fatalf("query after delete: %v", err)
	}
	if len(empty.Rows) != 0 || len(empty.Columns) != 2 {
		t.Fatalf("expected a two-column empty result, got %+v", empty)
	}

	// Ensure deadlines are propagated instead of being converted to generic gRPC errors.
	timeoutCtx, timeoutCancel := context.WithDeadline(ctx, time.Now().Add(-time.Second))
	defer timeoutCancel()
	if _, err := connection.Query(timeoutCtx, "SELECT 1"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected context deadline error, got %v", err)
	}

	// Drop the table and confirm session termination is idempotent and rejects later use.
	if _, err := connection.ExecuteUpdate(ctx, "DROP TABLE IF EXISTS "+table); err != nil {
		t.Fatalf("drop isolated table: %v", err)
	}
	closeCtx, closeCancel := context.WithTimeout(context.Background(), 10*time.Second)
	if err := connection.Close(closeCtx); err != nil {
		closeCancel()
		t.Fatalf("terminate H2 session: %v", err)
	}
	closeCancel()
	if err := connection.Close(context.Background()); err != nil {
		t.Fatalf("second close should be idempotent: %v", err)
	}
	if _, err := connection.Query(ctx, "SELECT 1"); !errors.Is(err, ojpclient.ErrConnectionClosed) {
		t.Fatalf("expected closed-session rejection, got %v", err)
	}
	if _, err := connection.ExecuteUpdate(ctx, "DELETE FROM "+table); !errors.Is(err, ojpclient.ErrConnectionClosed) {
		t.Fatalf("expected closed-session update rejection, got %v", err)
	}
}

func readH2ConnectionConfig() (string, string, string, error) {
	file, err := os.Open("testdata/h2_l1_connection.csv")
	if err != nil {
		return "", "", "", err
	}
	defer file.Close()

	reader := csv.NewReader(file)
	record, err := reader.Read()
	if err != nil {
		return "", "", "", err
	}
	if len(record) != 3 || strings.TrimSpace(record[0]) == "" {
		return "", "", "", fmt.Errorf("expected CSV fields: JDBC URL, username, password")
	}
	if _, err := reader.Read(); !errors.Is(err, io.EOF) {
		if err != nil {
			return "", "", "", err
		}
		return "", "", "", fmt.Errorf("expected exactly one H2 connection record")
	}
	return strings.TrimSpace(record[0]), strings.TrimSpace(record[1]), record[2], nil
}

func TestReadH2ConnectionConfigShouldLoadCsvRecord(t *testing.T) {
	jdbcURL, user, password, err := readH2ConnectionConfig()
	if err != nil {
		t.Fatalf("read H2 connection CSV: %v", err)
	}
	if jdbcURL != "jdbc:h2:mem:ojp_go_h2_l1;DB_CLOSE_DELAY=-1" || user != "sa" || password != "" {
		t.Fatalf("unexpected H2 connection configuration: URL=%q user=%q", jdbcURL, user)
	}
}

func assertH2Row(t *testing.T, ctx context.Context, connection *ojpclient.Connection, sql string, id int32, name string) {
	t.Helper()
	result, err := connection.Query(ctx, sql)
	if err != nil {
		t.Fatalf("query row: %v", err)
	}
	if len(result.Columns) != 2 || len(result.Rows) != 1 ||
		len(result.Rows[0]) != 2 || result.Rows[0][0] != id || result.Rows[0][1] != name {
		t.Fatalf("unexpected row result: %+v", result)
	}
}

func assertH2SQLError(t *testing.T, err error, expectedSQLState string) {
	t.Helper()
	var sqlErr *ojpclient.SQLError
	if !errors.As(err, &sqlErr) {
		t.Fatalf("expected SQL error with trailer, got %T: %v", err, err)
	}
	if sqlErr.SQLState != expectedSQLState || sqlErr.Message == "" {
		t.Fatalf("expected SQLSTATE %s and message, got %+v", expectedSQLState, sqlErr)
	}
	if sqlErr.VendorCode == 0 {
		t.Fatalf("expected database vendor code, got %+v", sqlErr)
	}
}

func integrationEnabled(value string) (bool, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "":
		return false, nil
	case "true", "1", "yes":
		return true, nil
	case "false", "0", "no":
		return false, nil
	default:
		return false, fmt.Errorf("OJP_TEST_H2 must be true or false, got %q", value)
	}
}
