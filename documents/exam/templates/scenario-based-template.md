# Scenario-Based Question Template

Use this template for questions that present a realistic scenario and ask for the best solution or approach.

---

## Question [Number]: [Brief Descriptive Title]

**Difficulty**: [Medium/Hard - Scenarios are typically not Easy]  
**Type**: Scenario-Based  
**Category**: [Foundation/Configuration/Advanced Features/Operations/Development]  
**Topic**: [Specific topic]  
**Reference**: [eBook Chapter X: Title, Section X.X]

**Scenario:**
[Describe the scenario in 2-5 sentences. Include:
- Context (what system/environment)
- Current situation
- Problem or goal
- Relevant constraints
- Any specific requirements]

**Question:**
[Ask what should be done, what is the best approach, what is the likely cause, etc.]

**Options:**
A) [First solution/approach]
B) [Second solution/approach]
C) [Third solution/approach]
D) [Fourth solution/approach]

**Correct Answer:** [Letter]

**Explanation:**
[Explain why this is the best solution for this scenario. Address:
- Why it solves the problem
- How it addresses the constraints
- What benefits it provides
- Any additional considerations]

**Distractor Analysis:**
- A) [Explain why this approach is suboptimal or incorrect]
- B) [Explain why this won't work or isn't best]
- C) [Explain the issue with this approach]
- D) [Explain why this is not ideal]

**Tags**: #category #difficulty #scenario #topic #[additional-tags]

---

## Example: Well-Written Scenario Question

## Question 78: High Connection Usage During Traffic Spike

**Difficulty**: Medium  
**Type**: Scenario-Based  
**Category**: Advanced Features  
**Topic**: Slow Query Segregation  
**Reference**: Chapter 8: Slow Query Segregation, Section 8.2

**Scenario:**
Your e-commerce application uses OJP to manage connections to a PostgreSQL database. During traffic spikes, you notice that analytical reports (which take 30-60 seconds to execute) are consuming most of the available connections, causing fast transactional queries (which complete in <100ms) to timeout. The application has a mix of read-heavy reporting and write-heavy transactional workloads. You need to ensure that slow analytical queries don't starve fast transactional queries of connections.

**Question:**
What is the best OJP feature to address this issue?

**Options:**
A) Increase the total connection pool size to accommodate both workload types
B) Enable slow query segregation to separate fast and slow queries into different connection slots
C) Configure multinode deployment to distribute the load across multiple servers
D) Implement query timeout limits to kill long-running analytical queries

**Correct Answer:** B

**Explanation:**
Slow query segregation is specifically designed for this scenario. It automatically monitors query execution times and classifies operations as fast or slow, then allocates separate connection slots for each category. This prevents slow analytical queries from monopolizing all available connections, ensuring that fast transactional queries always have access to connections. The feature learns and adapts over time, making it ideal for mixed workloads.

**Distractor Analysis:**
- A) Simply increasing pool size would provide more connections but wouldn't prevent slow queries from consuming all of them, and would put additional pressure on the database
- C) Multinode deployment addresses scalability and high availability but doesn't solve the connection starvation problem caused by query duration differences
- D) Killing long-running analytical queries defeats their purpose; the goal is to run both workload types successfully, not prevent the slow queries from completing

**Tags**: #advanced-features #medium #scenario #slow-query-segregation #performance #troubleshooting

---

## Example: Complex Hard-Level Scenario

## Question 156: Multinode XA Transaction Failure

**Difficulty**: Hard  
**Type**: Scenario-Based  
**Category**: Advanced Features  
**Topic**: XA Transactions, Multinode  
**Reference**: Chapter 10: XA Transactions, Section 10.3

**Scenario:**
You have a three-node OJP cluster (nodes A, B, C) serving a microservices application that requires XA distributed transactions. A client starts an XA transaction on node A, which successfully prepares the transaction. Before the transaction can be committed, node A experiences a hardware failure and becomes unavailable. The client's connection fails over to node B. When the client attempts to commit the transaction, it receives an error indicating the transaction cannot be found.

**Question:**
What is the root cause of this issue, and what should have been configured to prevent it?

**Options:**
A) XA transactions are not session-sticky; the transaction should have been configured with per-endpoint datasources to enable recovery from any node
B) XA transactions are session-bound to the originating server; session stickiness should have been configured to prevent mid-transaction failover
C) The client should have used 2-phase commit protocol explicitly; OJP doesn't handle XA transaction recovery automatically
D) The connection pool size on node B was too small; increasing the pool size would allow transaction recovery

**Correct Answer:** B

**Explanation:**
XA transactions in OJP are session-bound, meaning they must be completed on the same OJP server instance where they started. When node A failed, the transaction state was lost because the client failed over to node B. To handle this scenario properly, the application should implement session stickiness at the load balancer level to ensure the same client always connects to the same OJP node during a transaction. Alternatively, the application should implement transaction retry logic with a new transaction after failover, accepting that the original transaction was lost.

**Distractor Analysis:**
- A) While per-endpoint datasources are useful, they don't solve the session-bound nature of XA transactions in OJP's current architecture
- C) OJP does handle 2-phase commit protocol; the issue is not with the protocol but with the session binding
- D) Pool size is unrelated to transaction recovery; the transaction state was lost when the node failed

**Tags**: #advanced-features #hard #scenario #xa-transactions #multinode #high-availability #troubleshooting

---

## Tips for Writing Scenario Questions

### Scenario Design:
- ✅ Use realistic, production-like situations
- ✅ Include relevant technical details
- ✅ Provide sufficient context
- ✅ Make the problem clear
- ✅ Include constraints or requirements
- ✅ Keep scenarios concise (5-8 sentences max)

### Solution Options:
- ✅ All options should be technically possible
- ✅ Incorrect options should represent common approaches or misconceptions
- ✅ Test decision-making and judgment
- ✅ Focus on "best" solution, not just "correct"

### What to Test:
- Problem diagnosis
- Solution design
- Trade-off analysis
- Best practices application
- Troubleshooting approach
- Architecture decisions

### What to Avoid:
- ❌ Unrealistic scenarios
- ❌ Missing critical context
- ❌ Obviously wrong solutions
- ❌ Testing obscure edge cases
- ❌ Scenarios requiring information not in eBook

---

**Template Version**: 1.0  
**Last Updated**: 2026-02-09
