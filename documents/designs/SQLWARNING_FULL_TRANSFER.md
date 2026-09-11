# Design: Full SQLWarning Transfer (message + sqlState + vendorCode + warning chain)

**Context:** PR #584 ("fix: fix SQLWarning serialization between server and JDBC driver") fixed a
crash where `getWarnings()` on `Connection` and `Statement` failed because `SQLWarning` — being a
`Throwable` subclass — cannot be transported by `ProtoConverter`'s primitive/Map/List/Properties
contract. That fix extracted only the warning's text message on the server and reconstructed a
plain `new SQLWarning(message)` on the client, silently dropping `sqlState`, `vendorCode`, and
the entire `getNextWarning()` chain.

This document describes the complete solution that restores all `SQLWarning` attributes and the
full warning chain, and records why a dedicated proto message was considered but not adopted.

---

## 1. What the Minimal Fix Lost

A `SQLWarning` inherits from `SQLException`, which inherits from `Exception`. Its full data model is:

| Attribute | Source | JDBC getter | Example value |
|---|---|---|---|
| `message` | `Throwable` | `getMessage()` | "Data truncated for column 'x'" |
| `sqlState` | `SQLException` | `getSQLState()` | `"01000"`, `"01003"` |
| `vendorCode` | `SQLException` | `getErrorCode()` | `1292` (MySQL) |
| next warning | `SQLWarning` | `getNextWarning()` | another `SQLWarning` or `null` |

The PR #584 approach transferred only `message`. Callers that inspect `sqlState` or `vendorCode`
(frameworks, monitoring tools, ORM mappers, custom code) silently received empty/zero values. The
warning chain (`getNextWarning()`) was completely dropped.

### Real-world examples

- **MySQL / MariaDB** routinely chain multiple `01000` (General warning) warnings after a stored
  procedure call.
- **PostgreSQL** uses `SQLSTATE 01P01` (deprecated feature) and `01000` (warning) with meaningful
  messages and non-zero vendor codes.
- **SQL Server** attaches error numbers as the vendor code via `SQLServerWarning`.
- **Oracle** uses `OracleSQLWarning` with SQLSTATE codes from the `01xxx` family.

---

## 2. Root Cause

`CallResourceAction` intercepts any `SQLWarning` returned by a reflected method call and delegates
serialisation to `ProtoConverter.toParameterValue()`. That converter supports only primitives,
`Map`, `List`, `Properties`, and null. `SQLWarning` — a `Throwable` — falls outside that contract,
causing an `UnsupportedOperationException` at runtime.

A complete fix must extract *all* attributes before that boundary.

---

## 3. Approach Considered: Dedicated Proto Message

A natural fit for a typed, schema-documented contract would be to add new messages to
`StatementService.proto`:

```protobuf
message SqlWarningList {
    repeated SqlWarningEntry entries = 1;
}

message SqlWarningEntry {
    string message    = 1;
    string sqlState   = 2;
    int32  vendorCode = 3;
}
```

The server would build a `SqlWarningList`, and `ProtoConverter` would need a new branch to
serialise it (e.g. `toByteArray()` with a matching deserialisation step on the driver side).

**Why this was not adopted:**

Adding new proto messages requires regenerating gRPC stubs in *both* `ojp-grpc-commons` and all
dependent modules (`ojp-server`, `ojp-jdbc-driver`). For a feature whose only purpose is
transporting diagnostic warnings — not data or control flow — this overhead is disproportionate.
More importantly, it changes the shared API contract between the server and driver in a way that
requires coordinated deployment of both artefacts, and it introduces a new serialisation code path
in `ProtoConverter` that adds complexity with no runtime benefit over the existing `List<Map>`
transport.

The team decided to keep the proto schema stable and use the existing `List<Map<String, Object>>`
infrastructure already supported by `ProtoSerialization`. This delivers the full fix with no
shared-module changes.

---

## 4. Chosen Solution: List&lt;Map&gt; via Existing Transport

