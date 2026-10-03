package client_test

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	ojpclient "github.com/open-j-proxy/ojp-client/client"
)

func TestH2L1Integration(t *testing.T) {
	enabled, err := integrationEnabled(os.Getenv("OJP_TEST_H2"))
	if err != nil {
		t.Fatal(err)
	}
	if !enabled {
		t.Skip("set OJP_TEST_H2=true to run the real-server H2 L1 suite")
	}

	endpoint := strings.TrimSpace(os.Getenv("OJP_TEST_H2_ADDR"))
	jdbcURL := strings.TrimSpace(os.Getenv("OJP_TEST_H2_JDBC_URL"))
	if endpoint == "" || jdbcURL == "" {
		t.Fatal("OJP_TEST_H2_ADDR and OJP_TEST_H2_JDBC_URL are required when OJP_TEST_H2=true")
	}

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

	connection, err := client.Connect(ctx, ojpclient.Config{
		URL:      jdbcURL,
		User:     os.Getenv("OJP_TEST_H2_USER"),
		Password: os.Getenv("OJP_TEST_H2_PASSWORD"),
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

	readiness, err := connection.Query(ctx, "SELECT 1")
	if err != nil {
		t.Fatalf("protocol/database readiness query failed: %v", err)
	}
	if len(readiness.Rows) != 1 || len(readiness.Rows[0]) != 1 || readiness.Rows[0][0] != int32(1) {
		t.Fatalf("unexpected readiness query result: %+v", readiness)
	}

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

	_, err = connection.ExecuteUpdate(ctx, fmt.Sprintf(
		"INSERT INTO %s (id, name) VALUES (1, 'duplicate')",
		table,
	))
	assertH2SQLError(t, err, "23505")

	_, err = connection.ExecuteUpdate(ctx, "THIS IS NOT VALID SQL")
	assertH2SQLError(t, err, "42001")

	_, err = connection.Query(ctx, "SELECT FROM "+table)
	assertH2SQLError(t, err, "42001")

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

	timeoutCtx, timeoutCancel := context.WithDeadline(ctx, time.Now().Add(-time.Second))
	defer timeoutCancel()
	if _, err := connection.Query(timeoutCtx, "SELECT 1"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected context deadline error, got %v", err)
	}

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
