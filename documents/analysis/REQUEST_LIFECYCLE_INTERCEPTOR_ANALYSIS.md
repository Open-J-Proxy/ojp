# Request Lifecycle Interceptor API Analysis

This analysis was partially implemented in this PR https://github.com/Open-J-Proxy/ojp/pull/744, when implementing it start from the PR acknowledging that the PR code is outdated in relation to the main branch.

## Purpose

Evaluate whether OJP should provide a plugin API, inspired by Servlet Filters, for modules to participate in server-side request processing. This document is an architectural analysis only; it does not implement or commit OJP to the proposed API.

## Current State

OJP already uses `ServiceLoader` for extension points such as XA connection-pool providers. Request processing, however, is not exposed through a general-purpose interceptor SPI. The gRPC `StatementServiceImpl` delegates operations to server actions, and request-related behavior is coordinated through those actions and shared server context.

This creates a useful foundation: the existing SPI and external-library loading approach can inform plugin discovery, while interception should be placed at the OJP request/action layer rather than treating database requests as generic gRPC transport events.

## Problem and Goals

Cross-cutting behavior—such as SQL transformation, admission control, circuit breaking, request metrics, and audit logging—can require coordination with request execution. Without a common extension contract, each feature needs its own integration point and third-party modules cannot participate consistently.

An interceptor API should:

- Offer stable, documented hooks around server request processing.
- Allow ordered, composable plugins, including short-circuiting where appropriate.
- Reuse OJP's existing SPI and external-library loading conventions.
- Preserve current behavior when no plugins are configured.
- Keep database, session, transaction, and resource ownership in OJP core.

It should not expose unrestricted access to pooled connections or replace the existing connection-pool SPIs.

## Options Considered

| Option | Benefits | Limitations |
| --- | --- | --- |
| Request lifecycle interceptor chain | Ordered hooks, can transform context or short-circuit, supports before/after work and cleanup | Requires precise rules for ordering, exceptions, context mutation, and resource ownership |
| More specialized SPIs or action hooks | Smaller contracts tailored to individual features; easier to constrain capabilities | Can reproduce today's separate integration patterns and grow into many overlapping APIs |
| gRPC server interceptors | Established hooks for transport-level concerns such as metadata and authentication | Do not naturally model SQL execution, admission slots, result processing, or database-resource cleanup |
| Event/listener API | Suitable for observation and asynchronous side effects | Poor fit for synchronous transformation, admission decisions, or short-circuiting |

**Assessment:** A request-level chain best fits synchronous extensions that need to observe or influence processing. gRPC interceptors remain appropriate for transport concerns, and events remain useful for notifications. Neither should be stretched to own database lifecycle behavior.

## Candidate Design

Use a small request-scoped context and an ordered chain. An interceptor could declare its identity, priority, supported request types/phases, and an invocation method that receives the context and continuation. The context should expose only the information and capabilities needed for the supported use cases.

Potential phases include:

1. **Before request** — validate or enrich request metadata.
2. **Before execution** — validate or transform SQL, perform a circuit-breaker check.
3. **Resource acquisition** — coordinate with admission control where ownership boundaries are explicit.
4. **After execution** — inspect or transform a result only where result streaming semantics permit.
5. **Resource release** — release resources acquired by an interceptor.
6. **After request / failure** — record outcomes and perform final cleanup.

These are candidate boundaries, not a required eight-phase contract. Start with the smallest set that has clear semantics. In particular, avoid exposing an “execution” hook that permits plugins to bypass core execution or hold server-owned connections.

The chain needs deterministic ordering and well-defined continuation behavior: whether an interceptor may invoke the continuation zero, one, or multiple times; how short-circuit results are represented; and how failures are mapped to gRPC responses. Cleanup must run on success, short-circuit, and failure, without allowing a plugin exception to skip core resource release.

## Discovery and Compatibility

`ServiceLoader` is consistent with existing OJP extension points. Plugin loading should follow the server's established external-library path and startup behavior. Discovery alone is not a sufficient lifecycle policy: operators need a clear way to enable, disable, and diagnose plugins.

The API is a server-side compatibility surface. It needs an explicit versioning policy, stable context semantics, deterministic handling of duplicate identifiers, and clear behavior when a provider fails to load. Plugins are trusted code running in the server process; classloading does not provide sandboxing or isolation.

## Risks and Open Questions

- **API scope:** Which request types and phases have sufficiently stable semantics to publish?
- **Ordering:** Is priority alone adequate, and how are ties resolved deterministically?
- **Context exposure:** Which SQL, session, datasource, and result details may plugins access? Sensitive credentials and mutable core state should not be exposed by default.
- **Mutation:** Which fields can be changed, and how are original and transformed SQL retained for diagnostics?
- **Execution semantics:** How are streaming results, batches, prepared statements, transactions, and cancellation represented?
- **Cleanup:** What guarantees apply when a plugin fails, returns early, or acquires its own resource?
- **Reliability:** Should plugin failures fail a request, be isolated, or be configurable by phase? Silent continuation could hide correctness or security failures.
- **Performance:** What is the measured overhead with no plugins and with representative chains? No latency claim should be made before benchmarking.
- **Security and operations:** How are plugin provenance, configuration, startup diagnostics, and deployment risks communicated to operators?
- **Testing:** How will ordering, short-circuiting, exceptions, cleanup, and real server behavior be tested?

## Recommendation

Proceed with a design prototype before committing to a broad lifecycle API. First define the request context, supported use cases, ordering and exception semantics, and cleanup guarantees. Then validate the design against one transformation use case and one observational use case, without migrating all existing behavior at once.

Keep the first API narrowly scoped to request processing, preserve existing behavior when disabled or unused, and measure its overhead. Add phases or capabilities only when a concrete use case demonstrates the need. Treat compatibility, plugin trust, and failure handling as core API requirements rather than later implementation details.
