# OJP Certification Exam - Question Writing Guidelines

## Purpose

This document provides comprehensive guidelines for writing high-quality certification questions for the Open-J-Proxy (OJP) exam. Following these guidelines ensures consistency, fairness, and effectiveness of the assessment.

## Core Principles

### 1. Clarity
- Use clear, unambiguous language
- Avoid double negatives
- One clear question per item
- Precise terminology matching OJP documentation

### 2. Relevance
- Test meaningful knowledge and skills
- Focus on practical, real-world scenarios
- Avoid trivial or obscure details
- Align with job tasks and actual usage

### 3. Fairness
- No trick questions
- No unnecessarily complex language
- Appropriate difficulty for target audience
- Culturally neutral content

### 4. Accuracy
- Technically correct information
- Matches current OJP version
- Verified against documentation
- Tested configurations/code

## Question Structure Requirements

Every question must include these components:

### Header Section
```markdown
## Question [Number]: [Brief Descriptive Title]

**Difficulty**: [Easy/Medium/Hard]
**Type**: [Multiple Choice/Multiple Select/Scenario/Code Review/Fill-in-Blank]
**Category**: [Foundation/Configuration/Advanced Features/Operations/Development]
**Topic**: [Specific topic, e.g., "Multinode Deployment", "JDBC Configuration"]
**Reference**: [Chapter X: Title, Section X.X]
```

### Question Body
- Clear question statement
- Sufficient context (for scenarios)
- No ambiguous phrasing
- Appropriate length (not too verbose)

### Answer Options
- Logically ordered when possible
- Similar length and structure
- Plausible distractors (wrong answers)
- No "all of the above" or "none of the above" unless carefully justified

### Explanation Section
- Why the correct answer is right
- Key concepts reinforced
- Common misconceptions addressed
- Additional learning points

### Distractor Analysis
- Why each incorrect option is wrong
- Common mistakes explained
- Learning opportunity from distractors

### Tags
- Facilitate filtering and organization
- Include all relevant descriptors
- Format: #category #difficulty #topic

## Question Types

### 1. Multiple Choice (Single Answer)

**When to Use**:
- Testing specific knowledge
- Clear correct answer exists
- Basic to medium difficulty

**Best Practices**:
- 4-5 answer options
- All options grammatically parallel
- Distractors represent common errors
- Avoid "always" or "never" unless accurate

**Example**:
```markdown
## Question 1: Default Server Port

**Difficulty**: Easy
**Type**: Multiple Choice
**Category**: Configuration
**Topic**: Server Configuration
**Reference**: Chapter 6: Server Configuration, Section 6.1

**Question:**
What is the default gRPC port used by the OJP server?

**Options:**
A) 1058
B) 1059
C) 8080
D) 9090

**Correct Answer:** B

**Explanation:**
The OJP server uses port 1059 as the default gRPC port. This can be changed using the `ojp.server.port` system property or the `OJP_SERVER_PORT` environment variable.

**Distractor Analysis:**
- A) 1058 might be confused with the default port, but is not correct
- C) 8080 is a common HTTP port but not used by OJP by default
- D) 9090 is commonly used for metrics/Prometheus, not the gRPC server

**Tags**: #configuration #easy #server #ports
```

### 2. Multiple Select (Multiple Correct Answers)

**When to Use**:
- Testing comprehensive understanding
- Multiple valid approaches exist
- Assessing ability to identify all correct options

**Best Practices**:
- 5-7 answer options
- 2-4 correct answers typically
- Clear instruction: "Select all that apply"
- Avoid making all options correct

**Example**:
```markdown
## Question 2: Open Source Database Drivers

**Difficulty**: Easy
**Type**: Multiple Select
**Category**: Configuration
**Topic**: Database Drivers
**Reference**: Chapter 4: Database Drivers, Section 4.1

**Question:**
Which of the following database drivers are automatically downloaded by the OJP `download-drivers.sh` script? (Select all that apply)

**Options:**
A) PostgreSQL
B) MySQL
C) Oracle
D) H2
E) MariaDB
F) SQL Server
G) DB2

**Correct Answers:** A, B, D, E

**Explanation:**
The OJP download-drivers.sh script automatically downloads open source JDBC drivers including PostgreSQL, MySQL, H2, and MariaDB. Proprietary database drivers like Oracle, SQL Server, and DB2 must be downloaded separately due to licensing restrictions.

**Distractor Analysis:**
- C) Oracle is a proprietary database requiring manual driver installation
- F) SQL Server is proprietary and requires manual driver setup
- G) DB2 is proprietary and requires manual configuration

**Tags**: #configuration #easy #drivers #database
```

