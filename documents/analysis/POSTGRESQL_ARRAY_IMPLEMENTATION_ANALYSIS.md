# PostgreSQL Array Interface Implementation Analysis

**Date:** 2026-09-19  
**Author:** GitHub Copilot Analysis  
**Status:** Draft

## Executive Summary

This document analyzes what OJP would need in order to support `java.sql.Array`, with the primary focus on **PostgreSQL** and a design that can be extended to other databases later.

### Short Answer

OJP does **not** currently implement `java.sql.Array` in a usable way. The PostgreSQL user report is consistent with the current codebase. To support PostgreSQL arrays correctly, OJP needs:

1. A real client-side `org.openjproxy.jdbc.Array` implementation
2. A transport format for array values over gRPC
3. Server-side binding logic that can create database-native arrays
4. Result-set decoding logic for array columns
5. A database capability model, because JDBC array support is highly vendor-specific

### Recommendation

**Recommended direction:** implement a **canonical OJP array payload** for common scalar arrays first, with **PostgreSQL as the first-class target**, and leave vendor-specific edge cases behind explicit capability checks.

That is not the smallest amount of work, but it is the best technical direction. A PostgreSQL-only string-literal workaround would be faster, but it would also be brittle, incomplete, and hard to generalize cleanly.

---

## 1. Current State in OJP

### 1.1 What exists today

The current code already identifies array support as a gap:

- `org.openjproxy.jdbc.Array` is a stub and explicitly says it is not implemented
- `Connection.createArrayOf(...)` returns that stub
- `PreparedStatement.setArray(...)` records an `ARRAY` parameter, but the transport layer does not carry a usable `java.sql.Array` representation
- `ResultSet.getArray(...)` is not implemented for the non-proxy path
- PostgreSQL integration tests currently avoid real JDBC arrays and use PostgreSQL string literals instead

### 1.2 Why the current implementation fails for real PostgreSQL arrays

At a high level, OJP splits JDBC into:

```text
[Application] -> [OJP JDBC Driver] -> gRPC -> [OJP Server] -> [Real JDBC Driver]
```

That means array support must work across **three boundaries**:

1. **JDBC client API boundary**  
   OJP must behave like a normal JDBC driver from the application's point of view.

2. **OJP transport boundary**  
   OJP must serialize array values and metadata across gRPC.

3. **Backend JDBC driver boundary**  
   OJP server must reconstruct or create a database-native array for the real driver.

Today, the first and second boundaries are incomplete, so the third one never has a real chance.

---

## 2. What PostgreSQL Needs

PostgreSQL is the best first target because it has **real native array support** and the PostgreSQL JDBC driver supports standard JDBC array workflows well enough to make OJP support realistic.

### 2.1 Core JDBC use cases to support

For PostgreSQL, OJP should support at least these cases:

1. `Connection.createArrayOf("integer", new Object[]{1,2,3})`
2. `PreparedStatement.setArray(index, array)`
3. `PreparedStatement.setObject(index, someJavaArray, Types.ARRAY)` when practical
4. `ResultSet.getArray(...)`
5. `Array.getArray()`
6. `Array.getBaseType()`
7. `Array.getBaseTypeName()`
8. `Array.free()`

### 2.2 PostgreSQL-specific behavior OJP must respect

PostgreSQL arrays have a few practical requirements:

- element type names matter (`text`, `integer`, `uuid`, etc.)
- arrays may contain `NULL` elements
- multi-dimensional arrays exist
- some element types are standard scalars, others are PostgreSQL-specific
- array text syntax exists, but escaping rules can be tricky

A first release can reasonably limit scope to one-dimensional scalar arrays **as long as the limitation is explicit**. Claiming broader support too early would create documentation and compatibility risk.

---

## 3. Functional Scope Required for a Real Implementation

### 3.1 Client-side driver work

OJP would need a real `org.openjproxy.jdbc.Array` that stores:

