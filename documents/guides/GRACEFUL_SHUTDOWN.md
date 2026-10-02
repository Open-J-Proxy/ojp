# OJP Server Graceful Shutdown

This guide explains what happens when an OJP server is stopped (`SIGTERM`), how the JDBC driver reacts, and how to configure Kubernetes and Docker so that rolling restarts do not break applications.

## In short

- On `SIGTERM` the server **drains**: it stops accepting new sessions but lets open sessions (transactions, open result sets, XA branches) finish.
- The driver sees a dedicated "server draining" error, **stops routing new work** to that server and **retries requests without a session on another server**. Existing sessions stay on the draining server until they finish.
- When the server is back, the driver's normal health check brings it back into rotation. Nothing new to configure on the client.
- **Zero-downtime restarts need at least two OJP servers** in the JDBC URL. With one server, new requests fail while it drains and until it is back.

## Shutdown sequence (server)

When the JVM receives `SIGTERM`, the shutdown hook runs these steps in order:

1. **DRAINING** – the server stops accepting new work:
   - `connect()` calls (including the driver's heartbeat health check) are rejected.
   - Requests that would open a new session (statements without a session, `startTransaction`, new XA connections, LOB creation without a session) are rejected.
   - The rejection uses gRPC status `UNAVAILABLE`, the description `OJP server draining` and the trailer `ojp-server-draining: true`.
   - Requests that belong to an **existing session** keep working: statements, `commit`, `rollback`, fetching more rows, reading LOBs, XA `end`/`prepare`/`commit`/`rollback`.
2. The gRPC health service status for the OJP server is set to `NOT_SERVING`, so Kubernetes gRPC probes and load balancers stop sending traffic.
3. The server waits until there are **no open sessions**, or until `ojp.server.gracefulShutdown.drainTimeoutSeconds` (default 20 s) has passed.
4. **TERMINATING** – `server.shutdown()` is called. In-flight gRPC calls get up to `ojp.server.gracefulShutdown.terminationTimeoutSeconds` (default 5 s) to finish, then `shutdownNow()` cancels the rest.
5. Sessions still open are **rolled back and closed**. Each one is logged (session id, client id, XA or not) so you can see what was cut off.
6. All connection pools are closed (regular, read replica and XA pools), so database connections are released cleanly instead of being dropped.
7. The SQL enhancer, the session cleanup executor and the gRPC executor are stopped.

Set `ojp.server.gracefulShutdown.enabled=false` to skip steps 1 and 3 and stop right away, like older versions did.

### Settings

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `ojp.server.gracefulShutdown.enabled` | `OJP_SERVER_GRACEFULSHUTDOWN_ENABLED` | `true` | Turn draining on or off. |
| `ojp.server.gracefulShutdown.drainTimeoutSeconds` | `OJP_SERVER_GRACEFULSHUTDOWN_DRAINTIMEOUTSECONDS` | `20` | Maximum wait for open sessions to finish. |
| `ojp.server.gracefulShutdown.terminationTimeoutSeconds` | `OJP_SERVER_GRACEFULSHUTDOWN_TERMINATIONTIMEOUTSECONDS` | `5` | Wait for in-flight gRPC calls after the drain. |

The total worst-case shutdown time is roughly `drainTimeoutSeconds + terminationTimeoutSeconds` plus a few seconds to close pools and executors.

## What the driver does

The driver always uses the multinode code path, even with a single server in the URL.

| Situation | Driver behaviour |
|---|---|
| A request **without a session** gets the draining error | The server is marked **draining**, and the request is sent again to another healthy server. This is safe because the draining server rejects the request before running any SQL. |
| A request **with a session** on a draining server | Nothing changes. The session stays on its server so the transaction can finish and commit. |
| Draining server, XA sessions | XA sessions are **not** invalidated and the channel is **not** closed, so `prepare`/`commit` can still reach the server. |
| Other servers | They are told the new cluster health (the draining server no longer counts as healthy) so they can grow their pools. |
| Heartbeat while draining | Returns "draining" and the server stays excluded from new work. |
| Server is gone (connection refused) | The existing failure handling takes over: the server is marked unhealthy and its sessions are invalidated. |
| Server restarts | The heartbeat succeeds again. The driver rebuilds the pools on it, marks it healthy (this clears the draining flag) and redistributes connections as it already does after any recovery. |
| `UNAVAILABLE` **without** the draining trailer | Same as before. The request is **not** retried automatically, because the driver cannot know whether the server already ran it. |

**Older drivers** talking to a new server simply see `UNAVAILABLE` and react as they did before (they mark the server unhealthy). **New drivers** talking to an older server never see the trailer and also behave as before. No protocol change is needed in either direction.

## Kubernetes

Kubernetes sends `SIGTERM`, waits `terminationGracePeriodSeconds` (default **30 s**), and then sends `SIGKILL`. The defaults (20 s drain + 5 s termination) are chosen to fit inside 30 s.

Recommendations:

- Keep `terminationGracePeriodSeconds` **larger** than `drainTimeoutSeconds + terminationTimeoutSeconds + 5`. If you raise the drain timeout to 60 s, set the grace period to about 75 s.
- Use a gRPC readiness probe on the OJP server port. The default (empty) health service name is the OJP server status, which switches to `NOT_SERVING` as soon as draining starts.
- Optionally add a short `preStop` sleep (for example 5 s). It gives Service endpoints and load balancers time to remove the pod before draining starts. The `preStop` time counts against `terminationGracePeriodSeconds`.
- Run at least two replicas and use a `PodDisruptionBudget` (for example `maxUnavailable: 1`) so that rolling updates never stop all OJP servers at once.
- List every OJP server in the JDBC URL, for example `jdbc:ojp[ojp-0:1059,ojp-1:1059]_postgresql://db:5432/app`.

```yaml
spec:
  terminationGracePeriodSeconds: 35
  containers:
    - name: ojp-server
      image: rrobetti/ojp:<version>
      env:
        - name: OJP_SERVER_GRACEFULSHUTDOWN_DRAINTIMEOUTSECONDS
          value: "20"
        - name: OJP_SERVER_GRACEFULSHUTDOWN_TERMINATIONTIMEOUTSECONDS
          value: "5"
      lifecycle:
        preStop:
          exec:
            command: ["sleep", "5"]
      readinessProbe:
        grpc:
          port: 1059
```

## Docker

`docker stop` sends `SIGTERM` and waits only **10 seconds** before `SIGKILL`. Give it more time, for example `docker stop -t 30 <container>`, or set `stop_grace_period: 30s` in Docker Compose.

## Limits and things to know

- **Single server**: with only one OJP server in the URL, there is nowhere else to send new requests. They fail with a `SQLTransientConnectionException` (SQLState `08001`) during the drain and until the server is back. Zero-downtime restarts need at least two servers.
- **Long-lived sessions** (for example XA connections held by an application-side transaction manager, or connections left open with `autoCommit=false`) keep the server in the drain until the drain timeout. They are then rolled back and closed. Keep transactions short, or raise the drain timeout and the grace period together.
- The drain only waits for **sessions**. Requests without a session that are already running when draining starts finish during the termination step (`terminationTimeoutSeconds`).

## Testing

- Unit tests: `ShutdownCoordinatorTest`, `GracefulShutdownHandlerTest`, `SessionConnectionHelperDrainingTest` and `GrpcExceptionHandlerTest` in `ojp-server`; `ServerDrainingFailoverTest` in `ojp-jdbc-driver`.
- End-to-end test with two OJP containers (`GracefulShutdownMultinodeIntegrationTest` in `ojp-jdbc-driver`). It opens a long transaction, sends steady traffic, sends `SIGTERM` to one server, and checks that the transaction commits, the traffic moves to the other server without errors, and the restarted server is used again. It needs Docker and a locally built image:

```bash
cd ojp-server && bash download-drivers.sh && cd ..
mvn clean install -DskipTests -Dgpg.skip=true
docker build -t rrobetti/ojp:graceful-local ojp-server
mvn test -pl ojp-jdbc-driver -Dtest=GracefulShutdownMultinodeIntegrationTest \
    -DenableGracefulShutdownTests=true -Dojp.image.version=graceful-local
```