### 3. Scenario-Based Questions

**When to Use**:
- Testing application of knowledge
- Real-world problem-solving
- Medium to hard difficulty

**Best Practices**:
- Provide realistic scenario
- Include relevant context
- Ask for best solution/approach
- May include constraints or requirements

**Example**:
```markdown
## Question 3: Spring Boot Connection Pool Conflict

**Difficulty**: Medium
**Type**: Scenario-Based
**Category**: Configuration
**Topic**: Framework Integration
**Reference**: Chapter 7: Framework Integration, Section 7.1

**Question:**
You are integrating OJP with a Spring Boot application that previously used HikariCP for connection pooling. After adding the OJP JDBC driver and updating the connection URL, you notice that database connections are being pooled twice - once by Spring Boot's HikariCP and once by OJP server.

What should you do to resolve this issue?

**Options:**
A) Remove the HikariCP dependency from your pom.xml
B) Set `spring.datasource.type=org.openjproxy.jdbc.OjpDataSource` in application.properties
C) Configure both pools to use the same maximum connections
D) Add `spring.datasource.hikari.maximum-pool-size=0` to disable HikariCP pooling

**Correct Answer:** D

**Explanation:**
When using OJP with Spring Boot, you should disable Spring Boot's built-in connection pooling since OJP handles pooling on the server side. The correct approach is to set the HikariCP maximum pool size to 0 (or 1) to prevent double pooling. This allows Spring Boot's DataSource to work but prevents it from maintaining its own connection pool.

Alternative valid approach: Set `spring.datasource.type` to use a simple DataSource implementation rather than HikariCP.

**Distractor Analysis:**
- A) Removing HikariCP dependency may cause Spring Boot auto-configuration issues
- B) OjpDataSource is not a standard Spring Boot DataSource type
- C) Configuring both pools defeats the purpose of OJP and wastes resources

**Tags**: #configuration #medium #spring-boot #framework-integration #troubleshooting
```

### 4. Code Review Questions

**When to Use**:
- Testing ability to identify issues
- Configuration validation
- Medium to hard difficulty

**Best Practices**:
- Provide realistic code/configuration
- Include 1-3 issues to identify
- Issues should be common mistakes
- Code should be syntactically correct (except for the issues)

**Example**:
```markdown
## Question 4: Multinode URL Configuration Issue

**Difficulty**: Medium
**Type**: Code Review
**Category**: Advanced Features
**Topic**: Multinode Deployment
**Reference**: Chapter 9: Multinode Deployment, Section 9.2

**Question:**
Review the following JDBC URL for a multinode OJP deployment. Identify what is wrong with this configuration:

```java
String url = "jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost:5432/mydb";
```

**Options:**
A) The port numbers should be different for each server
B) The database host should not be localhost when using multinode
C) The OJP server addresses are correct, but session stickiness is not configured
D) There is no issue with this configuration

**Correct Answer:** B

**Explanation:**
When using multinode OJP deployment, the database host in the underlying JDBC URL should be accessible from all OJP servers, not localhost. If the database is on localhost, each OJP server would try to connect to its own local database rather than a shared database. The configuration should specify the actual database server address that all OJP nodes can reach.

Correct example:
```java
String url = "jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://db-server.example.com:5432/mydb";
```

**Distractor Analysis:**
- A) All OJP servers typically use the same gRPC port (1059), so this is correct
- C) Session stickiness is handled automatically by OJP and doesn't need URL configuration
- D) There is definitely an issue as explained above

**Tags**: #advanced-features #medium #multinode #configuration #troubleshooting
```

### 5. Fill-in-the-Blank Questions

**When to Use**:
- Testing specific syntax knowledge
- Configuration parameters
- Easy to medium difficulty

**Best Practices**:
- Clear context provided
- Exact answer required (specify if case-sensitive)
- May allow variations if noted
- Use for URLs, commands, property names

**Example**:
```markdown
## Question 5: JDBC URL Prefix

**Difficulty**: Easy
**Type**: Fill-in-the-Blank
**Category**: Foundation
**Topic**: JDBC URL Format
**Reference**: Chapter 3: Quick Start, Section 3.3

**Question:**
To use the OJP JDBC driver with a PostgreSQL database, you must prefix the standard PostgreSQL JDBC URL with `jdbc:___________[host:port]_`

(Fill in the blank with the OJP prefix. Answer is case-sensitive.)

**Correct Answer:** ojp

**Explanation:**
The OJP JDBC driver uses the prefix `ojp` followed by the server location in square brackets. For example:
- Standard: `jdbc:postgresql://localhost/mydb`
- With OJP: `jdbc:ojp[localhost:1059]_postgresql://localhost/mydb`

