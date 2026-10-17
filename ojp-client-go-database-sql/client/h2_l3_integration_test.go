package client_test

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	ojpclient "github.com/open-j-proxy/ojp-client-go-database-sql/client"
)

func TestH2DatabaseShouldSupportL3Capabilities(t *testing.T) {
	enabled, err := integrationEnabled(os.Getenv("OJP_TEST_H2"))
	if err != nil {
		t.Fatal(err)
	}
	if !enabled {
		t.Skip("set OJP_TEST_H2=true to run the real-server H2 L3 suite")
	}

	endpoint := strings.TrimSpace(os.Getenv("OJP_TEST_H2_ADDR"))
	jdbcURL, user, password, err := readH2ConnectionConfig()
	if err != nil {
		t.Fatalf("read H2 connection CSV: %v", err)
	}
	if endpoint == "" {
		t.Fatal("OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true")
	}

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

	if err := db.PingContext(ctx); err != nil {
		t.Fatalf("protocol/database readiness check failed: %v", err)
	}

	table := fmt.Sprintf("ojp_go_l3_%d", time.Now().UnixNano())
	if _, err := db.ExecContext(ctx, fmt.Sprintf(
		"CREATE TABLE %s (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL)",
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

	if _, err := db.ExecContext(ctx, fmt.Sprintf(
		"INSERT INTO %s (id, name) SELECT X, CONCAT('row-', X) FROM SYSTEM_RANGE(1, 250)",
		table,
	)); err != nil {
		t.Fatalf("insert pagination rows: %v", err)
	}

	rows, err := db.QueryContext(ctx, fmt.Sprintf(
		"SELECT id, name FROM %s ORDER BY id",
		table,
	))
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	defer rows.Close()

	cols, err := rows.Columns()
	if err != nil {
		t.Fatalf("columns: %v", err)
	}
	if len(cols) != 2 {
		t.Fatalf("expected 2 columns, got %d: %v", len(cols), cols)
	}

	count := 0
	for rows.Next() {
		var id int64
		var name string
		if err := rows.Scan(&id, &name); err != nil {
			t.Fatalf("scan row %d: %v", count, err)
		}
		if id != int64(count+1) || name != fmt.Sprintf("row-%d", count+1) {
			t.Fatalf("unexpected row %d: id=%d name=%q", count, id, name)
		}
		count++
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("rows error: %v", err)
	}
	if count != 250 {
		t.Fatalf("expected 250 rows spanning multiple server result blocks, got %d", count)
	}
	if err := rows.Close(); err != nil {
		t.Fatalf("close rows: %v", err)
	}

	empty, err := db.QueryContext(ctx, fmt.Sprintf(
		"SELECT id, name FROM %s WHERE id > 1000",
		table,
	))
	if err != nil {
		t.Fatalf("empty query: %v", err)
	}
	emptyCols, err := empty.Columns()
	if err != nil {
		t.Fatalf("empty columns: %v", err)
	}
	if len(emptyCols) != 2 {
		t.Fatalf("expected 2 empty columns, got %d: %v", len(emptyCols), emptyCols)
	}
	if empty.Next() {
		t.Fatal("expected no rows in empty result")
	}
	if err := empty.Close(); err != nil {
		t.Fatalf("close empty rows: %v", err)
	}

	partial, err := db.QueryContext(ctx, fmt.Sprintf("SELECT id FROM %s ORDER BY id", table))
	if err != nil {
		t.Fatalf("partial query: %v", err)
	}
	if !partial.Next() {
		t.Fatal("expected a row before early result close")
	}
	if err := partial.Close(); err != nil {
		t.Fatalf("close partial result: %v", err)
	}
	var remaining int64
	if err := db.QueryRowContext(ctx, fmt.Sprintf("SELECT COUNT(*) FROM %s", table)).Scan(&remaining); err != nil {
		t.Fatalf("query after early result close: %v", err)
	}
	if remaining != 250 {
		t.Fatalf("expected 250 rows after closing the partial result, got %d", remaining)
	}

	if _, err := db.ExecContext(ctx, "DROP TABLE IF EXISTS "+table); err != nil {
		t.Fatalf("drop isolated table: %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatalf("close H2 database: %v", err)
	}
	databaseClosed = true
}