- base type name
- JDBC base type code
- element values
- optional dimensional metadata
- lifecycle state (`free()` called or not)

It should behave like a normal materialized JDBC `Array`, not like a remote LOB handle.

### 3.2 Transport work

OJP needs a new transport representation for arrays. The current transport handles some primitive Java arrays (`int[]`, `long[]`, `String[]`) in isolated places, but that is **not enough** for JDBC `Array`.

The transport needs to carry at least:

- declared SQL base type name
- optional JDBC base type code
- ordered elements
- null element markers
- optional nesting / dimensions

### 3.3 Server-side binding work

On the server side, OJP must turn that payload into something the real JDBC driver accepts, usually by calling:

- `connection.createArrayOf(typeName, elements)`
- then `preparedStatement.setArray(index, sqlArray)`

For PostgreSQL, that is the clean path.

### 3.4 Result decoding work

For reads, OJP needs to:

1. detect when a result column is a SQL array
2. extract metadata from the backend `java.sql.Array`
3. materialize the value into OJP transport
4. reconstruct an OJP `Array` object on the client

### 3.5 Capability model

OJP should not assume every database supports standard arrays the same way. It needs capability checks such as:

- supports native SQL arrays
- supports standard `createArrayOf`
- needs vendor-specific named collection types
- read-only array support vs read/write support

This is important because PostgreSQL is straightforward, while Oracle and some others are not.

---

## 4. Design Options

### Option 1: PostgreSQL text-literal workaround

### Idea

Do not implement a real `java.sql.Array`. Instead, convert Java arrays into PostgreSQL array literal strings such as `'{1,2,3}'`, then bind them as strings and rely on SQL casts.

### Pros

- Smallest implementation
- Fastest path to help Liquibase-style migration scripts
- PostgreSQL-focused and easy to prototype
- No large proto redesign required at first

### Cons

- Not a true JDBC `Array` implementation
- PostgreSQL-specific, not portable
- Easy to get escaping wrong for text values, quotes, backslashes, nested arrays, and nulls
- Weak story for `ResultSet.getArray(...)`
- Hard to extend cleanly to Oracle collections or other vendors
- Encourages SQL-cast coupling in application code

### Verdict

Most suitable as a **short-term workaround** rather than as the long-term design.

---

### Option 2: Serialize vendor JDBC objects directly

### Idea

Try to move backend/vendor array objects through OJP more or less directly.

### Pros

- Can look attractive because the backend driver already knows the exact type
- Might reduce some mapping code for a narrow set of cases

### Cons

- Very brittle across class loaders and JDBC driver implementations
- Breaks OJP's database-agnostic transport direction
- Hard to support safely across client/server boundaries
- Difficult to make portable for non-Java clients in the future
- Likely to fail for PostgreSQL/Oracle vendor internals in subtle ways

### Verdict

This option is high-risk for a proxy architecture because it is too fragile across transport and driver boundaries.

---

### Option 3: Canonical OJP array payload for standard scalar arrays

### Idea

Define an OJP-native array payload in `ojp-grpc-commons` and implement `org.openjproxy.jdbc.Array` as a materialized client-side object. Server-side code converts between that canonical form and the backend driver's native array form.

### Pros

- Best fit for OJP architecture
- Clean JDBC story for PostgreSQL
- Extensible to H2 and potentially DB2
- Can support reads and writes consistently
- Avoids vendor object leakage through transport
- Easier to validate and test

### Cons

- More work than a literal workaround
- Requires protobuf/schema changes
- Requires type mapping design
- Needs careful handling of nulls and nested arrays

### Verdict

This option provides the cleanest base architecture for OJP.

---

### Option 4: Hybrid model

### Idea

Use the canonical OJP payload for supported scalar arrays, but allow vendor-specific fallback paths where necessary.

Examples:

- PostgreSQL: standard path via `createArrayOf`
- H2: same path if compatible
- Oracle: custom server-side adapter that resolves named collection types

