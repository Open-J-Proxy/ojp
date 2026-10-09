package client_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/csv"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"testing"
	"time"

	ojpclient "github.com/open-j-proxy/ojp-client-go-database-sql/client"
)

// TestH2DatabaseShouldSupportL1CRUDAndLifecycle covers the L1 capabilities
// defined in ../../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md.
func TestH2DatabaseShouldSupportL1CRUDAndLifecycle(t *testing.T) {
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

	// Use one bounded context for setup and data operations, then open the standard database/sql API.
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	dataSourceName, err := makeOjpDataSourceName(endpoint, jdbcURL, user, password)
	if err != nil {
		t.Fatalf("build OJP data source name: %v", err)
	}
	db, err := sql.Open(ojpclient.DriverName, dataSourceName)
	if err != nil {
		t.Fatalf("open OJP database: %v", err)
	}
	db.SetMaxOpenConns(1)
	databaseClosed := false
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Errorf("close H2 database: %v", err)
		}
	})

	// Confirm the database protocol is ready before creating test data.
	if err := db.PingContext(ctx); err != nil {
		t.Fatalf("protocol/database readiness check failed: %v", err)
	}
	var readiness int64
	if err := db.QueryRowContext(ctx, "SELECT 1").Scan(&readiness); err != nil {
		t.Fatalf("readiness query failed: %v", err)
	}
	if readiness != 1 {
		t.Fatalf("unexpected readiness query result: %d", readiness)
	}

	// Give this run a unique table so repeated or parallel runs do not collide.
	randomSuffix := make([]byte, 6)
	if _, err := rand.Read(randomSuffix); err != nil {
		t.Fatalf("generate isolated table name: %v", err)
	}
	table := "ojp_go_l1_" + hex.EncodeToString(randomSuffix)
	if _, err := db.ExecContext(ctx, fmt.Sprintf(
		"CREATE TABLE %s (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)",
		table,
	)); err != nil {
		t.Fatalf("create isolated table: %v", err)
	}
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cleanupCancel()
		if !databaseClosed {
			if _, err := db.ExecContext(cleanupCtx, "DROP TABLE IF EXISTS "+table); err != nil {
				t.Errorf("drop isolated table: %v", err)
			}
		}
	})

	// Exercise basic insert, read, and update behavior with exact row and count checks.
	insertResult, err := db.ExecContext(ctx, fmt.Sprintf(
		"INSERT INTO %s (id, name) VALUES (?, ?)",
		table,
	), 1, "before")
	if err != nil {
		t.Fatalf("insert row: %v", err)
	}
	insertCount, err := insertResult.RowsAffected()
	if err != nil || insertCount != 1 {
		t.Fatalf("expected insert count 1, got %d (error %v)", insertCount, err)
	}
	assertH2Row(t, ctx, db, fmt.Sprintf("SELECT id, name FROM %s WHERE id=?", table), 1, "before")

	updateResult, err := db.ExecContext(ctx, fmt.Sprintf(
		"UPDATE %s SET name=? WHERE id=?",
		table,
	), "after", 1)
	if err != nil {
		t.Fatalf("update row: %v", err)
	}
	updateCount, err := updateResult.RowsAffected()
	if err != nil || updateCount != 1 {
		t.Fatalf("expected update count 1, got %d (error %v)", updateCount, err)
	}
	assertH2Row(t, ctx, db, fmt.Sprintf("SELECT id, name FROM %s WHERE id=?", table), 1, "after")

	// Verify constraint violations and malformed SQL preserve their database SQL errors.
	_, err = db.ExecContext(ctx, fmt.Sprintf(
		"INSERT INTO %s (id, name) VALUES (1, 'duplicate')",
		table,
	))
	assertH2SQLError(t, err, "23505")

	_, err = db.ExecContext(ctx, "THIS IS NOT VALID SQL")
	assertH2SQLError(t, err, "42001")

	_, err = db.QueryContext(ctx, "SELECT FROM "+table)
	assertH2SQLError(t, err, "42001")

	// Delete the row and verify an empty query still reports its result columns.
	deleteResult, err := db.ExecContext(ctx, fmt.Sprintf("DELETE FROM %s WHERE id=1", table))
	if err != nil {
		t.Fatalf("delete row: %v", err)
	}
	deleteCount, err := deleteResult.RowsAffected()
	if err != nil || deleteCount != 1 {
		t.Fatalf("expected delete count 1, got %d (error %v)", deleteCount, err)
	}
	empty, err := db.QueryContext(ctx, fmt.Sprintf("SELECT id, name FROM %s WHERE id=1", table))
	if err != nil {
		t.Fatalf("query after delete: %v", err)
	}
	columns, err := empty.Columns()
	if err != nil {
		t.Fatalf("read empty-result columns: %v", err)
	}
	if empty.Next() || len(columns) != 2 || empty.Err() != nil {
		t.Fatalf("expected a two-column empty result, got columns %v and error %v", columns, empty.Err())
	}
	if err := empty.Close(); err != nil {
		t.Fatalf("close empty result: %v", err)
	}

	// Ensure deadlines are propagated instead of being converted to generic gRPC errors.
	timeoutCtx, timeoutCancel := context.WithDeadline(ctx, time.Now().Add(-time.Second))
	defer timeoutCancel()
	if err := db.QueryRowContext(timeoutCtx, "SELECT 1").Scan(&readiness); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected context deadline error, got %v", err)
	}

	// Drop the table, close the database, and verify the closed handle rejects later work.
	if _, err := db.ExecContext(ctx, "DROP TABLE IF EXISTS "+table); err != nil {
		t.Fatalf("drop isolated table: %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatalf("close H2 database: %v", err)
	}
	databaseClosed = true
	if err := db.QueryRowContext(ctx, "SELECT 1").Scan(&readiness); err == nil {
		t.Fatalf("expected closed-session rejection, got %v", err)
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

func assertH2Row(t *testing.T, ctx context.Context, db *sql.DB, query string, id int64, name string) {
	t.Helper()
	var gotID int64
	var gotName string
	if err := db.QueryRowContext(ctx, query, id).Scan(&gotID, &gotName); err != nil {
		t.Fatalf("query row: %v", err)
	}
	if gotID != id || gotName != name {
		t.Fatalf("expected (%d, %q), got (%d, %q)", id, name, gotID, gotName)
	}
}

func makeOjpDataSourceName(endpoint, jdbcURL, user, password string) (string, error) {
	var dataSourceName bytes.Buffer
	writer := csv.NewWriter(&dataSourceName)
	if err := writer.Write([]string{"jdbc:ojp[" + endpoint + "]_" + jdbcURL, user, password}); err != nil {
		return "", err
	}
	writer.Flush()
	if err := writer.Error(); err != nil {
		return "", err
	}
	return strings.TrimSpace(dataSourceName.String()), nil
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