The prefix identifies the driver and specifies where the OJP server is running.

**Tags**: #foundation #easy #jdbc-url #syntax
```

## Difficulty Level Guidelines

### Easy Questions

**Characteristics**:
- Single concept tested
- Direct recall from documentation
- Clear, unambiguous answer
- No complex reasoning required

**Example Topics**:
- Definitions and terminology
- Default configurations
- Basic syntax
- Simple true/false statements
- Component identification

**Cognitive Levels** (Bloom's Taxonomy):
- Remember: Recall facts, terms, concepts
- Understand: Explain ideas or concepts

**Time to Answer**: 30-60 seconds

### Medium Questions

**Characteristics**:
- Multiple concepts combined
- Application to scenarios
- Analysis required
- Some problem-solving

**Example Topics**:
- Configuration for specific use cases
- Troubleshooting common issues
- Comparing approaches
- Identifying problems in code
- Best practices application

**Cognitive Levels**:
- Apply: Use information in new situations
- Analyze: Draw connections among ideas

**Time to Answer**: 1-2 minutes

### Hard Questions

**Characteristics**:
- Complex scenarios
- Multiple variables
- Synthesis of concepts
- Expert-level reasoning
- Design decisions

**Example Topics**:
- Architecting complex deployments
- Advanced troubleshooting
- Performance optimization
- Custom implementations
- Trade-off analysis

**Cognitive Levels**:
- Evaluate: Justify decisions or courses of action
- Create: Produce new or original work

**Time to Answer**: 2-4 minutes

## Common Pitfalls to Avoid

### 1. Ambiguous Questions
❌ **Bad**: "What should you do when using OJP?"
✅ **Good**: "What is the first step to integrate OJP with an existing Spring Boot application?"

### 2. Trick Questions
❌ **Bad**: "OJP does NOT require which of the following?" (double negative)
✅ **Good**: "Which of the following is optional when deploying OJP?"

### 3. "All of the Above"
❌ **Bad**: Using "all of the above" as an option makes questions too easy
✅ **Good**: Make each option independently evaluable

### 4. Overly Specific Details
❌ **Bad**: "In which line of the OJP server source code is the default port defined?"
✅ **Good**: "What is the default port used by the OJP server?"

### 5. Opinion-Based Questions
❌ **Bad**: "Which connection pool is best for production use?"
✅ **Good**: "What is the default connection pool provider used by OJP?"

### 6. Dependent Questions
❌ **Bad**: Having Question 15's answer depend on Question 14
✅ **Good**: Each question stands alone

### 7. Obvious Answers
❌ **Bad**: Options like "delete all your data" or "crash the server"
✅ **Good**: All options should be plausible to someone without deep knowledge

## Writing Effective Distractors

**Good distractors** (incorrect options):
- Represent common misconceptions
- Are plausible to those who don't fully understand
- Test specific knowledge gaps
- Are similar in structure to correct answer

**Examples of effective distractors**:
- Similar-sounding configuration properties
- Common error values
- Partially correct approaches
- Previous version syntax
- Related but incorrect concepts

## Question Review Checklist

Before submitting a question, verify:

- [ ] Question is clear and unambiguous
- [ ] Only one correct answer (or clearly specified multiple correct answers)
- [ ] All options are plausible
- [ ] Distractors represent common errors
- [ ] Explanation is complete and educational
- [ ] eBook reference is accurate and specific
- [ ] Difficulty level is appropriate
- [ ] Code examples are syntactically correct
- [ ] No typos or grammatical errors
- [ ] Tags are complete and accurate
- [ ] Follows template structure exactly
- [ ] Tested/verified if contains code or configuration

## Accessibility Considerations

- Use clear, simple language
- Avoid idioms or culture-specific references
- Ensure code examples have proper formatting
- Provide sufficient context
- Use standard terminology
- Avoid time-pressured language ("quickly", "immediately")

## Version Control

Questions should be tagged with the OJP version they're written for:
- **Version Tag**: Include in metadata
- **Deprecation**: Mark questions as outdated when OJP changes
- **Updates**: Review questions quarterly for accuracy

## Maintenance

Questions require regular review when:
- New OJP version releases
- eBook content changes
- Community identifies issues
- Pass rates are too high/low
- Questions are confusing to test-takers

---

**Document Version**: 1.0  
**Last Updated**: 2026-02-09  
**Next Review**: 2026-05-09