### Pros

- Practical balance between purity and real-world compatibility
- Lets PostgreSQL ship first without blocking future Oracle support
- Avoids forcing one model onto databases with different semantics

### Cons

- More branching in the implementation
- Capability matrix and tests become more complex
- Risk of inconsistent behavior between databases

### Verdict

As a roadmap, this option balances extensibility and pragmatism, with Option 3 as the core and vendor-specific adapters only where justified.

---

## 5. Recommended PostgreSQL-First Design

### 5.1 Phase 1 scope

A reasonable Phase 1 scope is limited to these PostgreSQL cases:

- one-dimensional arrays
- common scalar types:
  - `text`
  - `varchar`
  - `integer`
  - `bigint`
  - `smallint`
  - `boolean`
  - `uuid`
  - `float8` / `double precision`
  - `float4` / `real`
  - `numeric`
  - `date`
  - `time`
  - `timestamp`
- null elements
- `createArrayOf`
- `setArray`
- `getArray`
- `Array.getArray`, `getBaseType`, `getBaseTypeName`, `free`

### Why this scope is reasonable

It covers the most likely real use cases:

- Liquibase migrations
- Hibernate / JPA PostgreSQL array columns
- general JDBC usage

It also avoids a dangerous first version that claims support for:

- multi-dimensional arrays
- arrays of custom enums
- arrays of composite types
- arrays of JSON wrapper objects

Those can come later.

## 5.2 Suggested payload shape

At a minimum, OJP's canonical array payload should include:

- `baseTypeName` - e.g. `integer`, `text`, `uuid`
- `jdbcBaseType` - e.g. `Types.INTEGER`
- `elements` - ordered values
- `containsNulls` or explicit null entries
- optional `dimensions`

There are two sub-options:

### A. Strongly typed repeated fields

Example concept:

- repeated strings
- repeated ints
- repeated longs
- repeated booleans

**Pros**
- efficient
- explicit
- easy validation

**Cons**
- many proto branches
- awkward for mixed extension cases
- painful for nested arrays

### B. Recursive generic value container

Example concept:

- array metadata
- repeated `ParameterValue` for each element

**Pros**
- more extensible
- reuses existing transport concepts
- easier to support more types later

**Cons**
- slightly more verbose
- more validation logic needed

### Comparative assessment

Sub-option **B** is the more extensible default because OJP already has `ParameterValue`, and arrays are unlikely to be performance-critical enough to justify a separate parallel type system unless benchmarking later proves otherwise.

---

## 6. PostgreSQL Implementation Steps

### 6.1 Driver module (`ojp-jdbc-driver`)

Required changes:

1. Replace stub `org.openjproxy.jdbc.Array` with a real implementation
2. Store base type metadata and elements
3. Implement:
   - `getArray()`
   - `getArray(long, int)`
   - `getBaseType()`
   - `getBaseTypeName()`
   - `free()`
4. Decide whether `getResultSet()` is:
   - implemented in Phase 1, or
   - explicitly unsupported initially

If scope reduction is needed, `getArray()` can be prioritized over `getResultSet()`. `getResultSet()` improves JDBC completeness, but it is less likely to be the first feature needed to unblock Liquibase users.

### 6.2 gRPC commons (`ojp-grpc-commons`)

Required changes:

1. Extend proto contract for array payloads
2. Extend `ProtoConverter` for:
   - `java.sql.Array`
   - array payload DTO
   - nested element serialization
3. Add compatibility tests for:
   - null array
   - empty array
   - array with null elements
   - scalar arrays by type

### 6.3 Server module (`ojp-server`)

Required changes:

1. Update parameter handling for `ParameterType.ARRAY`
2. Convert OJP payload to backend `java.sql.Array`
3. Free backend array objects when appropriate
4. Materialize backend result arrays into OJP payloads on reads
5. Add capability checks by database type

