# Troubleshooting Question Template

Use this template for questions that test ability to diagnose and resolve problems.

---

## Question [Number]: [Brief Descriptive Title]

**Difficulty**: [Medium/Hard - Troubleshooting is typically not Easy]  
**Type**: Troubleshooting  
**Category**: [Configuration/Advanced Features/Operations]  
**Topic**: [Specific topic]  
**Reference**: [eBook Chapter X: Title, Section X.X]

**Scenario:**
[Describe the problem situation:
- What the user is trying to do
- What's happening (symptoms)
- What they expected to happen
- Relevant environment/configuration details
- Error messages or logs if applicable]

**Question:**
What is the most likely cause of this issue?
[OR: What should be done first to troubleshoot this issue?]
[OR: What is the best way to resolve this issue?]

**Error Message/Logs (if applicable):**
```
[Include relevant error messages or log entries]
```

**Options:**
A) [First potential cause or solution]
B) [Second potential cause or solution]
C) [Third potential cause or solution]
D) [Fourth potential cause or solution]

**Correct Answer:** [Letter]

**Explanation:**
[Explain:
- Why this is the root cause
- How to diagnose it
- How to fix it
- Steps to prevent it in the future]

**Troubleshooting Steps:**
1. [First step to diagnose]
2. [Second step]
3. [How to verify the fix]

**Distractor Analysis:**
- A) [Why this is not the root cause or why this approach won't work]
- B) [Explain why this is unlikely or won't solve the problem]
- C) [Why this is not the issue]
- D) [Why this won't help]

**Tags**: #category #difficulty #troubleshooting #topic #[error-type]

---

## Example: Connection Failure Troubleshooting

## Question 134: Connection Refused Error

**Difficulty**: Medium  
**Type**: Troubleshooting  
**Category**: Configuration  
**Topic**: Connection Issues  
**Reference**: Chapter 15: Troubleshooting, Section 15.2

**Scenario:**
A developer is trying to connect their Java application to an OJP server running in a Docker container on the same machine. The OJP server logs show it started successfully on port 1059. However, when the application tries to connect, it immediately fails.

**Error Message:**
```
java.sql.SQLException: Failed to connect to OJP server at localhost:1059
Caused by: io.grpc.StatusRuntimeException: UNAVAILABLE: io exception
Caused by: java.net.ConnectException: Connection refused
```

**Question:**
What is the most likely cause of this connection failure?

**Options:**
A) The OJP server is not actually running despite the logs
B) The Docker container is using host network mode and there's a port conflict
C) The Docker container is not exposing port 1059 to the host
D) The application is using the wrong JDBC driver class

**Correct Answer:** C

**Explanation:**
The most common cause of "Connection refused" when connecting to a Docker container from the host is that the port is not properly exposed or mapped. The OJP server is running and listening on port 1059 inside the container, but that port isn't accessible from the host machine because it wasn't published when the container was started.

**Troubleshooting Steps:**
1. Check if the container was started with port mapping: `docker ps` and look for "0.0.0.0:1059->1059/tcp"
2. Restart the container with proper port mapping: `docker run -p 1059:1059 rrobetti/ojp:latest`
3. Verify the connection from the host: `telnet localhost 1059` or `nc -zv localhost 1059`
4. Check if the application can now connect

**Prevention:**
Always use `-p 1059:1059` when running OJP in Docker to expose the gRPC port.

**Distractor Analysis:**
- A) The server logs showing successful startup indicate it is running; the issue is network accessibility
- B) Host network mode would actually solve this issue by making container ports directly accessible; this is not the problem
- D) A wrong driver class would produce a different error (ClassNotFoundException or "No suitable driver"), not "Connection refused"

**Tags**: #configuration #medium #troubleshooting #docker #connection-refused #networking

---

## Example: Performance Troubleshooting

## Question 187: Slow Query Performance

**Difficulty**: Hard  
**Type**: Troubleshooting  
**Category**: Advanced Features  
**Topic**: Performance Issues  
**Reference**: Chapter 8: Slow Query Segregation, Chapter 13: Telemetry

**Scenario:**
A production application using OJP is experiencing intermittent slow response times. Monitoring shows that during certain hours, query latencies spike from an average of 50ms to over 5 seconds. The application has a mix of transactional queries (normally <100ms) and reporting queries (normally 2-10 seconds). The OJP server logs show no errors. The database server CPU and memory utilization are normal (<50%). Connection pool metrics show frequent connection wait times during the slow periods.

**Prometheus Metrics:**
```
ojp_connection_pool_active_connections{datasource="main"} = 20
ojp_connection_pool_max_connections{datasource="main"} = 20
ojp_connection_wait_time_ms{datasource="main"} = 4850
```

**Question:**
What is the most effective solution to this performance problem?

**Options:**
A) Increase the maximum connection pool size from 20 to 50
B) Enable slow query segregation to prevent slow queries from monopolizing connections
C) Add more OJP server instances in a multinode configuration
D) Increase the database server's max_connections setting

**Correct Answer:** B

