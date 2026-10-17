package client

import (
	"context"
	"encoding/csv"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	pb "github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/com/openjproxy/grpc"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
)

func TestH2DatabaseShouldSupportL3FetchNextRowsAndClose(t *testing.T) {
	if !strings.EqualFold(strings.TrimSpace(os.Getenv("OJP_TEST_H2")), "true") {
		t.Skip("set OJP_TEST_H2=true to run the real-server H2 L3 fetch suite")
	}
	endpoint := strings.TrimSpace(os.Getenv("OJP_TEST_H2_ADDR"))
	if endpoint == "" {
		t.Fatal("OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true")
	}
	jdbcURL, user, password, err := readL3H2ConnectionConfig()
	if err != nil {
		t.Fatalf("read H2 connection CSV: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	rpc, err := newRPCClient(endpoint)
	if err != nil {
		t.Fatalf("create OJP client: %v", err)
	}
	connection, err := rpc.connect(ctx, connectionConfig{URL: jdbcURL, User: user, Password: password})
	if err != nil {
		_ = rpc.close()
		t.Fatalf("connect to OJP: %v", err)
	}
	t.Cleanup(func() {
		if err := connection.close(context.Background()); err != nil {
			t.Errorf("terminate OJP session: %v", err)
		}
		if err := rpc.close(); err != nil {
			t.Errorf("close OJP client: %v", err)
		}
	})

	table := fmt.Sprintf("ojp_go_l3_fetch_%d", time.Now().UnixNano())
	if err := executeUpdateAndCloseKeys(ctx, connection, "CREATE TABLE "+table+" (id INT PRIMARY KEY, name VARCHAR(30))"); err != nil {
		t.Fatalf("create pagination table: %v", err)
	}
	t.Cleanup(func() {
		if err := executeUpdateAndCloseKeys(context.Background(), connection, "DROP TABLE IF EXISTS "+table); err != nil {
			t.Errorf("drop pagination table: %v", err)
		}
	})
	if err := executeUpdateAndCloseKeys(ctx, connection, fmt.Sprintf(
		"INSERT INTO %s (id, name) SELECT X, CONCAT('row-', X) FROM SYSTEM_RANGE(1, 250)",
		table,
	)); err != nil {
		t.Fatalf("insert pagination rows: %v", err)
	}

	statementUUID, err := connection.prepareStatement(ctx, fmt.Sprintf("SELECT id, name FROM %s ORDER BY id", table))
	if err != nil {
		t.Fatalf("prepare paginated query: %v", err)
	}
	t.Cleanup(func() {
		if err := connection.closePreparedStatement(context.Background(), statementUUID); err != nil {
			t.Errorf("close prepared statement: %v", err)
		}
	})

	resultSetUUID := executePreparedQueryForTest(t, ctx, connection, statementUUID)
	rowsRead := 0
	pages := 0
	for {
		page, err := connection.fetchNextRows(ctx, resultSetUUID, 100)
		if err != nil {
			t.Fatalf("fetch result page %d: %v", pages+1, err)
		}
		if len(page.Rows) == 0 {
			break
		}
		pages++
		for _, row := range page.Rows {
			expectedID := int64(rowsRead + 1)
			if len(row) != 2 || row[0] != expectedID || row[1] != fmt.Sprintf("row-%d", expectedID) {
				t.Fatalf("unexpected row %d in page %d: %v", rowsRead, pages, row)
			}
			rowsRead++
		}
	}
	if pages < 2 || rowsRead != 250 {
		t.Fatalf("expected multiple fetched pages containing 250 ordered rows, got %d pages and %d rows", pages, rowsRead)
	}
	if err := connection.closeResultSet(ctx, resultSetUUID); err != nil {
		t.Fatalf("close exhausted result set: %v", err)
	}

	partialUUID := executePreparedQueryForTest(t, ctx, connection, statementUUID)
	firstPage, err := connection.fetchNextRows(ctx, partialUUID, 100)
	if err != nil {
		t.Fatalf("fetch partial result: %v", err)
	}
	if len(firstPage.Rows) == 0 {
		t.Fatal("expected rows before early result-set close")
	}
	if err := connection.closeResultSet(ctx, partialUUID); err != nil {
		t.Fatalf("close partial result set: %v", err)
	}
	countResult, err := connection.query(ctx, "SELECT COUNT(*) FROM "+table, nil)
	if err != nil {
		t.Fatalf("query after early result-set close: %v", err)
	}
	if err := connection.closeResultSet(ctx, countResult.resultSetUUID); err != nil {
		t.Fatalf("close query after early result-set close: %v", err)
	}
}

func executeUpdateAndCloseKeys(ctx context.Context, connection *rpcConnection, sql string) error {
	_, generatedKeysUUID, err := connection.executeUpdate(ctx, sql, nil)
	if err != nil {
		return err
	}
	if generatedKeysUUID != "" {
		_, err = connection.fetchGeneratedKeys(ctx, generatedKeysUUID)
	}
	return err
}

func executePreparedQueryForTest(
	t *testing.T,
	ctx context.Context,
	connection *rpcConnection,
	statementUUID string,
) string {
	t.Helper()
	var trailer metadata.MD
	response, err := connection.client.rpc.CallResource(ctx, &pb.CallResourceRequest{
		Session:      cloneSession(connection.session),
		ResourceType: pb.ResourceType_RES_PREPARED_STATEMENT,
		ResourceUUID: statementUUID,
		Target: &pb.TargetCall{
			CallType:     pb.CallType_CALL_EXECUTE,
			ResourceName: "executeQuery",
		},
	}, grpc.Trailer(&trailer))
	if err != nil {
		t.Fatalf("execute prepared query: %v", grpcError(ctx, err, trailer))
	}
	if response == nil || len(response.GetValues()) != 1 {
		t.Fatal("OJP server returned an invalid result-set identifier")
	}
	connection.applySession(response.GetSession())
	resultSetUUID, err := decodeValueTo[string](response.GetValues()[0])
	if err != nil {
		t.Fatalf("decode result-set identifier: %v", err)
	}
	if resultSetUUID == "" {
		t.Fatal("OJP server returned an empty result-set identifier")
	}
	return resultSetUUID
}

func readL3H2ConnectionConfig() (string, string, string, error) {
	file, err := os.Open("testdata/h2_l1_connection.csv")
	if err != nil {
		return "", "", "", err
	}
	defer file.Close()

	record, err := csv.NewReader(file).Read()
	if err != nil {
		return "", "", "", err
	}
	if len(record) != 3 || strings.TrimSpace(record[0]) == "" {
		return "", "", "", fmt.Errorf("expected CSV fields: JDBC URL, username, password")
	}
	return strings.TrimSpace(record[0]), strings.TrimSpace(record[1]), record[2], nil
}
