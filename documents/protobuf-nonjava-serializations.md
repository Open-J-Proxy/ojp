# Protocol Buffer Non-Java Serialization

## Overview

This document describes the language-independent encoding of various types in Protocol Buffer messages for the Open-J-Proxy (OJP) project. These changes replace Java object serialization with protobuf-based representations, enabling cross-language compatibility.

## Motivation

Previously, various objects were serialized using Java's `ObjectOutputStream` and transmitted as opaque byte arrays in protobuf `bytes` fields. This approach had several limitations:

1. **Language Lock-In**: Only Java clients/servers could deserialize these objects
2. **Tight Coupling**: Required exact Java class versions and serialization compatibility
3. **Non-Standard**: Java serialization is not a standard wire protocol
4. **Fragile**: Changes to Java classes could break serialization compatibility

The new approach uses **protobuf-based encoding** to enable:
- Cross-language interoperability
- Human-readable representations in logs and debugging
- Stable wire format independent of Java class changes
- Proper distinction between `null` and empty values

## Supported Types

### 1. Containers (Maps, Lists, Properties)

**Proto File**: `ojp-grpc-contract/proto/containers.proto`
**Package**: `ojp.transport.v1`
**Java Class**: `org.openjproxy.grpc.transport.ProtoSerialization`

#### Container Message
All serializable containers are wrapped in a `Container` message for type-safe deserialization:

```protobuf
message Container {
  oneof content {
    Value value = 1;          // Primitive value or nested structure
    Object object = 2;        // Map/Object
    Array array = 3;          // List/Array
    Properties properties = 4; // Properties (String -> String map)
  }
}
```

#### Supported Container Types

**Map<String, Object>**
```java
Map<String, Object> map = new LinkedHashMap<>();
map.put("key1", "value1");
map.put("nested", nestedMap);

byte[] bytes = ProtoSerialization.serializeToTransport(map);
Map<String, Object> result = ProtoSerialization.deserializeFromTransport(bytes, Map.class);
```

**List<Object>**
```java
List<Object> list = new ArrayList<>();
list.add("item1");
list.add(42);
list.add(nestedList);

byte[] bytes = ProtoSerialization.serializeToTransport(list);
List<Object> result = ProtoSerialization.deserializeFromTransport(bytes, List.class);
```

**Properties**
```java
Properties props = new Properties();
props.setProperty("key", "value");

byte[] bytes = ProtoSerialization.serializeToTransport(props);
Properties result = ProtoSerialization.deserializeFromTransport(bytes, Properties.class);
```

#### Supported Value Types
- `String`
- `Number` (Integer, Long, Float, Double) - all stored as `double`
- `Boolean`
- `null`
- Nested `Map<String, Object>`
- Nested `List<Object>`

#### Limitations
- Map keys must be Strings
- Numbers are stored as `double` (very large `long` values may lose precision)
- Arbitrary Java objects/POJOs are NOT supported - will throw `SerializationException`
- Only the types listed above can be nested within containers

#### Usage Example
```java
import org.openjproxy.grpc.transport.ProtoSerialization;
import org.openjproxy.grpc.transport.ProtoSerialization.SerializationException;

try {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("string", "hello");
    data.put("number", 42);
    data.put("boolean", true);
    data.put("null", null);
    
    List<Object> list = new ArrayList<>();
    list.add("item1");
    list.add(123);
    data.put("list", list);
    
    // Serialize
    byte[] bytes = ProtoSerialization.serializeToTransport(data);
    
    // Deserialize
    Map<String, Object> result = ProtoSerialization.deserializeFromTransport(bytes, Map.class);
} catch (SerializationException e) {
    // Handle unsupported types or invalid data
}
```

### 2. UUID, URL, and RowId

**Proto File**: `ojp-grpc-contract/proto/StatementService.proto`
**Helper Class**: `org.openjproxy.grpc.ProtoTypeConverters`

## Encoding Rules

### UUID
**Type**: `java.util.UUID`  
**Proto Field**: `google.protobuf.StringValue uuid_value`  
**Encoding**: Canonical textual representation (8-4-4-4-12 hexadecimal format)

**Java Encoding**:
```java
UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
String encoded = uuid.toString(); // "550e8400-e29b-41d4-a716-446655440000"
```

