package gen_test

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"

	pb "github.com/open-j-proxy/ojp-client-go-database-sql/internal/gen/go/com/openjproxy/grpc"
	"google.golang.org/protobuf/reflect/protoreflect"
)

var protoFieldPattern = regexp.MustCompile(`(?m)^\s*(?:(?:repeated|optional|required)\s+)?[\w.]+\s+(\w+)\s*=\s*(\d+)\s*;`)
var protoRPCPattern = regexp.MustCompile(`(?m)\brpc\s+(\w+)\s*\(`)

func TestGeneratedStatementProtocolMatchesProtoSource(t *testing.T) {
	protoPath := findStatementServiceProto(t)
	source, err := os.ReadFile(protoPath)
	if err != nil {
		t.Fatalf("read canonical protocol source: %v", err)
	}

	fileDescriptor := pb.File_StatementService_proto
	for _, name := range []string{
		"ConnectionDetails",
		"SessionInfo",
		"OpQueryResultProto",
		"OpResult",
		"StatementRequest",
		"SqlErrorResponse",
	} {
		sourceFields := parseProtoMessageFields(t, string(source), name)
		message := fileDescriptor.Messages().ByName(protoreflect.Name(name))
		if message == nil {
			t.Fatalf("generated protobuf is missing message %s", name)
		}
		if message.Fields().Len() != len(sourceFields) {
			t.Errorf("%s has %d generated fields; proto source declares %d", name, message.Fields().Len(), len(sourceFields))
		}
		for fieldName, number := range sourceFields {
			field := message.Fields().ByName(protoreflect.Name(fieldName))
			if field == nil || int(field.Number()) != number {
				t.Errorf("%s.%s has generated field number %v; proto source declares %d", name, fieldName, field, number)
			}
		}
	}

	service := fileDescriptor.Services().ByName("StatementService")
	if service == nil {
		t.Fatal("generated protobuf is missing StatementService")
	}
	grpcMethods := make(map[string]bool)
	for _, method := range pb.StatementService_ServiceDesc.Methods {
		grpcMethods[method.MethodName] = true
	}
	for _, method := range pb.StatementService_ServiceDesc.Streams {
		grpcMethods[method.StreamName] = true
	}
	for _, match := range protoRPCPattern.FindAllSubmatch(source, -1) {
		name := string(match[1])
		if service.Methods().ByName(protoreflect.Name(name)) == nil || !grpcMethods[name] {
			t.Errorf("generated gRPC client is missing proto RPC %s", name)
		}
	}
}

func findStatementServiceProto(t *testing.T) string {
	t.Helper()
	directory, err := os.Getwd()
	if err != nil {
		t.Fatalf("find working directory: %v", err)
	}
	for {
		candidate := filepath.Join(directory, "ojp-grpc-commons", "src", "main", "proto", "StatementService.proto")
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
		parent := filepath.Dir(directory)
		if parent == directory {
			t.Fatal("could not locate ojp-grpc-commons/src/main/proto/StatementService.proto")
		}
		directory = parent
	}
}

func parseProtoMessageFields(t *testing.T, source, messageName string) map[string]int {
	t.Helper()
	declaration := "message " + messageName
	start := strings.Index(source, declaration)
	if start < 0 {
		t.Fatalf("proto source is missing message %s", messageName)
	}
	open := strings.IndexByte(source[start:], '{')
	if open < 0 {
		t.Fatalf("proto message %s has no opening brace", messageName)
	}
	open += start
	depth := 0
	inLineComment := false
	end := -1
	for index := open; index < len(source); index++ {
		current := source[index]
		if current == '\n' {
			inLineComment = false
			continue
		}
		if inLineComment {
			continue
		}
		if current == '/' && index+1 < len(source) && source[index+1] == '/' {
			inLineComment = true
			continue
		}
		if current == '{' {
			depth++
		} else if current == '}' {
			depth--
			if depth == 0 {
				end = index
				break
			}
		}
	}
	if end < 0 {
		t.Fatalf("proto message %s has no closing brace", messageName)
	}

	fields := make(map[string]int)
	for _, match := range protoFieldPattern.FindAllStringSubmatch(source[open+1:end], -1) {
		number, err := strconv.Atoi(match[2])
		if err != nil {
			t.Fatalf("invalid field number for %s.%s: %v", messageName, match[1], err)
		}
		fields[match[1]] = number
	}
	return fields
}