### 6.4 Testing

Minimum test matrix:

- PostgreSQL:
  - insert/select integer array
  - insert/select text array
  - null array
  - empty array
  - array with null elements
  - `createArrayOf` + `setArray`
  - `getArray`
- H2:
  - confirm whether the same canonical path works
- MySQL/MariaDB:
  - confirm unsupported behavior remains explicit and predictable

---

## 7. Database Support Matrix

This section is about **likely target support for OJP array implementation**, not current OJP behavior.

| Database | Native array concept | Standard JDBC path realistic? | OJP support outlook | Notes |
|---|---|---:|---|---|
| PostgreSQL | Yes | Yes | **Best first target** | Cleanest fit for `createArrayOf` and `getArray` |
| H2 | Yes | Likely yes for basic cases | **Good secondary target** | Useful for local tests and basic compatibility |
| Oracle | Yes, but named collection types | Not via plain standard flow in the same way | **Possible later with adapter** | Needs database-defined collection types and vendor-aware handling |
| DB2 | Limited / version-dependent | Unclear / weaker | **Possible later, low priority** | Needs careful driver-specific verification |
| MySQL | No native arrays | No | **Do not implement** | Keep unsupported |
| MariaDB | No native arrays | No | **Do not implement** | Keep unsupported |
| SQL Server | No native arrays | No | **Do not implement** | Table-valued parameters are a different feature |

### Opinionated priority order

1. **PostgreSQL**
2. **H2**
3. **Oracle** only if there is a real user need
4. **DB2** only after verified demand
5. Do not spend effort on MySQL/MariaDB/SQL Server for `java.sql.Array`

Trying to force a cross-database array abstraction onto databases that do not have native arrays would create a confusing API and a large maintenance burden for limited value.

---

## 8. Key Technical Decisions Still Needed

### 8.1 Materialized array vs remote handle

**Decision needed:** should OJP arrays be fully materialized on the client?

**Assessment:** yes is the simpler default.

Reason:

- arrays are usually much smaller and simpler than LOBs
- JDBC users expect immediate access to array contents
- avoiding a remote handle makes lifecycle management simpler

### 8.2 Multi-dimensional arrays in Phase 1?

**Assessment:** no for Phase 1.

Support them later, after one-dimensional arrays are stable.

### 8.3 `Array.getResultSet()` in Phase 1?

**Assessment:** deferring it is acceptable.

If schedule pressure exists, it is a reasonable Phase 2 feature. But if implemented, it should be based on the materialized client-side array object, not another server round-trip.

### 8.4 Should OJP silently coerce Java arrays passed through `setObject(...)`?

**Assessment:** a conservative policy is safer.

Support the cases that are clearly mappable, and reject ambiguous cases with a good error message. Silent magic here can create very confusing bugs.

---

## 9. Pros and Cons of the Recommended Direction

### Pros

- Solves the real PostgreSQL problem properly
- Aligns with JDBC expectations
- Extensible design rather than a one-off workaround
- Preserves OJP's protocol boundary cleanly
- Gives a path for H2 and possibly Oracle later

### Cons

- Not a tiny feature
- Requires cross-module changes
- Adds testing complexity
- Needs careful documentation of what is and is not supported in each phase

---

## 10. Risks and Concerns

### 10.1 Documentation drift

There is already documentation that suggests PostgreSQL arrays are fully supported, while the code and tests do not support that claim consistently. The clearest example is `/home/runner/work/ojp/ojp/documents/ebook/appendix-e-jdbc-compatibility.md`, section `E.1.4 Array and Struct Support`, which currently says PostgreSQL arrays are "Fully supported (getArray, getResultSet, getBaseType)".

**Concern:** if implementation work starts without first aligning that compatibility appendix and any related support matrices, users will remain confused.

### 10.2 Type-name normalization

PostgreSQL accepts type names like `text`, `integer`, `uuid`, but applications may pass:

