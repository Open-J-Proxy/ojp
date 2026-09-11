package main

import (
	"context"
	"fmt"
	"strings"
	"time"

	ojpclient "github.com/open-j-proxy/ojp-client/internal/client"
	pb "github.com/open-j-proxy/ojp-client/internal/gen/go/com/openjproxy/grpc"
)

// runTransactionRollbackRoundTrip verifies local transaction rollback behavior.
// It inserts a row inside a transaction and asserts that the row disappears
// after calling RollbackTransaction.
func runTransactionRollbackRoundTrip(addr, backendURL, dbUser, dbPassword string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	client := ojpclient.NewGrpcStatementServiceClient(addr)
	defer client.Shutdown()

	session, err := client.Connect(ctx, &pb.ConnectionDetails{
		Url:        backendURL,
		User:       dbUser,
		Password:   dbPassword,
		ClientUUID: "go-example-client-tx-rollback",
	})
	if err != nil {
		return fmt.Errorf("connect failed: %w", err)
	}
	defer func() {
		_ = client.TerminateSession(context.Background(), session)
	}()

	if strings.HasPrefix(strings.ToLower(backendURL), "jdbc:db2:") {
		if _, err = client.ExecuteUpdate(ctx, session, "SET SCHEMA DB2INST1"); err != nil {
			return fmt.Errorf("set schema failed: %w", err)
		}
	}

	// CREATE
	if _, err = client.ExecuteUpdate(ctx, session, "CREATE TABLE IF NOT EXISTS demo(id INT NOT NULL PRIMARY KEY, name VARCHAR(100))"); err != nil {
		return fmt.Errorf("create table failed: %w", err)
	}

	// DELETE
	if _, err = client.ExecuteUpdate(ctx, session, "DELETE FROM demo WHERE id = 2"); err != nil {
		return fmt.Errorf("cleanup delete failed: %w", err)
	}

	txSession, err := client.StartTransaction(ctx, session)
	if err != nil {
		return fmt.Errorf("start transaction failed: %w", err)
	}
	fmt.Println("TRANSACTION started")

	// INSERT
	if _, err = client.ExecuteUpdate(ctx, txSession, "INSERT INTO demo(id, name) VALUES (2, 'hello in tx')"); err != nil {
		return fmt.Errorf("insert in transaction failed: %w", err)
	}

	// READ
	beforeRollbackResults, err := client.ExecuteQuery(ctx, txSession, "SELECT id, name FROM demo WHERE id = 2")
	if err != nil {
		return fmt.Errorf("execute query before rollback failed: %w", err)
	}
	for _, msg := range beforeRollbackResults {
		fmt.Printf("opResult: type=%v uuid=%s\n", msg.GetType(), msg.GetUuid())
	}
	fmt.Println("READ inside TRANSACTION before ROLLBACK:")
	var rowsBeforeRollback int
	for _, r := range beforeRollbackResults {
		if qr := r.GetQueryResult(); qr != nil {
			rowsBeforeRollback += len(qr.GetRows())
		}
	}
	if rowsBeforeRollback != 1 {
		return fmt.Errorf("assert before rollback failed: expected 1 row, got %d", rowsBeforeRollback)
	}

	sessionAfterRollback, err := client.RollbackTransaction(ctx, txSession)
	if err != nil {
		return fmt.Errorf("rollback transaction failed: %w", err)
	}
	fmt.Println("TRANSACTION rolled back")

	// READ
	afterRollbackResults, err := client.ExecuteQuery(ctx, sessionAfterRollback, "SELECT id, name FROM demo WHERE id = 2")
	if err != nil {
		return fmt.Errorf("execute query after rollback failed: %w", err)
	}
	for _, msg := range afterRollbackResults {
		fmt.Printf("opResult: type=%v uuid=%s\n", msg.GetType(), msg.GetUuid())
	}
	fmt.Println("READ after ROLLBACK:")
	var rowsAfterRollback int
	for _, r := range afterRollbackResults {
		if qr := r.GetQueryResult(); qr != nil {
			rowsAfterRollback += len(qr.GetRows())
		}
	}
	if rowsAfterRollback != 0 {
		return fmt.Errorf("assert after rollback failed: expected 0 rows, got %d", rowsAfterRollback)
	}

	return nil
}

// runTransactionCommitRoundTrip verifies local transaction commit behavior.
// It inserts a row inside a transaction and asserts that the row remains
// visible after calling CommitTransaction.
func runTransactionCommitRoundTrip(addr, backendURL, dbUser, dbPassword string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	client := ojpclient.NewGrpcStatementServiceClient(addr)
	defer client.Shutdown()

	session, err := client.Connect(ctx, &pb.ConnectionDetails{
		Url:        backendURL,
		User:       dbUser,
		Password:   dbPassword,
		ClientUUID: "go-example-client-tx-commit",
	})
	if err != nil {
		return fmt.Errorf("connect failed: %w", err)
	}
	defer func() {
		_ = client.TerminateSession(context.Background(), session)
	}()

	if strings.HasPrefix(strings.ToLower(backendURL), "jdbc:db2:") {
		if _, err = client.ExecuteUpdate(ctx, session, "SET SCHEMA DB2INST1"); err != nil {
			return fmt.Errorf("set schema failed: %w", err)
		}
	}

	// CREATE
	if _, err = client.ExecuteUpdate(ctx, session, "CREATE TABLE IF NOT EXISTS demo(id INT NOT NULL PRIMARY KEY, name VARCHAR(100))"); err != nil {
		return fmt.Errorf("create table failed: %w", err)
	}

	// DELETE
	if _, err = client.ExecuteUpdate(ctx, session, "DELETE FROM demo WHERE id = 3"); err != nil {
		return fmt.Errorf("cleanup delete failed: %w", err)
	}

	txSession, err := client.StartTransaction(ctx, session)
	if err != nil {
		return fmt.Errorf("start transaction failed: %w", err)
	}
	fmt.Println("TRANSACTION started for COMMIT test")

	// INSERT
	if _, err = client.ExecuteUpdate(ctx, txSession, "INSERT INTO demo(id, name) VALUES (3, 'hello commit tx')"); err != nil {
		return fmt.Errorf("insert in transaction failed: %w", err)
	}

	sessionAfterCommit, err := client.CommitTransaction(ctx, txSession)
	if err != nil {
		return fmt.Errorf("commit transaction failed: %w", err)
	}
	fmt.Println("TRANSACTION committed")

	// READ
	afterCommitResults, err := client.ExecuteQuery(ctx, sessionAfterCommit, "SELECT id, name FROM demo WHERE id = 3")
	if err != nil {
		return fmt.Errorf("execute query after commit failed: %w", err)
	}
	for _, msg := range afterCommitResults {
		fmt.Printf("opResult: type=%v uuid=%s\n", msg.GetType(), msg.GetUuid())
	}
	fmt.Println("READ after COMMIT:")
	var rowsAfterCommit int
	for _, r := range afterCommitResults {
		if qr := r.GetQueryResult(); qr != nil {
			rowsAfterCommit += len(qr.GetRows())
		}
	}
	if rowsAfterCommit != 1 {
		return fmt.Errorf("assert after commit failed: expected 1 row, got %d", rowsAfterCommit)
	}

	// DELETE
	if _, err = client.ExecuteUpdate(ctx, sessionAfterCommit, "DELETE FROM demo WHERE id = 3"); err != nil {
		return fmt.Errorf("post-commit cleanup delete failed: %w", err)
	}

	return nil
}