**Java Decoding**:
```java
UUID decoded = UUID.fromString(encoded);
```

**Proto Value Example** (JSON representation):
```json
{
  "uuid_value": {
    "value": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

### URL
**Type**: `java.net.URL`  
**Proto Field**: `google.protobuf.StringValue url_value`  
**Encoding**: URL external form (string representation of complete URL)

**Java Encoding**:
```java
URL url = new URL("https://example.com:8080/path?query=value#fragment");
String encoded = url.toExternalForm(); // "https://example.com:8080/path?query=value#fragment"
```

**Java Decoding**:
```java
URL decoded = new URL(encoded); // May throw MalformedURLException
```

**Proto Value Example** (JSON representation):
```json
{
  "url_value": {
    "value": "https://example.com:8080/path?query=value#fragment"
  }
}
```

**Error Handling**: If an invalid URL string is encountered during decoding, an `IllegalArgumentException` is thrown (wrapping `MalformedURLException`).

### RowId
**Type**: `java.sql.RowId`  
**Proto Field**: `google.protobuf.StringValue rowid_value`  
**Encoding**: Base64-encoded bytes from `rowId.getBytes()`

**Java Encoding**:
```java
RowId rowId = resultSet.getRowId(1);
byte[] bytes = rowId.getBytes();
String encoded = Base64.getEncoder().encodeToString(bytes);
```

**Java Decoding** (to bytes only):
```java
byte[] decoded = Base64.getDecoder().decode(encoded);
```

**Proto Value Example** (JSON representation):
```json
{
  "rowid_value": {
    "value": "QWJjZGVmZ2g="
  }
}
```

**IMPORTANT LIMITATION**: `java.sql.RowId` objects are **database vendor-specific** and **cannot be reliably reconstructed** from raw bytes alone. The bytes are **opaque data** that should only be:
- Compared for equality
- Passed back to the same database that created them
- Used with the same JDBC driver implementation

The OJP server receives RowId bytes and passes them to the database using `PreparedStatement.setBytes()` rather than attempting to reconstruct a `RowId` object.

## Null vs Empty String Semantics

All three types use `google.protobuf.StringValue` wrappers to **preserve presence information**:

| Java Value | Proto Representation | Notes |
|------------|---------------------|-------|
| `null` | Wrapper field not set (absent) | `hasUrlValue()` returns false |
| Empty string `""` | Wrapper present with `value = ""` | `hasUrlValue()` returns true, `getValue() == ""` |
| Non-empty value | Wrapper present with encoded value | `hasUrlValue()` returns true, `getValue()` contains data |

**Example (URL)**:
```java
// Null URL
if (url == null) {
    // Do not set url_value field in proto
    builder.clearUrlValue();
}

// Empty string URL (unusual but valid)
URL emptyUrl = new URL(""); // This will actually throw MalformedURLException
// Empty URLs are invalid in practice

// Valid URL
if (url != null) {
    builder.setUrlValue(StringValue.of(url.toExternalForm()));
}

// Reading
if (!message.hasUrlValue()) {
    url = null; // Field not set = null
} else {
    String urlStr = message.getUrlValue().getValue();
    url = new URL(urlStr); // May throw if invalid
}
```

## Code Usage

### Helper Class: ProtoTypeConverters

The `org.openjproxy.grpc.ProtoTypeConverters` utility class provides conversion methods:

```java
// UUID
Optional<StringValue> uuidWrapper = ProtoTypeConverters.uuidToProto(uuid);
UUID uuid = ProtoTypeConverters.uuidFromProto(wrapper);

// URL
Optional<StringValue> urlWrapper = ProtoTypeConverters.urlToProto(url);
URL url = ProtoTypeConverters.urlFromProto(wrapper);

// RowId
Optional<StringValue> rowIdWrapper = ProtoTypeConverters.rowIdToProto(rowId);
byte[] rowIdBytes = ProtoTypeConverters.rowIdBytesFromProto(wrapper);
```

### Setting Values in Proto Builders

```java
// UUID
Optional<StringValue> uuidOpt = ProtoTypeConverters.uuidToProto(myUuid);
uuidOpt.ifPresent(builder::setUuidValue);

// URL
Optional<StringValue> urlOpt = ProtoTypeConverters.urlToProto(myUrl);
urlOpt.ifPresent(builder::setUrlValue);

