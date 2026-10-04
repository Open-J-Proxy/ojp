package main

import (
	"context"
	"database/sql"
	"encoding/csv"
	"errors"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
	"time"

	ojpclient "github.com/open-j-proxy/ojp-client-go-database-sql/client"
)

func main() {
	// Default connection values used when no env-based CSV input is provided.
	addr := "127.0.0.1:1059"
	backendURL := "jdbc:postgresql://postgres:5432/ojp"
	dbUser := "ojp"
	dbPassword := "ojp"
	jdbcLine := strings.TrimSpace(env("OJP_JDBC_LINE", ""))
	csvLines := strings.TrimSpace(env("OJP_JDBC_CSV", ""))
	csvIndex := env("OJP_JDBC_CSV_INDEX", "0")

	if jdbcLine != "" {
		// Primary mode: one CSV line from env (normal use case).
		parsed, err := parseOjpCsvLine(jdbcLine)
		if err != nil {
			log.Fatalf("invalid OJP_JDBC_LINE: %v", err)
		}

		addr = parsed.Addr
		backendURL = parsed.BackendURL
		dbUser = parsed.DbUser
		dbPassword = parsed.DbPassword
	} else if csvLines != "" {
		// Fallback mode: multiline CSV + index (test helper mode).
		selected, err := selectCsvLine(csvLines, csvIndex)
		if err != nil {
			log.Fatalf("invalid OJP_JDBC_CSV input: %v", err)
		}
		parsed, err := parseOjpCsvLine(selected)
		if err != nil {
			log.Fatalf("invalid OJP_JDBC_CSV line: %v", err)
		}

		addr = parsed.Addr
		backendURL = parsed.BackendURL
		dbUser = parsed.DbUser
		dbPassword = parsed.DbPassword
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	dataSourceName, err := makeDataSourceName(addr, backendURL, dbUser, dbPassword)
	if err != nil {
		log.Fatalf("build OJP data source name failed: %v", err)
	}
	db, err := sql.Open(ojpclient.DriverName, dataSourceName)
	if err != nil {
		log.Fatalf("open OJP database failed: %v", err)
	}
	defer db.Close()
	if err := db.PingContext(ctx); err != nil {
		log.Fatalf("connect failed: %v", err)
	}

	if strings.HasPrefix(strings.ToLower(backendURL), "jdbc:db2:") {
		if _, err = db.ExecContext(ctx, "SET SCHEMA DB2INST1"); err != nil {
			log.Fatalf("set schema failed: %v", err)
		}
	}
	if _, err = db.ExecContext(ctx, "CREATE TABLE IF NOT EXISTS demo(id INT NOT NULL PRIMARY KEY, name VARCHAR(100))"); err != nil {
		log.Fatalf("create table failed: %v", err)
	}

	if _, err = db.ExecContext(ctx, "DELETE FROM demo WHERE id = ?", 1); err != nil {
		log.Fatalf("clean demo row failed: %v", err)
	}
	if _, err = db.ExecContext(ctx, "INSERT INTO demo(id, name) VALUES (?, ?)", 1, "hello from go"); err != nil {
		log.Fatalf("insert failed: %v", err)
	}
	fmt.Println("READ after CREATE/INSERT:")
	assertDemoRow(db, ctx, 1, "hello from go")

	if _, err = db.ExecContext(ctx, "UPDATE demo SET name = ? WHERE id = ?", "updated from go", 1); err != nil {
		log.Fatalf("update failed: %v", err)
	}
	fmt.Println("READ after UPDATE:")
	assertDemoRow(db, ctx, 1, "updated from go")
	if _, err = db.ExecContext(ctx, "DELETE FROM demo WHERE id = ?", 1); err != nil {
		log.Fatalf("delete failed: %v", err)
	}
	fmt.Println("READ after DELETE:")
	assertDemoRowAbsent(db, ctx, 1)

	if _, err = db.ExecContext(ctx, "DELETE FROM demo WHERE id = ?", 2); err != nil {
		log.Fatalf("clean rollback row failed: %v", err)
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		log.Fatalf("start rollback transaction failed: %v", err)
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO demo(id, name) VALUES (?, ?)", 2, "rollback"); err != nil {
		log.Fatalf("insert rollback row failed: %v", err)
	}
	if err := tx.Rollback(); err != nil {
		log.Fatalf("rollback transaction failed: %v", err)
	}
	assertDemoRowAbsent(db, ctx, 2)

	if _, err = db.ExecContext(ctx, "DELETE FROM demo WHERE id = ?", 3); err != nil {
		log.Fatalf("clean commit row failed: %v", err)
	}
	tx, err = db.BeginTx(ctx, nil)
	if err != nil {
		log.Fatalf("start commit transaction failed: %v", err)
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO demo(id, name) VALUES (?, ?)", 3, "commit"); err != nil {
		log.Fatalf("insert commit row failed: %v", err)
	}
	if err := tx.Commit(); err != nil {
		log.Fatalf("commit transaction failed: %v", err)
	}
	assertDemoRow(db, ctx, 3, "commit")
	if _, err := db.ExecContext(ctx, "DELETE FROM demo WHERE id = ?", 3); err != nil {
		log.Fatalf("clean committed row failed: %v", err)
	}
}

func assertDemoRow(db *sql.DB, ctx context.Context, expectedID int64, expectedName string) {
	var id int64
	var name string
	if err := db.QueryRowContext(ctx, "SELECT id, name FROM demo WHERE id = ?", expectedID).Scan(&id, &name); err != nil {
		log.Fatalf("read demo row: %v", err)
	}
	if id != expectedID || name != expectedName {
		log.Fatalf("unexpected demo row: id=%d name=%q", id, name)
	}
	fmt.Printf("id=%d name=%s\n", id, name)
}

func assertDemoRowAbsent(db *sql.DB, ctx context.Context, id int64) {
	var name string
	err := db.QueryRowContext(ctx, "SELECT name FROM demo WHERE id = ?", id).Scan(&name)
	if !errors.Is(err, sql.ErrNoRows) {
		log.Fatalf("expected demo row %d to be absent, got name=%q error=%v", id, name, err)
	}
}

func makeDataSourceName(endpoint, backendURL, user, password string) (string, error) {
	var dataSourceName strings.Builder
	writer := csv.NewWriter(&dataSourceName)
	if err := writer.Write([]string{"jdbc:ojp[" + endpoint + "]_" + backendURL, user, password}); err != nil {
		return "", err
	}
	writer.Flush()
	if err := writer.Error(); err != nil {
		return "", err
	}
	return strings.TrimSpace(dataSourceName.String()), nil
}

type parsedOjpCsv struct {
	Addr       string
	BackendURL string
	DbUser     string
	DbPassword string
}

// selectCsvLine returns the non-empty line at OJP_JDBC_CSV_INDEX from a
// newline-separated CSV payload.
func selectCsvLine(csvLines, indexRaw string) (string, error) {
	idx, err := strconv.Atoi(strings.TrimSpace(indexRaw))
	if err != nil || idx < 0 {
		return "", fmt.Errorf("OJP_JDBC_CSV_INDEX must be a non-negative number, got %q", indexRaw)
	}

	lines := strings.Split(csvLines, "\n")
	nonEmpty := make([]string, 0, len(lines))
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed != "" {
			nonEmpty = append(nonEmpty, trimmed)
		}
	}

	if len(nonEmpty) == 0 {
		return "", errors.New("OJP_JDBC_CSV has no non-empty lines")
	}
	if idx >= len(nonEmpty) {
		return "", fmt.Errorf("OJP_JDBC_CSV_INDEX=%d out of range (lines=%d)", idx, len(nonEmpty))
	}
	return nonEmpty[idx], nil
}

