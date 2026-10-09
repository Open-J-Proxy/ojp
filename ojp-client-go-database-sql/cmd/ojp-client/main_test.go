package main

import (
	"testing"
)

func TestSelectCsvLineShouldReturnRequestedNonEmptyLine(t *testing.T) {
	csvLines := "\nline-0\n\n line-1 \nline-2\n"
	got, err := selectCsvLine(csvLines, "1")
	if err != nil {
		t.Fatalf("selectCsvLine returned error: %v", err)
	}
	if got != "line-1" {
		t.Fatalf("expected line-1, got %q", got)
	}
}

func TestSelectCsvLineShouldFailForInvalidIndex(t *testing.T) {
	_, err := selectCsvLine("a\nb", "x")
	if err == nil {
		t.Fatal("expected error for invalid index, got nil")
	}
}

func TestSelectCsvLineShouldFailForOutOfRangeIndex(t *testing.T) {
	_, err := selectCsvLine("a\nb", "5")
	if err == nil {
		t.Fatal("expected out-of-range error, got nil")
	}
}

func TestParseOjpCsvLineShouldParseSingleEndpoint(t *testing.T) {
	line := "jdbc:ojp[localhost:1059]_h2:~/test,sa,"
	got, err := parseOjpCsvLine(line)
	if err != nil {
		t.Fatalf("parseOjpCsvLine returned error: %v", err)
	}
	if got.Addr != "localhost:1059" {
		t.Fatalf("expected addr localhost:1059, got %q", got.Addr)
	}
	if got.BackendURL != "jdbc:h2:~/test" {
		t.Fatalf("expected backend jdbc:h2:~/test, got %q", got.BackendURL)
	}
	if got.DbUser != "sa" {
		t.Fatalf("expected user sa, got %q", got.DbUser)
	}
	if got.DbPassword != "" {
		t.Fatalf("expected empty password, got %q", got.DbPassword)
	}
}

func TestParseOjpCsvLineShouldUseFirstEndpointAndStripDatasourceName(t *testing.T) {
	line := "\"jdbc:ojp[host1:1059(main),host2:1060]_postgresql://localhost:5432/defaultdb\",testuser,testpassword"
	got, err := parseOjpCsvLine(line)
	if err != nil {
		t.Fatalf("parseOjpCsvLine returned error: %v", err)
	}
	if got.Addr != "host1:1059" {
		t.Fatalf("expected addr host1:1059, got %q", got.Addr)
	}
	if got.BackendURL != "jdbc:postgresql://localhost:5432/defaultdb" {
		t.Fatalf("unexpected backendURL: %q", got.BackendURL)
	}
}

func TestParseOjpCsvLineShouldFailForInvalidPrefix(t *testing.T) {
	_, err := parseOjpCsvLine("jdbc:postgresql://localhost:5432/db,user,pass")
	if err == nil {
		t.Fatal("expected error for invalid prefix, got nil")
	}
}