// RowId
Optional<StringValue> rowIdOpt = ProtoTypeConverters.rowIdToProto(myRowId);
rowIdOpt.ifPresent(builder::setRowidValue);
```

### Reading Values from Proto Messages

```java
// UUID
UUID uuid = message.hasUuidValue() 
    ? ProtoTypeConverters.uuidFromProto(message.getUuidValue()) 
    : null;

// URL
URL url = message.hasUrlValue() 
    ? ProtoTypeConverters.urlFromProto(message.getUrlValue()) 
    : null;

// RowId bytes
byte[] rowIdBytes = message.hasRowidValue() 
    ? ProtoTypeConverters.rowIdBytesFromProto(message.getRowidValue()) 
    : null;
```

## Proto Schema Changes

The `StatementService.proto` file was updated to include:

```protobuf
import "google/protobuf/wrappers.proto";

message ParameterValue {
    oneof value {
        // ... existing fields ...
        
        // URL encoded as string: URL.toExternalForm() on write, new URL(string) on read
        // Presence-aware: unset means null, empty string is a valid (though unusual) URL value
        google.protobuf.StringValue url_value = 14;
        
        // RowId encoded as base64 string of rowId.getBytes()
        // Opaque bytes representation - vendor-specific, cannot be reconstructed into java.sql.RowId
        // Presence-aware: unset means null, empty string means zero-length rowid bytes
        google.protobuf.StringValue rowid_value = 15;
    }
}
```

## Migration Guidance

### For Existing Deployments

**Wire Compatibility**: The changes introduce **new field numbers** (14 and 15) for URL and RowId. The old `bytes_value` field (field 7) is still used for other serialized objects. This means:

- **Forward Compatibility**: Old clients sending URL/RowId as serialized bytes (field 7) will still be understood by the new server, which attempts deserialization. However, this is deprecated.
- **Backward Compatibility**: New clients sending URL/RowId as strings (fields 14, 15) will NOT be understood by old servers, which don't have these fields.

**Migration Strategy Options**:

#### Option A: Coordinated Upgrade (Recommended for controlled environments)
1. Deploy updated servers first (can handle both old and new formats)
2. Deploy updated clients (send new string format)
3. Verify all clients are upgraded
4. Optional: Remove old deserialization fallback code in a future release

#### Option B: Gradual Migration (For distributed systems)
1. Add transitional code to read both old (bytes) and new (string) fields
2. Prioritize new string fields, fall back to old bytes fields
3. Log warnings when old format is detected
4. After migration period, remove old format support

**Example Migration Code** (if supporting both formats):
```java
// Reading URL with fallback (transitional period only)
URL url;
if (message.hasUrlValue()) {
    // New format
    url = ProtoTypeConverters.urlFromProto(message.getUrlValue());
} else if (message.getBytesValue().size() > 0) {
    // Old format (Java serialization) - deprecated
    try {
        url = SerializationHandler.deserialize(
            message.getBytesValue().toByteArray(), 
            URL.class
        );
        logger.warn("Received URL in deprecated Java serialization format");
    } catch (Exception e) {
        logger.error("Failed to deserialize legacy URL", e);
        url = null;
    }
} else {
    url = null;
}
```

**Current Implementation**: The OJP codebase has been updated to ONLY use the new string-based format. No backward-compatibility fallback is included. This means **all clients and servers must be upgraded together**.

### Breaking Changes

1. **Wire Protocol**: Old clients cannot communicate URL/RowId with new servers
2. **RowId Handling**: Server no longer calls `PreparedStatement.setRowId()` - uses `setBytes()` instead
3. **Validation**: Invalid URL strings now throw `IllegalArgumentException` instead of silently failing

## Testing

### Unit Tests

Comprehensive unit tests are provided in `ProtoTypeConvertersTest.java`:

- **Null handling**: Verifies null → absent wrapper
- **Empty string handling**: Verifies empty string → present wrapper with empty value
- **Roundtrip conversion**: Ensures data survives encode→decode cycle
- **Error cases**: Tests invalid input (malformed URLs, invalid UUIDs, invalid Base64)
- **Large data**: Tests RowId with 256-byte arrays

Run tests:
```bash
mvn test -Dtest=ProtoTypeConvertersTest
```

### Integration Testing

**IMPORTANT**: Integration tests (`*IntegrationTest.java`) were **not modified** as per requirements. They may need updates if they rely on URL/RowId serialization behavior.

### Manual Testing Checklist

1. **UUID Parameters**:
   - [ ] Set UUID parameter in PreparedStatement
   - [ ] Verify UUID is transmitted and received correctly
   - [ ] Confirm null UUID is handled properly

2. **URL Parameters**:
   - [ ] Set URL parameter (valid HTTPS URL)
   - [ ] Set URL parameter with query strings and fragments
   - [ ] Verify malformed URL throws exception
   - [ ] Confirm null URL is handled properly

3. **RowId Parameters**:
   - [ ] Retrieve RowId from ResultSet
   - [ ] Use RowId in UPDATE statement (WHERE ROWID=?)
   - [ ] Verify bytes are transmitted correctly
   - [ ] Confirm null RowId is handled properly

## Limitations

### RowId Reconstruction
**Cannot reconstruct `java.sql.RowId` objects**: The Base64-encoded bytes are opaque and database-vendor-specific. Different databases (Oracle, PostgreSQL, SQL Server) have incompatible RowId implementations. The bytes can only be:
- Compared for equality
- Passed back to the originating database
- Used with the same JDBC driver

**Workaround**: The server uses `PreparedStatement.setBytes(idx, rowIdBytes)` instead of `setRowId()`. This works for most databases that accept RowId in WHERE clauses, but may not work for all databases or all RowId use cases.

### Empty String Handling
**URL**: Empty strings are technically invalid URLs and will throw `MalformedURLException`. However, the presence wrapper still distinguishes `null` (absent) from `""` (present but invalid).

**UUID**: Empty strings are invalid UUIDs and will throw `IllegalArgumentException` from `UUID.fromString("")`.

**RowId**: Empty strings decode to zero-length byte arrays, which may or may not be valid depending on the database.

### Performance
String encoding adds minimal overhead:
- **UUID**: 36 characters vs ~16 bytes (Java serialization overhead is larger)
- **URL**: Variable length, similar to original string
- **RowId**: Base64 encoding increases size by ~33% vs raw bytes, but removes Java serialization overhead

## Release Notes

### Version 0.2.1 (Current)

**Breaking Changes**:
- URL and RowId parameters now use string-based encoding in protobuf
- Removed Java object serialization for UUID, URL, and RowId types
- Server-side RowId handling changed from `setRowId()` to `setBytes()`

**New Features**:
- Added `ProtoTypeConverters` utility class for type conversions
- Added 26 new unit tests for conversion logic
- Language-independent protobuf encoding enables non-Java clients

**Migration Required**:
- All clients and servers must be upgraded together
- No backward compatibility with old Java-serialized format

**Files Changed**:
- `ojp-grpc-contract/proto/StatementService.proto`
- `ojp-grpc-commons/src/main/java/org/openjproxy/grpc/ProtoConverter.java`
- `ojp-grpc-commons/src/main/java/org/openjproxy/grpc/ProtoTypeConverters.java` (new)
- `ojp-server/src/main/java/org/openjproxy/grpc/server/statement/ParameterHandler.java`
- `ojp-grpc-commons/src/test/java/org/openjproxy/grpc/ProtoTypeConvertersTest.java` (new)

## Regenerating Protocol Buffer Classes

After modifying `.proto` files, regenerate Java classes:

```bash
cd ojp-grpc-commons
mvn clean compile
```

Generated files location: `target/generated-sources/protobuf/java/`

The protobuf-maven-plugin configuration (in `ojp-grpc-commons/pom.xml`) reads the canonical definitions from `../ojp-grpc-contract/proto` and automatically runs protoc during the `compile` phase.

## Further Reading

- [Protocol Buffers Language Guide](https://developers.google.com/protocol-buffers/docs/proto3)
- [Protocol Buffers Well-Known Types](https://developers.google.com/protocol-buffers/docs/reference/google.protobuf)
- [Java UUID Specification](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/UUID.html)
- [Java URL Specification](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/URL.html)
- [JDBC RowId Specification](https://docs.oracle.com/en/java/javase/11/docs/api/java.sql/java/sql/RowId.html)

## Contact

For questions or issues related to this migration, please open an issue on the GitHub repository:
https://github.com/Open-J-Proxy/ojp/issues
