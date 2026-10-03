# Documenting OJP main flows

These overviews help developers understand what happens across components without reading implementation details. Start with the [executeQuery example](EXECUTE_QUERY_FLOW.md).

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

## Main flows to cover

| Flow | Useful boundary |
|---|---|
| Connect | Application requests a connection → driver can send database work |
| executeQuery | Application requests rows → rows are read and the result is closed |
| executeUpdate | Application sends a change → affected-row count is returned |
| Commit / rollback | Application ends a transaction → server completes it at the database |
| Close connection | Application closes its connection → server releases session resources |

Only **executeQuery** is illustrated here; the other entries identify follow-up overviews, not existing diagrams. Keep optional features and failure recovery in short notes or separate flows when they would obscure the main path.
