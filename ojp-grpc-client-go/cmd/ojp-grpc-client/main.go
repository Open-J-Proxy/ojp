package main

import (
	"context"
	"encoding/csv"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"strconv"
	"strings"
	"time"

	ojpclient "github.com/open-j-proxy/ojp-client/internal/client"
	pb "github.com/open-j-proxy/ojp-client/internal/gen/go/com/openjproxy/grpc"
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

	client := ojpclient.NewGrpcStatementServiceClient(addr)

	connectResp, err := client.Connect(ctx, &pb.ConnectionDetails{
		Url:        backendURL,
		User:       dbUser,
		Password:   dbPassword,
		ClientUUID: "go-example-client",
	})
	if err != nil {
		log.Fatalf("connect failed: %v", err)
	}

	// DB2 default schema handling for demo table operations.
	// Without this, DB2 may resolve objects in an unexpected schema.
	if strings.HasPrefix(strings.ToLower(backendURL), "jdbc:db2:") {
		setSchemaReq := &pb.StatementRequest{
			Session: connectResp,
			Sql:     "SET SCHEMA DB2INST1",
		}
		if _, err = client.ExecuteUpdate(ctx, connectResp, setSchemaReq.Sql); err != nil {
			log.Fatalf("set schema failed: %v", err)
		}
	}

	// CREATE
	updateReq := &pb.StatementRequest{
		Session: connectResp,
		Sql:     "CREATE TABLE IF NOT EXISTS demo(id INT NOT NULL PRIMARY KEY, name VARCHAR(100))",
	}
	if _, err = client.ExecuteUpdate(ctx, connectResp, updateReq.Sql); err != nil {
		log.Fatalf("create table failed: %v", err)
	}

	// INSERT
	insertSQL := "INSERT INTO demo(id, name) VALUES (1, 'hello from go')"

	insertReq := &pb.StatementRequest{
		Session: connectResp,
		Sql:     insertSQL,
	}
	if _, err = client.ExecuteUpdate(ctx, connectResp, insertReq.Sql); err != nil {
		log.Fatalf("insert failed: %v", err)
	}

	// READ
	queryReq := &pb.StatementRequest{
		Session: connectResp,
		Sql:     "SELECT id, name FROM demo ORDER BY id",
	}
	results, err := client.ExecuteQuery(ctx, connectResp, queryReq.Sql)
	if err != nil {
		log.Fatalf("executeQuery failed: %v", err)
	}
	for _, msg := range results {
		fmt.Printf("opResult: type=%v uuid=%s\n", msg.GetType(), msg.GetUuid())
	}
	fmt.Println("READ after CREATE/INSERT:")
	var insertedRows []*pb.ResultRow
	for _, r := range results {
		if qr := r.GetQueryResult(); qr != nil {
			insertedRows = append(insertedRows, qr.GetRows()...)
		}
	}
	if len(insertedRows) != 1 {
		log.Fatalf("assert after insert failed: expected 1 row, got %d", len(insertedRows))
	}
	insertCols := insertedRows[0].GetColumns()
	if len(insertCols) < 2 {
		log.Fatalf("assert after insert failed: expected at least 2 columns, got %d", len(insertCols))
	}

	// UPDATE
	updateReq2 := &pb.StatementRequest{
		Session: connectResp,
		Sql:     "UPDATE demo SET name = 'updated from go' WHERE id = 1",
	}
	if _, err = client.ExecuteUpdate(ctx, connectResp, updateReq2.Sql); err != nil {
		log.Fatalf("update failed: %v", err)
	}
	results, err = client.ExecuteQuery(ctx, connectResp, queryReq.Sql)
	if err != nil {
		log.Fatalf("executeQuery after update failed: %v", err)
	}
	for _, msg := range results {
		fmt.Printf("opResult: type=%v uuid=%s\n", msg.GetType(), msg.GetUuid())
	}
	fmt.Println("READ after UPDATE:")
	var updatedRows []*pb.ResultRow
	for _, r := range results {
		if qr := r.GetQueryResult(); qr != nil {
			updatedRows = append(updatedRows, qr.GetRows()...)
		}
	}
	if len(updatedRows) != 1 {
		log.Fatalf("assert after update failed: expected 1 row, got %d", len(updatedRows))
	}
	updateCols := updatedRows[0].GetColumns()
	if len(updateCols) < 2 {
		log.Fatalf("assert after update failed: expected at least 2 columns, got %d", len(updateCols))
	}

	// DELETE
	deleteReq := &pb.StatementRequest{
		Session: connectResp,
		Sql:     "DELETE FROM demo WHERE id = 1",
	}
	if _, err = client.ExecuteUpdate(ctx, connectResp, deleteReq.Sql); err != nil {
		log.Fatalf("delete failed: %v", err)
	}
	results, err = client.ExecuteQuery(ctx, connectResp, queryReq.Sql)
	if err != nil {
		log.Fatalf("executeQuery after delete failed: %v", err)
	}
	for _, msg := range results {
		fmt.Printf("opResult: type=%v uuid=%s\n", msg.GetType(), msg.GetUuid())
	}
	fmt.Println("READ after DELETE:")
	var deletedRows int
	for _, r := range results {
		if qr := r.GetQueryResult(); qr != nil {
			deletedRows += len(qr.GetRows())
		}
	}
	if deletedRows != 0 {
		log.Fatalf("assert after delete failed: expected 0 rows, got %d", deletedRows)
	}
	//close the simple round trip
	_ = client.TerminateSession(context.Background(), connectResp)

	//staring with transcational database connections
	if err = runTransactionRollbackRoundTrip(addr, backendURL, dbUser, dbPassword); err != nil {
		log.Fatalf("transaction rollback round trip failed: %v", err)
	}
	if err = runTransactionCommitRoundTrip(addr, backendURL, dbUser, dbPassword); err != nil {
		log.Fatalf("transaction commit round trip failed: %v", err)
	}

	client.Shutdown()
}

// drainQueryStream consumes the full ExecuteQuery stream, prints each OpResult,
// and returns the collected results for assertions.
func drainQueryStream(stream pb.StatementService_ExecuteQueryClient) ([]*pb.OpResult, error) {
	var results []*pb.OpResult
	for {
		msg, recvErr := stream.Recv()
		if recvErr == io.EOF {
			break
		}
		if recvErr != nil {
			return nil, fmt.Errorf("stream recv failed: %w", recvErr)
		}
		results = append(results, msg)
		fmt.Printf("opResult: type=%v uuid=%s\n", msg.GetType(), msg.GetUuid())
	}
	return results, nil
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
