# Multiple Choice Question Template

Use this template for single-answer multiple choice questions.

---

## Question [Number]: [Brief Descriptive Title]

**Difficulty**: [Easy/Medium/Hard]  
**Type**: Multiple Choice  
**Category**: [Foundation/Configuration/Advanced Features/Operations/Development]  
**Topic**: [Specific topic, e.g., "Server Configuration", "JDBC URL Format"]  
**Reference**: [eBook Chapter X: Title, Section X.X]

**Question:**
[Write your question here. Be clear and specific. Avoid ambiguous wording.]

**Options:**
A) [First option]
B) [Second option]
C) [Third option]
D) [Fourth option]
[E) Fifth option - if needed]

**Correct Answer:** [Letter of correct answer]

**Explanation:**
[Provide a detailed explanation of why the correct answer is right. Include key concepts and additional context that helps learning. 2-4 sentences typically.]

**Distractor Analysis:**
- A) [If wrong, explain why this option is incorrect or what misconception it represents]
- B) [Explain why incorrect]
- C) [Explain why incorrect]
- D) [Explain why incorrect]
[- E) Explain if applicable]

**Tags**: #category #difficulty #topic #[additional-relevant-tags]

---

## Example: Well-Written Multiple Choice Question

## Question 42: Default Connection Pool Provider

**Difficulty**: Easy  
**Type**: Multiple Choice  
**Category**: Configuration  
**Topic**: Connection Pool Provider  
**Reference**: Chapter 12: Connection Pool Provider SPI, Section 12.1

**Question:**
Which connection pool provider does OJP use by default if no custom provider is specified?

**Options:**
A) Apache Commons DBCP2
B) C3P0
C) HikariCP
D) Tomcat JDBC Pool

**Correct Answer:** C

**Explanation:**
OJP uses HikariCP as its default connection pool provider because of its excellent performance characteristics, minimal overhead, and reliability. HikariCP is widely adopted in the Java ecosystem and provides efficient connection management. While OJP supports custom pool providers through the SPI, HikariCP is automatically used when no alternative is configured.

**Distractor Analysis:**
- A) Apache Commons DBCP2 is supported via the SPI but not the default
- B) C3P0 is not currently supported by OJP
- D) Tomcat JDBC Pool is not currently supported by OJP

**Tags**: #configuration #easy #connection-pool #hikaricp #defaults

---

## Tips for Writing Multiple Choice Questions

### Do's:
- ✅ Use 4-5 options (4 is most common)
- ✅ Make all options grammatically parallel
- ✅ Keep options similar in length
- ✅ Order options logically (alphabetically, numerically) when possible
- ✅ Make distractors plausible to someone without full knowledge
- ✅ Test one clear concept

### Don'ts:
- ❌ Use "all of the above" or "none of the above"
- ❌ Include obvious throwaway options
- ❌ Use absolute terms like "always" or "never" unless factually accurate
- ❌ Make one option significantly longer than others (often reveals the answer)
- ❌ Use trick questions or overly complex language
- ❌ Include opinion-based questions

### Common Mistakes to Avoid:

**Mistake 1: Ambiguous question**
❌ "What should you configure in OJP?"
✅ "What property configures the OJP server's gRPC port?"

**Mistake 2: Obvious wrong answers**
❌ Options include "delete all data" or "do nothing"
✅ All options should be contextually reasonable

**Mistake 3: Multiple correct answers**
❌ Two options could both be considered correct
✅ Only ONE clearly correct answer (or use Multiple Select type)

**Mistake 4: Testing recall of obscure details**
❌ "In what year was HikariCP first released?"
✅ "What is the primary benefit of using HikariCP in OJP?"

---

**Template Version**: 1.0  
**Last Updated**: 2026-02-09
