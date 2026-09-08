# Which Consensus Algorithm Fits OJP's Messaging Design?

## Question

[OJP_MESSAGING_PROTOCOL_ANALYSIS.md](./OJP_MESSAGING_PROTOCOL_ANALYSIS.md)
uses "RAFT" as a placeholder name for "leader-election/consensus algorithm."
This document treats the choice as open and compares RAFT against
Byzantine-fault-tolerant (BFT) alternatives — PBFT, HotStuff, Tendermint,
BFT-SMaRt — for OJP's two transports: the opt-in **direct mesh** and the
default **client-relay**.

The short answer: **RAFT (via Apache Ratis)**, run over whichever topology
the deployment already uses: the direct mesh when mesh is ON, encrypted
client-relay when mesh is OFF. One topology setting governs every message
type — there's no separate per-topic switch. §5 walks through the
client-relay case in detail, with concrete examples.

---

## 1. Why transport affects the algorithm choice

Consensus algorithms differ in what they assume about their transport:

- **Crash-fault-tolerant (CFT)** algorithms, like RAFT, assume every message
  came from a legitimate, fixed cluster member — a peer can crash or go
  silent, but a message that arrives is trusted at face value.
- **Byzantine-fault-tolerant (BFT)** algorithms assume some participants may
  actively lie, forge, or send conflicting messages, and tolerate that up
  to a bound (`f` faulty nodes out of `N`).

The direct mesh's participants are exactly the fixed, operator-configured
set of OJP servers — the situation CFT algorithms are built for.
Client-relay routes messages through application processes, which are a
different, larger, less centrally-operated population — this is the
situation that makes BFT interesting to consider, and is the focus of §5.

---

## 2. Candidate algorithms

| Algorithm | Fault model | Quorum | Message complexity | Typical latency | Java maturity |
|---|---|---|---|---|---|
| **RAFT** | Crash-only (CFT) | `N = 2f+1` | `O(N)` | Low (single round-trip per decision) | **High** — Apache Ratis is production-grade, used by Ozone/Ratis-based systems |
| **PBFT** | Byzantine | `N = 3f+1` | `O(N²)` | Medium (3 message phases) | Low — no mainstream, maintained Java implementation |
| **HotStuff** | Byzantine | `N = 3f+1` | `O(N)` (its key improvement over PBFT) | Medium | Low — reference implementations are Go/C++; used in Diem/Aptos, not Java |
| **Tendermint** | Byzantine | `N = 3f+1` | `O(N²)` | Medium-high (block-based, not single-decision) | Low in Java — the ecosystem is Go (Cosmos SDK); built for blockchain-style block finality, a heavier fit than "elect one leader" |
| **BFT-SMaRt** | Byzantine | `N = 3f+1` | `O(N²)`, with batching optimizations | Medium | **Medium** — the only Java-native, embeddable BFT library among these |

**Reading the table:** every BFT option needs 50% more nodes for the same
fault tolerance (`3f+1` vs `2f+1`) and at least quadratic messaging unless
using HotStuff specifically. That's the price of tolerating lying nodes,
not just crashed ones — worth paying only where the trust assumption RAFT
makes is actually false.

### 2.1 Why not a Byzantine-fault-tolerant algorithm

This document's overall recommendation is RAFT everywhere — mesh ON or
OFF — not a BFT algorithm. Consolidated reasoning:

1. **The trust gap is real but narrow, and encryption closes the part that
   matters.** RAFT's crash-only assumption ("a message that arrives is
   trusted at face value") is not fully true for client-relay: OJP clients
   are application processes, not OJP servers. But they are not anonymous
   internet hosts either — they are the operator's own application
   instances, on the operator's own isolated network, secured the same way
   as everything else in the deployment. That's a **narrow** trust gap
   (trusted-for-queries vs. trusted-for-consensus), not a **wide** one
   (trusted vs. an unknown adversary). A single shared AEAD key (§5.1)
   closes exactly that narrow gap: it makes forging or altering a consensus
   message cryptographically impossible for a client, which is the specific
   thing BFT would otherwise be paying for. RAFT + encryption is a
   reasonable, proportionate answer to "the transport isn't fully trusted,"
   without paying for protection against a fully adversarial network that
   OJP doesn't actually have.
