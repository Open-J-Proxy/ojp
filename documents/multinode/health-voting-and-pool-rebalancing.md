# Health Voting and Pool Rebalancing

In a multinode cluster, each OJP server opens only its share of a pool's connections. When an OJP server goes down, the remaining servers grow their pools so total capacity stays the same. This page explains how a server decides that another server is down, what that decision cannot protect against, and how to turn rebalancing off. See the [multinode guide](README.md) for the rest of the multinode setup.

## How Voting Works

Every request from the JDBC driver carries the client's view of the cluster, for example `a:1059(UP);b:1059(DOWN);c:1059(UP)`. The receiving server treats this as one vote per server in that client's URL list.

The rule for each server is:

- A server counts as **DOWN** only after DOWN votes have continued for a full vote window (10 seconds by default) **and** no client has reported it UP in the meantime.
- A single UP vote from any client cancels the DOWN streak immediately.
- A server that nobody has voted on counts as UP.
- A confirmed DOWN server stays DOWN until a client reports it UP again.

The result is "grow slowly, shrink fast". A pool expands only after the window passes. When a server is reported UP again, the other servers shrink their pools straight away.

Each pool is sized from the votes on the servers **in its own URL list**. Clients with different lists still combine their views on the servers they share.

### Example

Server A hears from two clients:

- Client 1 uses servers A, B and C.
- Client 2 uses servers A and B.

Client 2 loses its network path to B and starts reporting `b(DOWN)`. Client 1 still reaches B and keeps reporting `b(UP)`. Client 1's UP vote cancels the DOWN streak, so A keeps both pools at their normal size and no extra connections are opened.

If B really dies, both clients report it DOWN. After 10 seconds with no UP votes, A resizes both pools: client 1's pool divides by 2 instead of 3, and client 2's pool by 1 instead of 2.

If no server has seen a pool yet, the first connection uses the reporting client's votes, so a new pool opens at the correct size.

## Configuration

Set these on every OJP server, as JVM properties, in `ojp.properties`, or as environment variables.

| Property | Environment variable | Default | Meaning |
|----------|----------------------|---------|---------|
| `ojp.server.multinode.poolRebalancing.enabled` | `OJP_SERVER_MULTINODE_POOLREBALANCING_ENABLED` | `true` | `false` turns off automatic resizing. |
| `ojp.server.multinode.healthVoteWindowMs` | `OJP_SERVER_MULTINODE_HEALTHVOTEWINDOWMS` | `10000` | How long, in milliseconds, DOWN votes must continue with no UP vote before a server counts as DOWN. `0` makes the first DOWN vote take effect immediately. |

A longer window gives more clients time to vote and makes false alarms less likely, but surviving servers take longer to pick up the lost capacity.

### Disabling Rebalancing (Fixed Shares)

```bash
java -Dojp.server.multinode.poolRebalancing.enabled=false -jar ojp-server.jar
```

When rebalancing is disabled:

- Each server keeps its share of the pool permanently. For example, `maximumPoolSize=30` across 3 servers is always 10 per server.
- Health reports from clients are ignored for pool sizing. The driver still routes requests around failed servers.
- Database connection budgets are divided by the configured server count.
- When a server goes down, cluster capacity drops. In the example, two surviving servers serve the load with 20 connections.

Use fixed shares when the database connection limit must never be exceeded, for example when the database is close to its `max_connections`.

## Limitations

Voting reduces false alarms, but it is not distributed consensus. The connections that can be opened beyond the configured total are described below, using `maximumPoolSize=30` across three servers A, B and C (10 each).

- **Split brain can still happen.** Each server hears only from clients that can reach it. If the network splits into {clients, A} and {clients, B, C}, A may decide that B and C are down and grow to 30, while B and C still hold 10 each, giving 50 connections. This is unlikely inside one data centre but more plausible across zones or regions.
- **A server that is cut off from clients but still connected to the database keeps its connections.** If clients cannot reach C because of a network fault or a long GC pause, A and B grow to 15 each while C still holds its 10, giving 40 until C's connections close.
- **Only active clients vote.** Idle clients send no votes, so they cannot cancel a DOWN streak. A single active client that loses its path to B can still trigger expansion after the window if no other client reports B as UP.
- **Server addresses must match exactly.** Votes are matched by `host:port` (case-insensitive). If one client uses `ojp-b:1059` and another uses `10.0.0.12:1059`, they are counted as different servers.
- **Failover is slower.** Surviving servers expand only after the slowest active client notices the failure, plus the vote window.
- **Shrinking is gradual.** When a server recovers, the pools shrink immediately, but active connections are not terminated. The total can stay above the limit until busy connections are returned.
- **Budgets are local to each server.** Database connection budgets are enforced by each server separately from its own view, not across the cluster.

To guarantee a hard limit, also cap connections in the database itself, for example with PostgreSQL's `ALTER ROLE app CONNECTION LIMIT 30`, or disable rebalancing.