- `TEXT`
- `VARCHAR`
- `INT4`
- `java.lang.Integer`

**Concern:** OJP needs a normalization policy, or errors will feel random.

### 10.3 Null and empty semantics

The difference between:

- `NULL`
- empty array `{}`
- array containing nulls like `{1,NULL,3}`

must be preserved exactly.

### 10.4 Oracle pressure too early

One likely pressure point is: "If PostgreSQL arrays are added, Oracle collections should be added immediately too."

Expanding immediately to Oracle collections would materially increase scope unless there is a concrete user requirement driving that work. Oracle collections are meaningfully more complex than PostgreSQL arrays.

### 10.5 Transport complexity creep

If the proto is designed too narrowly for PostgreSQL, OJP will have to break it later. If it is designed too generically, Phase 1 will drag on.

This is the core architectural tension in this feature.

---

## 11. Questions for Maintainers

These are the key questions to answer before implementation starts:

1. **What exact user scenarios must be unblocked first?**
   - Liquibase migrations only?
   - Hibernate/JPA array columns?
   - direct JDBC `setArray/getArray`?

2. **Is Phase 1 allowed to exclude `Array.getResultSet()`?**
   - A "yes" answer is workable, but it should be explicit.

3. **Do we need multi-dimensional PostgreSQL arrays in Phase 1?**
   - My recommendation is no.

4. **Do we want to support Java primitive arrays and object arrays equally at launch?**
   - Example: `int[]` vs `Integer[]`.

5. **Should OJP normalize PostgreSQL type names internally?**
   - Example: `INTEGER`, `int4`, and `integer`.

6. **Is H2 compatibility important as an early test target, or is PostgreSQL-only acceptable initially?**

7. **Do we want Oracle collection support on the roadmap now, or only after PostgreSQL proves stable?**

8. **Should unsupported databases throw `SQLFeatureNotSupportedException` at `createArrayOf(...)`, or only when binding occurs?**
   - Failing early is preferable.

---

## 12. Suggestions

### Suggested delivery plan

#### Phase 0: Cleanup and truthfulness

- align docs with current reality
- document current unsupported behavior clearly
- add a focused issue for PostgreSQL arrays

#### Phase 1: PostgreSQL one-dimensional scalar arrays

- canonical OJP array payload
- `createArrayOf`
- `setArray`
- `getArray`
- clear unsupported messages for the rest

#### Phase 2: H2 validation + API completeness

- validate H2 path
- add `Array.getResultSet()` if still missing
- improve `setObject(..., Types.ARRAY)` support

#### Phase 3: Vendor-specific expansion

- Oracle feasibility spike
- DB2 feasibility spike
- no work for MySQL/MariaDB/SQL Server beyond explicit unsupported behavior

### Suggested engineering discipline

- keep the PostgreSQL implementation narrow and honest
- add integration tests before claiming support
- do not merge documentation that says "fully supported" until the tests prove it

---

## 13. Final Recommendation

If the goal is a **real** `java.sql.Array` implementation, OJP should build a **canonical array payload** and implement PostgreSQL first.

If the goal is only to unblock a narrow class of Liquibase scripts quickly, a PostgreSQL text-literal workaround could be added temporarily, but it should be treated as a stopgap rather than as the final design.

### My recommended decision

1. Commit to **PostgreSQL-first**
2. Use a **canonical OJP array transport model**
3. Limit Phase 1 to **one-dimensional scalar arrays**
4. Fail early and clearly on unsupported databases
5. Defer Oracle/DB2 until there is proven demand

That gives OJP the cleanest balance of user value, correctness, and future extensibility.

---

## 14. Confidence

**Confidence:** High

Why:

- the current code clearly shows `java.sql.Array` is unfinished
- PostgreSQL is a natural first target for JDBC array support
- the main uncertainty is not whether support is needed, but how broad Phase 1 should be
