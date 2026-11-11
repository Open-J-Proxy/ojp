# Autonomous Database Testing Issues

This directory contains three detailed issue descriptions for implementing autonomous testing workflows for proprietary databases in the OJP (Open J Proxy) project.

## Overview

Currently, Oracle, SQL Server, and DB2 tests are disabled in the Main CI workflow because OJP cannot keep their JDBC drivers in the repository due to licensing requirements. These issues describe how to implement separate GitHub Actions workflows that will:

1. Dynamically inject the required JDBC drivers during CI execution
2. Set up the database services with proper configuration
3. Run comprehensive tests for each proprietary database
4. Operate autonomously without affecting the main CI workflow

## Issue Files

### 1. Oracle Database Testing
**File:** `issue-oracle-autonomous-testing.md`  
**Complexity:** Medium  
**Key Points:**
- Uses `gvenzl/oracle-xe:21-slim` Docker image
- Oracle JDBC driver: `com.oracle.database.jdbc:ojdbc11:23.8.0.25.04`
- Startup time: 2-5 minutes
- Requires health check with proper retries
- Tests connection to XEPDB1 pluggable database
- 11 test classes covering Oracle-specific functionality

### 2. SQL Server Database Testing
**File:** `issue-sqlserver-autonomous-testing.md`  
**Complexity:** Medium to High  
**Key Points:**
- Uses `mcr.microsoft.com/mssql/server:2022-latest` image
- SQL Server JDBC driver: `com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11`
- Startup time: 3-5 minutes
- **Requires database initialization step** to create `defaultdb` and `testuser`
- Special LOB handling requirements (documented)
- 12 test classes covering SQL Server-specific functionality

### 3. IBM DB2 Database Testing
**File:** `issue-db2-autonomous-testing.md`  
**Complexity:** High  
**Key Points:**
- Uses `ibmcom/db2:11.5.8.0` image
- DB2 JDBC driver: `com.ibm.db2:jcc:11.5.9.0`
- Startup time: **15+ minutes** (longest of all databases)
- **Requires privileged Docker mode** and **6GB RAM**
- Extended startup wait with polling required
- Special LOB and ResultSetMetadata handling requirements
- 12 test classes covering DB2-specific functionality
- Performance optimization suggestions included

## Common Implementation Pattern

All three workflows follow the same pattern based on the existing `main.yml`:

1. **Placeholder Comment in pom.xml**
   - Add a comment in `ojp-server/pom.xml` after PostgreSQL dependency
   - Location for CI workflows to inject proprietary drivers
   - Keeps repository license-clean

2. **Workflow Structure**
   - Based on `.github/workflows/main.yml`
   - Same Java version matrix (11, 17, 21, 22)
   - Database service configuration
   - Driver injection step
   - Build and test steps

3. **Driver Injection**
   - Use `sed` to insert driver dependency after placeholder comment
   - Verify insertion before build
   - Only happens during CI execution, never committed

4. **Test Execution**
   - Build ojp-grpc-commons with JDK 22
   - Build ojp-server with JDK 22 (now includes proprietary driver)
   - Run ojp-server as background process
   - Switch to matrix JDK version
   - Build ojp-jdbc-driver
   - Run tests with `-Denable{Database}Tests` flag

## Key Differences Between Databases

| Database   | Startup Time | Special Requirements | Complexity | Resource Usage |
|-----------|--------------|----------------------|------------|----------------|
| Oracle    | 2-5 min      | Health checks        | Medium     | Normal         |
| SQL Server| 3-5 min      | DB initialization    | Medium-High| Normal         |
| DB2       | 15+ min      | Privileged mode, 6GB | High       | Very High      |

## Implementation Order Recommendation

1. **Start with Oracle** - Simplest implementation, good for testing the pattern
2. **Then SQL Server** - Adds database initialization complexity
3. **Finally DB2** - Most complex, benefits from lessons learned

## Testing Guides Referenced

Each issue references the corresponding testing guide:
- [Oracle Testing Guide](https://github.com/Open-J-Proxy/ojp/blob/main/documents/environment-setup/oracle-testing-guide.md)
- [SQL Server Testing Guide](https://github.com/Open-J-Proxy/ojp/blob/main/documents/environment-setup/sqlserver-testing-guide.md)
- [DB2 Testing Guide](https://github.com/Open-J-Proxy/ojp/blob/main/documents/environment-setup/db2-testing-guide.md)

## Performance Considerations

### Oracle
- Can run on every PR/push
- Relatively quick startup
- Normal resource consumption

### SQL Server
- Can run on every PR/push
- Moderate startup time
- Requires database initialization
- Normal resource consumption

### DB2
- **Consider scheduling** (nightly builds) instead of every PR
- Very long startup time
- High memory requirements (6GB)
- May strain CI runner resources
- Suggested optimizations included in issue

## Benefits

Once implemented, these workflows will:
- ✅ Provide continuous testing for proprietary databases
- ✅ Keep the repository license-clean (no proprietary drivers committed)
- ✅ Catch database-specific issues early
- ✅ Allow parallel development without license concerns
- ✅ Provide status badges for each database
- ✅ Enable confident refactoring with full test coverage

## Next Steps

1. Create GitHub issues using these descriptions (copy content from each .md file)
2. Assign appropriate labels (e.g., `enhancement`, `ci/cd`, `testing`)
3. Set priorities based on database importance to your users
4. Implement workflows in recommended order
5. Add workflow status badges to README.md once working

## Questions?

These issues are comprehensive and ready to be copied into GitHub's issue tracker. Each contains:
- Clear summary and background
- Detailed implementation steps with code examples
- Acceptance criteria
- Test classes to validate
- Special considerations and gotchas
- Related documentation links
- Estimated effort assessment

The implementation should be straightforward following these guides!
