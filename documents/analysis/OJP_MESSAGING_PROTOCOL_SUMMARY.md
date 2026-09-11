# OJP Generic Messaging Protocol — Executive Summary

Full analysis: [OJP_MESSAGING_PROTOCOL_ANALYSIS.md](./OJP_MESSAGING_PROTOCOL_ANALYSIS.md)
Related analysis: [OJP_CONSENSUS_ALGORITHM_ANALYSIS.md](./OJP_CONSENSUS_ALGORITHM_ANALYSIS.md)

## What's proposed

A generic `MessagingService` gRPC contract — `Publish`, `Subscribe`, `Ack`;
topic + opaque payload + delivery mode — added to `ojp-grpc-commons` and
consumed through the existing `ojp-jdbc-driver` connection/session/failover
machinery. No new wire protocol, no mandatory external broker.

```proto
service MessagingService {
  rpc Publish (PublishRequest) returns (PublishAck);
  rpc Subscribe (SubscribeRequest) returns (stream Envelope);
  rpc Ack (AckRequest) returns (AckResponse);
}

message Envelope {
  string topic = 2;
  bytes payload = 3;
  DeliveryMode delivery_mode = 6;
  bool cluster_scope = 8;
}
```

Two delivery modes: **fire-and-forget** (at-most-once, no ack — default for
consensus, cache invalidation) and **guaranteed** (at-least-once, ack +
retry + dedup — default for server-restart notices). Both modes work over
any topology, including client-relay.

## Two server-to-server topologies

| | Client-relay | Direct mesh |
|---|---|---|
| Default | **Yes, always on** | No — opt-in (`ojp.server.mesh.enabled`) |
| New connections between servers | **None** | One channel per configured peer |
| Works with zero clients connected | No | Yes |
| Good for | All topics, when mesh is off (consensus needs `GUARANTEED` mode + encryption, see below) | All topics, when mesh is on; required for any deployment with long zero-client periods (serverless) |

Client-relay works because multinode clients are already connected to every
server in the cluster and forward `cluster_scope=true` messages between the
servers they hold sessions to — no new connection, no new config. The
direct mesh is a small, explicit exception: servers open a channel directly
to configured peers, reusing the driver's client-side gRPC plumbing as a
library (not its public JDBC API), only when an operator turns it on. One
setting governs every topic, including consensus — there's no separate
per-topic switch.

## Consensus over each topology

When the mesh is on, consensus runs over it like everything else: cost is
`O(servers)` regardless of client count, and it works with zero clients
connected (serverless).

When the mesh is off, consensus runs over **encrypted client-relay** —
that's the approach for mesh-off consensus, not an alternative to
something else. Full fan-out means an attacker needs to defeat *every*
client bridging two servers to suppress a message, not just one — a real,
meaningful bar. What's left is a single point shared by all clients (the
driver build they all run, or a network path they all cross), which more
clients doesn't fix. This path also costs `O(clients × servers)` per
heartbeat and needs a widened election timeout to tolerate relay-path
latency, trading failover speed for staying mesh-free. Full reasoning and
configuration:
[OJP_CONSENSUS_ALGORITHM_ANALYSIS.md §5](./OJP_CONSENSUS_ALGORITHM_ANALYSIS.md#5-can-client-relay-carry-consensus-messages-reliably).

## Which consensus algorithm

RAFT (via Apache Ratis) — its crash-only trust assumption matches the
direct mesh's fixed, operator-configured peer set. BFT alternatives (PBFT,
HotStuff, Tendermint, BFT-SMaRt) cost more nodes and more messages to
tolerate a threat model (malicious peers) that doesn't fit a
single-operator mesh. Full comparison:
[OJP_CONSENSUS_ALGORITHM_ANALYSIS.md](./OJP_CONSENSUS_ALGORITHM_ANALYSIS.md).

## Other notes/limitations

1. Inter-server authentication for the direct mesh doesn't exist yet.
   Recommended: **mTLS** — gives each peer its own revocable identity and
   reuses standard gRPC/HTTPS certificate infrastructure, vs. a shared
   secret where leaking it from one server compromises the whole mesh with
   no way to revoke a single peer. Full reasoning: §9 item 3.
2. Client-relay supports both delivery modes, including `GUARANTEED`
   (ack + retry at every hop — a slow or briefly-disconnected bridging
   client doesn't lose the message). What retries can't fix: if zero
   currently-connected clients bridge the source and target server at
   publish time, there's no path to retry over. Not a cluster-wide
   guarantee independent of client topology. Consensus traffic stays off
   the relay allowlist by default (§9 items 8, 10).
3. `GUARANTEED` mode is documented as **not crash-durable**: retries stop
   if the publisher crashes, permanently losing anything still queued.
   Fine for `server.lifecycle` (a crashed server can't announce its own
   restart anyway); a future need for crash-durable delivery would need a
   real broker, not an extension of this design (§9 item 4).