**Explanation:**
The metrics and symptoms indicate classic connection starvation caused by slow reporting queries holding connections while fast transactional queries wait. The pool is maxed out (20/20 active) and queries are waiting nearly 5 seconds for connections. Slow query segregation is designed specifically for this scenario - it separates fast and slow queries into different connection slots, ensuring fast queries don't get stuck behind slow ones.

**Troubleshooting Steps:**
1. Enable slow query segregation: `-Dojp.slow.query.segregation.enabled=true`
2. Configure slot percentages: `-Dojp.slow.query.slot.percentage=30` (reserve 30% for slow queries)
3. Monitor the metrics to verify improvement
4. Adjust slot percentage based on workload patterns

Why other options are less effective:
- Increasing pool size (A) would help temporarily but doesn't solve the root cause - slow queries will still monopolize connections
- Multinode (C) adds complexity without addressing the connection allocation problem
- Database max_connections (D) is not the bottleneck - the OJP pool is the constraint

**Distractor Analysis:**
- A) While this provides more connections, it doesn't prevent slow queries from consuming all of them and puts more load on the database
- C) Multinode deployment addresses scalability and availability but doesn't solve connection segregation by query duration
- D) The database is not the bottleneck (CPU/memory are normal); adding database connections won't help if OJP pool is the constraint

**Tags**: #advanced-features #hard #troubleshooting #performance #slow-query-segregation #connection-pool

---

## Example: Multinode Troubleshooting

## Question 203: Uneven Load Distribution

**Difficulty**: Hard  
**Type**: Troubleshooting  
**Category**: Advanced Features  
**Topic**: Multinode Deployment  
**Reference**: Chapter 9: Multinode Deployment, Section 9.3

**Scenario:**
A three-node OJP cluster has been deployed (nodes A, B, C) with identical hardware and configuration. However, monitoring shows that node A is handling 80% of the traffic while nodes B and C are mostly idle. The application's JDBC URL is correctly configured with all three nodes. The application is deployed across 10 Kubernetes pods with proper scaling.

**Telemetry Data:**
```
Node A: 150 active connections, CPU 85%
Node B: 12 active connections, CPU 15%
Node C: 8 active connections, CPU 12%
```

**JDBC URL:**
```java
String url = "jdbc:ojp[node-a:1059,node-b:1059,node-c:1059]_postgresql://db:5432/app";
```

**Question:**
What is the most likely cause of this uneven load distribution?

**Options:**
A) The OJP server on node A is configured with a larger connection pool than nodes B and C
B) The application pods have session stickiness enabled and most were connected when only node A was available
C) The load-aware selection algorithm is not working; switching to round-robin would fix this
D) Nodes B and C have network latency issues causing clients to prefer node A

**Correct Answer:** B

**Explanation:**
The most likely cause is session stickiness combined with application deployment timing. If most application pods were deployed and connected to OJP when only node A was available (or before nodes B and C were ready), those connections would stick to node A due to OJP's session management for transaction consistency. New pods would distribute across all nodes, but existing pods remain stuck to node A.

**Troubleshooting Steps:**
1. Check application pod creation times vs OJP node availability
2. Verify this by rolling restart of application pods: `kubectl rollout restart deployment/app`
3. Monitor connection redistribution as pods reconnect
4. If issue persists, check if sessions are being held open unnecessarily

**Resolution:**
Perform a rolling restart of the application pods to force reconnection with proper load balancing. Future deployments should ensure all OJP nodes are available before deploying application pods.

**Long-term Prevention:**
- Implement proper pod disruption budgets
- Ensure coordinated startup (OJP nodes before application)
- Consider implementing connection recycling for long-lived application instances

**Distractor Analysis:**
- A) Pool size differences would affect capacity but not load distribution; clients choose servers based on load, not pool size
- C) OJP's load-aware selection is working correctly; the issue is session stickiness from initial connections
- D) Network latency significant enough to affect server selection would show up in connection establishment times, not cause 80% skew

**Tags**: #advanced-features #hard #troubleshooting #multinode #load-balancing #session-stickiness

---

## Tips for Writing Troubleshooting Questions

### Scenario Design:
- ✅ Use realistic production issues
- ✅ Include relevant symptoms and error messages
- ✅ Provide enough context to diagnose
- ✅ Include monitoring data or logs when relevant
- ✅ Test systematic troubleshooting approach

### Good Troubleshooting Questions:
- Present problems that practitioners actually encounter
- Test diagnostic reasoning, not just solution knowledge
- Include red herrings (symptoms that seem important but aren't)
- Teach troubleshooting methodology
- Focus on root causes, not just symptoms

### What to Test:
- Root cause analysis
- Diagnostic steps
- Solution selection
- Prevention strategies
- Use of monitoring/logs
- Systematic problem-solving

### Difficulty Calibration:
- **Medium**: Common issues with clear symptoms
- **Hard**: Complex issues, multiple potential causes, requires synthesis

### What to Avoid:
- ❌ Obvious solutions
- ❌ Issues with insufficient information to diagnose
- ❌ Problems requiring information outside the eBook
- ❌ Purely hypothetical issues that don't occur in practice

---

**Template Version**: 1.0  
**Last Updated**: 2026-02-09