2. **BFT has a hard, non-negotiable minimum cluster size that RAFT doesn't.**
   Tolerating even one faulty node needs `N = 3f+1`, so `f=1` requires
   **4 nodes minimum** for any BFT algorithm in the table, vs. **3 nodes**
   for RAFT (`N = 2f+1`). For a project whose stated target is "a handful
   of servers," that's not a rounding error — it can be the difference
   between a 3-node cluster being viable at all and requiring a 4th node
   purely for the consensus algorithm's sake, with no other use for it.
   That's a real cost increase for small deployments, for a trust problem
   (§1 above) that mostly doesn't apply here.
3. **Java ecosystem maturity is lopsided.** Apache Ratis (RAFT) is a mature,
   production-grade, actively maintained Java library with real production
   users. Of the four BFT candidates, three (PBFT, HotStuff, Tendermint)
   have no mainstream Java implementation at all — their reference
   implementations are Go/C++, meaning OJP would be building and
   maintaining its own Java BFT implementation from a paper, not adopting
   a library. Only BFT-SMaRt is Java-native, and even that has materially
   less production track record than Ratis. Betting a core availability
   feature (leader election) on a from-scratch or lightly-proven
   implementation is a large, avoidable risk.
4. **More message phases means more ways to get the implementation wrong.**
   RAFT needs one round-trip per decision; BFT algorithms need multiple
   message phases (PBFT's pre-prepare/prepare/commit, for example) with
   more edge cases (view changes, equivocation detection) to implement
   correctly. More protocol surface to build, test, and operate is itself
   a cost — independent of raw message count — for a team that would be
   building this in-house rather than adopting a finished product.
5. **BFT doesn't actually fix client-relay's real remaining risk.** §5.2
   shows the honest residual risk in client-relay is a compromised driver
   build or a compromised shared network path — not a client validator
   voting maliciously within the consensus protocol. BFT algorithms assume
   Byzantine behavior *from a fixed, known set of validators*; an OJP
   client was never a validator in the consensus sense, it's a relay. A
   compromised driver build defeats a BFT protocol exactly as it would
   defeat RAFT — it can suppress or alter what it forwards before the
   protocol even sees it. So the extra cost of BFT would be paid without
   closing the one gap that's actually still open.

Put together: BFT would cost more nodes, more mature-library risk, and more
implementation surface, to defend against a threat (fully adversarial,
unknown participants) that isn't OJP's actual deployment shape, while
leaving the one real residual risk (§5.2) exactly as open as RAFT does.
That's why RAFT + a shared AEAD key, not a BFT algorithm, is the
recommendation for both mesh ON and mesh OFF.

---

## 3. Mesh ON: recommendation

**RAFT, via Apache Ratis.** The mesh's participants are exactly the fixed,
operator-configured peer set RAFT assumes. Switching to a BFT algorithm
here buys protection against a scenario — a configured OJP server acting
maliciously — that, if it happens, is already a bigger operational problem
than consensus safety (that server also serves real client queries, sees
real data, and holds real credentials). It also costs more nodes, more
messages, and a Java ecosystem with materially less production track record
than Ratis.

**When this would change:** if OJP is ever deployed as a shared, mutually
untrusting multi-tenant mesh — operators who don't otherwise trust each
other's servers — BFT-SMaRt is the one Java-native, embeddable option worth
prototyping. Not needed for a single-operator cluster, which is what this
document assumes today. **Open question: is a multi-tenant mesh a real
target, or is every deployment single-operator?**

---

## 4. Mesh OFF: recommendation

**RAFT over encrypted client-relay** (§5.5) is the approach for consensus
when the mesh is off — not an alternative to something else, this is what
"mesh off" means for consensus too. One topology setting governs every
topic, consensus included: mesh ON routes everything (consensus, cache
invalidation, restart notices) over the direct mesh; mesh OFF routes
everything over client-relay. Consensus additionally requires the shared
AEAD key described in §5.1 whenever it runs over client-relay, since
unencrypted client-relay is fine for cache invalidation but not for
consensus (§5). §5 has the full reasoning and concrete configuration.

---

## 5. Can client-relay carry consensus messages reliably?

This is the practical question: OJP clients are trusted applications, not
random internet hosts, and every client is already connected to every
server. Doesn't that make client-relay good enough for consensus, without
needing the mesh at all?

### 5.1 What encryption actually fixes

Yes — if every consensus envelope is protected with **authenticated
encryption** (AEAD, e.g. AES-GCM), a relaying client genuinely cannot forge
a vote, alter a message, or impersonate a server, provided the key is only
known to OJP servers. That is a real, complete fix for **tampering and
forgery**. Plain confidentiality-only encryption (e.g. unauthenticated
AES-CBC) does *not* give this — it hides the bytes but doesn't stop someone
from flipping or replacing them; AEAD (or plaintext + MAC) is required.

**Key model: one shared cluster key, not one key per server.** A separate
key per server (or per server-pair) adds PKI-like distribution and
rotation overhead that doesn't fit OJP's simple, single-jar deployment
model. Instead: **a single symmetric AEAD key, shared by every OJP server
in the cluster**, configured the same way other OJP settings already are —
a JVM system property, an environment variable, or an `ojp.server.*`
property — with no built-in key-management service. This matches OJP's
expected deployment shape: applications, OJP servers, and databases running
together on an isolated, operator-controlled network, not exposed on the
open internet. In that setting, a shared secret distributed via config
(the same way a database password already is) is a reasonable, low-overhead
fit — not a compromise made only because something better wasn't available.

This is worth stating plainly: **given trusted client applications, a
single shared AEAD key, and an isolated deployment network, "the client
rewrites the message" is a solved problem.**

**Honest limitations of one shared key** (documented so operators can judge
fit, not hidden):
- **No per-server accountability.** Any server can produce a validly-signed
  message; you cannot cryptographically prove *which* server sent a given
  envelope. Not a concern for a single trusted operator's own cluster;
  would matter if OJP servers themselves needed to distrust each other.
- **Single blast radius.** Leaking the key from any one server (or from
  the config file/env var that holds it) lets an attacker forge messages
  as any server. Mitigated by normal secret-handling practice — the same
  care already required for database credentials — not by the messaging
  design itself.
- **Rotation is manual.** There's no built-in rotation mechanism; changing
  the key requires updating it on every server (a rolling restart, or a
  dual-key grace-period scheme) — standard practice for a shared secret,
  not a special case.
- **Not recommended** for a multi-tenant deployment where the OJP servers
  themselves belong to mutually-untrusting parties, or for any deployment
  where the network between OJP servers and application clients isn't
  otherwise trusted/isolated — that's a different threat model than the
  one this document assumes (§1).

### 5.2 What encryption does not fix

Encryption protects the message. It does nothing about whether the courier
**delivers** it at all.

**Simple example:** imagine a sealed, tamper-proof envelope handed to a
courier. Sealing it stops the courier from reading or altering the letter
inside. It does not stop the courier from putting it in a drawer and
forgetting about it, walking a different route that takes longer, or
simply choosing not to deliver it today.

**But this is much harder to pull off than it sounds, because of fan-out.**
`Publish` sends the envelope to *every* client subscribed on the source
server, and each one independently relays it. To make one specific message
never reach a specific target server, an attacker (or a bug) has to make
**every single one** of the clients that bridge those two servers fail to
relay it — not just one. With, say, 10 clients connecting server A to
server B, that means compromising or disabling 10 independent processes in
exact coincidence, not one. That is a real, meaningful bar, and it's fair
to say encryption plus full fan-out addresses the "one rogue client drops
one message" version of this concern.

**What's left is the case where the clients aren't actually independent.**
Two realistic ways that happens:
- **A shared bug or a supply-chain compromise in the driver itself.** Every
  client uses the same `ojp-jdbc-driver` build. If that build (or a
  malicious dependency inside it) is the thing deciding whether to relay,
  then all 10 "independent" clients are really running the same decision
  logic — compromising the driver once is the same as compromising all
  10 at once. This is not a per-client attack; it's a single point of
  failure disguised as many.
- **A shared network path.** If every one of those 10 clients' traffic to
  server B crosses the same load balancer, sidecar proxy, or network
  segment, an attacker controlling that one shared point can drop the
  message for all 10 without touching any client process.

Neither of these is defeated by "send it to more clients," because more
clients does not mean more *independence* if they all trust the same
binary or cross the same wire. This is the honest, narrower version of the
concern — not "a client might drop a message," but "the one thing all
clients share (their code, or their network path) might drop it for all of
them at once."

For RAFT specifically, this narrower risk (not the discredited "any one
client can bias an election" version) is what remains: a compromised driver
build or a compromised shared network path could selectively suppress
specific messages — say, only one candidate's vote requests — while
leaving everything else working normally, and RAFT's crash-only design has
no way to detect that as anything other than normal message loss (which it
already tolerates, so it wouldn't even raise an alarm).

### 5.3 Redundancy math for the independent-failure case

To make §5.2's independence point concrete: if each of `k` relaying clients
has a small, independent chance `p` of failing to relay (crashed, network
blip, GC pause — not a shared cause), the chance that *all of them* fail is
`p^k`. At `p = 0.05` (5% chance any one client fails) and `k = 10`
connected clients, the chance every single one fails is `0.05^10` —
practically zero. This is why client-relay works well in practice against
ordinary, uncoordinated failures, and it's the same math that makes §5.2's
"attacker needs all 10, not 1" point real.

It does not touch cost: sending via `k` clients is `k` times the relay
traffic (§5.3.1 of the messaging analysis already quantifies plain relay as
up to `clients × servers` calls per broadcast). More redundancy for better
odds is more of that same traffic, not a separate expense.

### 5.4 Bottom line

- **Forgery/tampering: solved** by a single shared AEAD key held only by
  OJP servers, given clients are trusted applications on an isolated
  network (§5.1).
- **Full suppression by one rogue or failing client: solved** by full
  fan-out — an attacker needs every bridging client to fail at once, not
  one (§5.2, §5.3).
- **Remaining risk: a single point shared by all clients** — the driver
  build every client runs, or a network path every client's traffic
  crosses — since that isn't defeated by adding more clients (§5.2).
- **Cost and latency** scale with the number of connected clients and with
  how tuned their network paths are, neither of which client-relay
  controls (§5.3, §5.5 below).

These are no longer reasons to rule client-relay out for consensus
outright — they're the concrete, documented trade-offs of the approach
used whenever the mesh is off, described next.

### 5.5 RAFT over encrypted client-relay — the mesh-off approach

Given §5.2–§5.4, running RAFT over an encrypted, redundant client-relay is
a legitimate choice, not a rejected idea — it's what "mesh off" means for
consensus. Concrete configuration:

1. **A single shared AEAD key** (§5.1) on every `raft.*` envelope,
   configured once per server via JVM property / environment variable /
   `ojp.server.*` setting — the same distribution model already used for
   other OJP server config — so a relaying client cannot forge or alter a
   message.
2. **A monotonic sequence number per producer**, so a replayed old message
   is rejected instead of accepted twice.
3. **Rely on full fan-out for redundancy** — every connected client already
   relays independently; no extra fan-out logic needed beyond what
   client-relay already does.
4. **Widen RAFT's election timeout** well beyond the 150–300ms typical for
   a direct-mesh deployment — e.g. into the 1–5 second range — to absorb
   app-process scheduling jitter on the relay path instead of triggering
   spurious elections. This trades faster failover for tolerating
   relay-path latency.
5. **Keep the driver's relay logic minimal and audited.** Since §5.2's
   remaining risk is the shared driver code path itself, that code should
   do nothing more than "verify AEAD tag, check sequence number, forward" —
   no parsing of consensus semantics, so a driver bug has the smallest
   possible blast radius.

**What you get:** no direct connection between OJP servers, ever; message
forgery is closed; single-client failures (random crashes, blips) are
absorbed by redundancy.

**What you still accept:** slower failover (from the widened timeout); the
`clients × servers` traffic cost at heartbeat frequency (real, ongoing
infrastructure load, not a one-time cost); and the narrower residual risk
in §5.2 — a compromised driver build or a compromised shared network
segment could still suppress specific messages without being detected,
because RAFT cannot distinguish that from ordinary message loss it already
tolerates; and it still depends on at least one client being connected, so
it doesn't cover long client-free periods (serverless, §8 of the messaging
analysis) — that's the case for turning the mesh ON instead, cluster-wide,
not just for consensus.

---

## 6. Overall recommendation

| | Recommendation |
|---|---|
| Mesh ON (all topics) | RAFT via Apache Ratis, over the direct mesh |
| Mesh OFF (all topics) | Client-relay for everything; consensus specifically requires the shared AEAD key + sequence numbers + a widened election timeout (§5.5) — cache invalidation is fine unencrypted |
| Switch to BFT | Only if a multi-tenant, mutually-untrusting mesh becomes a real deployment target (open question, §3) |

## 7. Open questions

1. Is a multi-tenant, mutually-untrusting mesh a real target for OJP, or is
   every deployment single-operator? (Drives §3.)
2. If the shared AEAD key (§5.1) is built for client-relay's consensus path
   or cache-invalidation path, what's the exact config surface (property
   name, env var name, JVM flag) and rotation procedure? This needs its
   own design, not an add-on to this analysis.
3. Should the driver expose a way for an application to observe "my relay
   attempt failed" instead of it being silent? Would help operators notice
   the shared-failure-point risk in §5.2 sooner, even without fully
   solving it.
4. What election-timeout value is actually safe for encrypted client-relay
   (§5.5, item 4) in a real deployment? This needs measured relay-path
   latency data, not a guess — should be validated before recommending a
   specific number.
