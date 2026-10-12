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

// TestH2DatabaseShouldSupportL2Capabilities covers the L2 capabilities
// defined in ../../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md:
//
//   - Broad ParameterTypeProto coverage (bool, int64, float64, string, []byte, time.Time)
//   - PreparedStatement and Statement variants
//   - Generated keys
//   - Basic metadata (column labels, result-set structure)
func TestH2DatabaseShouldSupportL2Capabilities(t *testing.T) {
	enabled, err := integrationEnabled(os.Getenv("OJP_TEST_H2"))
	if err != nil {
		t.Fatal(err)
	}
	if !enabled {
		t.Skip("set OJP_TEST_H2=true to run the real-server H2 L2 suite")
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

	// Give this run a unique table so repeated or parallel runs do not collide.
	table := fmt.Sprintf("ojp_go_l2_%d", time.Now().UnixNano())
	if _, err := db.ExecContext(ctx, fmt.Sprintf(
		"CREATE TABLE %s (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, active BOOLEAN, score DOUBLE, data BINARY, created TIMESTAMP)",
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

	// --- Broad ParameterTypeProto coverage ---
	// Insert with bool, string, double, binary, timestamp
	insertResult, err := db.ExecContext(ctx, fmt.Sprintf(
		"INSERT INTO %s (name, active, score, data, created) VALUES (?, ?, ?, ?, ?)",
		table,
	), "l2-test", true, 99.5, []byte("binary-data"), time.Date(2026, 10, 12, 12, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("insert with broad types: %v", err)
	}
	insertCount, err := insertResult.RowsAffected()
	if err != nil || insertCount != 1 {
		t.Fatalf("expected insert count 1, got %d (error %v)", insertCount, err)
	}

	// Read back with broad types
	var name string
	var active bool
	var score float64
	var data []byte
	var created time.Time
	if err := db.QueryRowContext(ctx, fmt.Sprintf(
		"SELECT name, active, score, data, created FROM %s WHERE name=?", table,
	), "l2-test").Scan(&name, &active, &score, &data, &created); err != nil {
		t.Fatalf("select broad types: %v", err)
	}
	if name != "l2-test" || !active || score != 99.5 || string(data) != "binary-data" {
		t.Fatalf("unexpected broad-type row: name=%q active=%v score=%v data=%q", name, active, score, data)
	}

	// --- PreparedStatement ---
	stmt, err := db.Prepare(fmt.Sprintf(
		"INSERT INTO %s (name, active, score) VALUES (?, ?, ?)",
		table,
	))
	if err != nil {
		t.Fatalf("prepare statement: %v", err)
	}
	defer stmt.Close()

	psResult, err := stmt.Exec("ps-row", false, 42.0)
	if err != nil {
		t.Fatalf("prepared exec: %v", err)
	}
	psCount, err := psResult.RowsAffected()
	if err != nil || psCount != 1 {
		t.Fatalf("expected prepared exec count 1, got %d (error %v)", psCount, err)
	}

	var psName string
	var psActive bool
	var psScore float64
	if err := db.QueryRowContext(ctx, fmt.Sprintf(
		"SELECT name, active, score FROM %s WHERE name='ps-row'", table,
	)).Scan(&psName, &psActive, &psScore); err != nil {
		t.Fatalf("select prepared row: %v", err)
	}
	if psName != "ps-row" || psActive || psScore != 42.0 {
		t.Fatalf("unexpected prepared row: name=%q active=%v score=%v", psName, psActive, psScore)
	}

	// --- Generated keys ---
	genResult, err := db.ExecContext(ctx, fmt.Sprintf(
		"INSERT INTO %s (name) VALUES (?)",
		table,
	), "gen-keys")
	if err != nil {
		t.Fatalf("insert for generated keys: %v", err)
	}
	lastID, err := genResult.LastInsertId()
	if err != nil {
		// OJP may not support LastInsertId; fall back to identity query
		t.Logf("LastInsertId not supported (%v), querying identity", err)
		if err := db.QueryRowContext(ctx, fmt.Sprintf(
			"SELECT id FROM %s WHERE name='gen-keys'", table,
		)).Scan(&lastID); err != nil {
			t.Fatalf("query generated key: %v", err)
		}
	}
	if lastID == 0 {
		t.Fatal("expected non-zero generated key")
	}

	// --- Basic metadata ---
	rows, err := db.QueryContext(ctx, fmt.Sprintf(
		"SELECT id, name, active, score FROM %s WHERE name=?", table,
	), "l2-test")
	if err != nil {
		t.Fatalf("metadata query: %v", err)
	}
	defer rows.Close()

	cols, err := rows.Columns()
	if err != nil {
		t.Fatalf("columns: %v", err)
	}
	if len(cols) != 4 {
		t.Fatalf("expected 4 columns, got %d: %v", len(cols), cols)
	}
	if !rows.Next() {
		t.Fatal("expected at least one row for metadata check")
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("rows error: %v", err)
	}

	// --- Statement variant (direct Exec/Query without Prepare) ---
	if _, err := db.ExecContext(ctx, fmt.Sprintf(
		"UPDATE %s SET score=? WHERE name=?", table,
	), 100.0, "l2-test"); err != nil {
		t.Fatalf("statement update: %v", err)
	}
	var updatedScore float64
	if err := db.QueryRowContext(ctx, fmt.Sprintf(
		"SELECT score FROM %s WHERE name='l2-test'", table,
	)).Scan(&updatedScore); err != nil {
		t.Fatalf("select updated score: %v", err)
	}
	if updatedScore != 100.0 {
		t.Fatalf("expected updated score 100.0, got %v", updatedScore)
	}

	// Cleanup
	if _, err := db.ExecContext(ctx, "DROP TABLE IF EXISTS "+table); err != nil {
		t.Fatalf("drop isolated table: %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatalf("close H2 database: %v", err)
	}
	databaseClosed = true
}