`ProtoConverter.toParameterValue()` already recognises `List` and delegates to
`ProtoSerialization.serializeToTransport()`, which handles `List<Map<String, Object>>` natively.
No proto schema changes are needed.

### 4.1 Server — `CallResourceAction`

Walk the warning chain and convert each node to a `Map`:

```java
if (resultFirstLevel instanceof SQLWarning warning) {
    resultFirstLevel = toWarningListMap(warning);
}

private List<Map<String, Object>> toWarningListMap(SQLWarning head) {
    List<Map<String, Object>> list = new ArrayList<>();
    SQLWarning cursor = head;
    while (cursor != null) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("message",    cursor.getMessage());
        entry.put("sqlState",   cursor.getSQLState());
        entry.put("vendorCode", cursor.getErrorCode());
        list.add(entry);
        cursor = cursor.getNextWarning();
    }
    return list;
}
```

### 4.2 Driver — `Connection` and `Statement`

Replace the `String.class` proxy call with a list-based call and delegate to `SqlWarningUtils`:

```java
List<Map<String, Object>> entries = this.callProxy(CallType.CALL_GET, "Warnings", List.class);
return SqlWarningUtils.buildWarningChain(entries);
```

### 4.3 Driver — `SqlWarningUtils` (new shared helper)

```java
public static SQLWarning buildWarningChain(List<Map<String, Object>> entries) {
    if (entries == null || entries.isEmpty()) {
        return null;
    }
    SQLWarning head = null;
    SQLWarning tail = null;
    for (Map<String, Object> entry : entries) {
        String message    = (String) entry.get("message");
        String sqlState   = (String) entry.get("sqlState");
        Number vendorCode = (Number) entry.get("vendorCode");
        SQLWarning w = new SQLWarning(
            message,
            sqlState,
            vendorCode != null ? vendorCode.intValue() : 0
        );
        if (head == null) {
            head = w;
            tail = w;
        } else {
            tail.setNextWarning(w);
            tail = w;
        }
    }
    return head;
}
```

---

## 5. Null Handling Edge Cases

- **`getWarnings()` returns `null`:** `instanceof SQLWarning` is false; `resultFirstLevel` stays
  `null`; client receives `null` list and returns `null`. No special handling needed.
- **`warning.getMessage()` is `null`:** Some drivers return warnings where SQLSTATE is the only
  diagnostic. The `Map` stores `null` for `message`; `buildWarningChain` passes it through
  directly to the `SQLWarning` constructor, which accepts null.
- **`warning.getSQLState()` is `null`:** `getSQLState()` is nullable per JDBC spec. Stored as
  `null` in the map; passed through to the `SQLWarning` constructor.
- **Very long chains:** Uncommon but possible (some MySQL routines emit dozens of `01000`
  warnings). The list approach handles any chain length without special-casing.

---

## 6. Files Changed

| Module | File | Change |
|---|---|---|
| `ojp-server` | `CallResourceAction.java` | Walk chain; convert to `List<Map<String, Object>>` |
| `ojp-jdbc-driver` | `Connection.java` | Use `List.class` proxy call; delegate to `SqlWarningUtils` |
| `ojp-jdbc-driver` | `Statement.java` | Same as `Connection` |
| `ojp-jdbc-driver` | `SqlWarningUtils.java` (new) | `buildWarningChain(List<Map<String, Object>>)` |

No changes to `ojp-grpc-commons` or `StatementService.proto`.

---

## 7. Test Strategy

- **Integration test (H2):** verify null path, `clearWarnings()`, single warning attributes
  (message, sqlState, vendorCode), and chained warnings round-trip correctly.
- **Integration test (MySQL/MariaDB):** `SIGNAL SQLSTATE '01000'` with explicit message and
  vendorCode; two-node chain ordering; connection-level warning; data truncation warning.
- **Integration test (PostgreSQL):** `RAISE WARNING` single node; two-node chain via stored
  procedure; connection-level warning; message content round-trip.