// parseOjpCsvLine parses one CSV record in OJP format:
// jdbc:ojp[host:port]_backendUrl,user,password
// It extracts OJP address, backend JDBC URL, DB user, and DB password.
func parseOjpCsvLine(line string) (*parsedOjpCsv, error) {
	r := csv.NewReader(strings.NewReader(line))
	r.FieldsPerRecord = 3
	fields, err := r.Read()
	if err != nil {
		return nil, fmt.Errorf("csv parse failed: %w", err)
	}

	ojpJdbcURL := strings.TrimSpace(fields[0])
	dbUser := strings.TrimSpace(fields[1])
	dbPassword := strings.TrimSpace(fields[2])

	const prefix = "jdbc:ojp["
	if !strings.HasPrefix(ojpJdbcURL, prefix) {
		return nil, fmt.Errorf("first field must start with %q, got %q", prefix, ojpJdbcURL)
	}

	bracketEnd := strings.Index(ojpJdbcURL, "]")
	if bracketEnd < 0 {
		return nil, fmt.Errorf("missing closing bracket in %q", ojpJdbcURL)
	}
	if bracketEnd+1 >= len(ojpJdbcURL) || ojpJdbcURL[bracketEnd+1] != '_' {
		return nil, fmt.Errorf("missing '_' separator after OJP endpoint section in %q", ojpJdbcURL)
	}

	addrSection := ojpJdbcURL[len(prefix):bracketEnd]
	if addrSection == "" {
		return nil, fmt.Errorf("empty OJP endpoint section in %q", ojpJdbcURL)
	}

	firstEndpoint := strings.TrimSpace(strings.Split(addrSection, ",")[0])
	if firstEndpoint == "" {
		return nil, fmt.Errorf("empty first endpoint in %q", ojpJdbcURL)
	}
	if openParen := strings.Index(firstEndpoint, "("); openParen >= 0 {
		firstEndpoint = strings.TrimSpace(firstEndpoint[:openParen])
	}
	if firstEndpoint == "" || !strings.Contains(firstEndpoint, ":") {
		return nil, fmt.Errorf("invalid first endpoint %q", firstEndpoint)
	}

	backendURL := "jdbc:" + ojpJdbcURL[bracketEnd+2:]

	return &parsedOjpCsv{
		Addr:       firstEndpoint,
		BackendURL: backendURL,
		DbUser:     dbUser,
		DbPassword: dbPassword,
	}, nil
}

// env returns an environment variable value or the fallback when unset/empty.
func env(name, fallback string) string {
	v := os.Getenv(name)
	if v == "" {
		return fallback
	}
	return v
}
