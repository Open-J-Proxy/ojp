# OJP Simplified Flow Diagrams

Simplified Flow Diagrams help developers understand what happens across components without reading implementation details. Start with the [executeQuery Simplified Flow Diagram](EXECUTE_QUERY_FLOW.md).

## Knowledge in layers

First see the [system picture](../../README.md#system-picture): application → JDBC driver → server → database. It is a component map, not an execution sequence.

Then choose an operation below. Follow its diagram, read the essential notes and assumptions, and use **Go deeper** links for specialised scenarios. Source checkpoints let you verify or change the implementation. Stop at the layer that answers your question; deployment and configuration are directly accessible through the [documentation hub](../README.md).

## Main flows

Everyday JDBC operations:

| Flow | Useful boundary |
|---|---|
| [Connect](CONNECT_FLOW.md) | Application requests a connection → driver can send database work |
| [executeQuery](EXECUTE_QUERY_FLOW.md) | Application requests rows → rows are read and the result is closed |
| [executeUpdate](EXECUTE_UPDATE_FLOW.md) | Application sends a change → affected-row count is returned |
| [Commit / rollback](TRANSACTION_FLOW.md) | Application ends a transaction → server completes it at the database |
| [Close connection](CLOSE_CONNECTION_FLOW.md) | Application closes its connection → server releases session resources |

## Main XA flows

The transaction manager coordinates distributed transactions; OJP relays each participant's XA operations. These diagrams separate that protocol from regular JDBC transactions.

| Flow | Useful boundary |
|---|---|
| [XA connection setup](XA_CONNECT_FLOW.md) | Obtain an XA handle → server-bound session ready for enlistment |
| [XA branch work](XA_BRANCH_FLOW.md) | Start a branch → execute SQL → end its work |
| [XA prepare / commit / rollback](XA_COMPLETION_FLOW.md) | Prepare and vote → coordinator decision → branch completion |
| [XA connection closure](XA_CLOSE_FLOW.md) | Close after completion → completed backend returned and session removed |
| [XA recovery scan](XA_RECOVERY_FLOW.md) | Request a scan → return recoverable branch identifiers |

## Shared remote operations

| Flow | Useful boundary |
|---|---|
| [Call Proxy](CALL_PROXY_FLOW.md) | A JDBC operation needs remote access → server invokes it and returns the result |

Call Proxy is the shared remote-call path used by many operations, including metadata, statement settings, and cursor operations.

## Method

- Give each flow its own Markdown file under `documents/designs`, named for the operation.
- State the trigger, starting assumptions, and where the flow ends in one or two sentences.
- Trace the current implementation before drawing; show the normal successful path, not every helper call.
- Use a Mermaid `flowchart` with **at most 15 step rectangles**, preferably fewer. Count substeps toward that limit.
- Put every step inside a component `subgraph` (the bigger rectangle), such as `Application`, `ojp-jdbc-driver`, `ojp-server`, or `Database`.
- Label steps with sequential numbers and short, plain-language actions. Describe what happens, not class or method names.
- Use `2.a`, `2.b` only when splitting one logical step improves understanding. Keep branches rectangular and label their arrows; avoid decision diamonds.
- Show component crossings and repeated work clearly. If work overlaps, explain that briefly rather than implying everything runs serially.
- After the diagram, add only essential numbered notes: configuration that changes behavior, important exceptions, or resource lifetime.
- Finish with a few source links for verification. Keep implementation names there, not in the diagram.

Keep optional features and failure recovery in short notes or separate flows when they would obscure the main path. When behaviour changes, review the main path, assumptions, resource lifetimes, handoffs, notes, and source checkpoints. Internal refactoring alone need not change the diagram if that story remains accurate.
