# Contributing to Open J Proxy (OJP)

Thank you for your interest in contributing to OJP! We welcome contributions of all kinds — code, documentation, testing, bug reports, and community support. This guide will help you get started.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Ways to Contribute](#ways-to-contribute)
- [Getting Started](#getting-started)
- [Development Workflow](#development-workflow)
- [Linking Issues to Pull Requests](#linking-issues-to-pull-requests)
- [Code Style and Conventions](#code-style-and-conventions)
- [Testing Requirements](#testing-requirements)
- [Pull Request Process](#pull-request-process)
- [Code Review Process](#code-review-process)
- [Contributor Recognition](#contributor-recognition)
- [Getting Help](#getting-help)

---

## Code of Conduct

We are committed to providing a welcoming and inclusive environment for all contributors. Please be respectful and professional in all interactions.

---

## Ways to Contribute

OJP values contributions across multiple tracks:

### 🔵 Code Contributions

- Fix bugs and implement new features
- Improve performance and refactor code
- Review pull requests and provide feedback

### 🟠 Documentation & Content

- Improve README files and setup guides
- Write tutorials and examples
- Create diagrams and visual aids
- Update API documentation

### 🟢 Testing & Quality

- Report bugs and validate fixes
- Add test coverage
- Improve CI/CD pipelines
- Conduct stress and performance testing

### 🟣 Evangelism & Community

- Write blog posts or articles
- Give talks or presentations
- Share OJP on social media
- Help others in discussions and Discord

For more details on recognition for these contributions, see our [Contributor Recognition Program](https://github.com/Open-J-Proxy/ojp/blob/main/documents/contributor-badges/contributor-recognition-program.md).

---

## Getting Started

### Prerequisites

- **Java 25 or higher**
- **Maven 3.9+**
- **Docker** (for running databases and OJP server)
- **Git** (for version control)

### Fork and Clone the Repository

1. **Fork the repository** on GitHub by clicking the "Fork" button at the top right of the [OJP repository](https://github.com/Open-J-Proxy/ojp).

2. **Clone your fork locally**:

   ```bash
   git clone https://github.com/YOUR-USERNAME/ojp.git
   cd ojp
   ```

3. **Add the upstream repository** as a remote:

   ```bash
   git remote add upstream https://github.com/Open-J-Proxy/ojp.git
   ```

4. **Keep your fork in sync** with the upstream repository:

   ```bash
   git fetch upstream
   git checkout main
   git merge upstream/main
   ```

### Download JDBC Drivers

> **⚠️ Required for v0.4.0-beta and later:** Download JDBC drivers before running OJP. See [Chapter 4: Database Drivers](../ebook/part2-chapter4-database-drivers.md) for comprehensive instructions.

OJP requires JDBC drivers to connect to databases. Open source drivers (H2, PostgreSQL, MySQL, MariaDB) are not embedded and must be downloaded:

```bash
cd ojp-server
bash download-drivers.sh
cd ..
```

This script downloads the drivers from Maven Central to `./ojp-libs/` at the repository root. For proprietary drivers (Oracle, DB2, SQL Server), see the specific database testing guides in `documents/environment-setup/`.

### Build the Project

Build the project to ensure everything is set up correctly:

```bash
mvn clean install -DskipTests
```

For detailed setup instructions, see [Source Code Developer Setup and Local Testing](https://github.com/Open-J-Proxy/ojp/blob/main/documents/code-contributions/setup_and_testing_ojp_source.md).

---

## Development Workflow

### 1. Create a Feature Branch in Your Fork

Always create a new branch in your fork for your work. Use a descriptive name that reflects what you're working on:

```bash
git checkout -b feature/add-connection-timeout
git checkout -b fix/null-pointer-exception
git checkout -b docs/improve-setup-guide
```

### 2. Make Your Changes

- Write clean, readable code that follows the project's conventions
- Add or update tests to cover your changes
- Update documentation if needed
- Keep commits focused and atomic

### 3. Test Your Changes

Before submitting a pull request, ensure your changes work correctly:

**Download drivers** (if not already done):

```bash
cd ojp-server
bash download-drivers.sh
cd ..
```

**Start the OJP server** (required for running tests):

```bash
mvn verify -pl ojp-server -Prun-ojp-server
```

**Run tests** (in a separate terminal, navigate to ojp-jdbc-driver):

```bash
cd ojp-jdbc-driver
mvn test -DenableH2Tests=true
```

**Note 1:** the OJP server must be running to execute the integration tests.

**Note 2**: By default, all database tests are disabled. Use the appropriate enable flags for specific databases:

- `-DenableH2Tests=true` - H2 database tests
- `-DenablePostgresTests=true` - PostgreSQL tests
- `-DenableMySQLTests=true` - MySQL tests
- `-DenableMariaDBTests=true` - MariaDB tests
- `-DenableCockroachDBTests=true` - CockroachDB tests
- `-DenableOracleTests=true` - Oracle tests (requires manual setup)
- `-DenableSqlServerTests=true` - SQL Server tests

For setting up local databases, see [Run Local Databases](https://github.com/Open-J-Proxy/ojp/blob/main/documents/environment-setup/run-local-databases.md).

**Running Tests from IDE:**

When running integration tests via IDEs against Docker containers of ojp-server, configure these JVM parameters in your IDE run configuration:

```
-Dfile.encoding=UTF-8 -Duser.timezone=UTC
```

For detailed IDE-specific configuration instructions, see the [Running Integration Tests from IDE](https://github.com/Open-J-Proxy/ojp/blob/main/documents/code-contributions/setup_and_testing_ojp_source.md#running-integration-tests-from-ide) section in the setup guide.

### 4. Commit Your Changes

Write clear, descriptive commit messages:

```bash
git add .
git commit -m "Add connection timeout configuration to JDBC driver"
```

**Good commit message examples**:

- `Fix null pointer exception in ResultSet.getString()`
- `Add support for PostgreSQL array types`
- `Update README with Docker installation instructions`
- `Improve error handling in connection pool`

**Avoid**:

- `Fixed stuff`
- `Update`
- `WIP`

### 5. Push to Your Fork

```bash
git push origin feature/add-connection-timeout
```

---

## Linking Issues to Pull Requests

**This is critical**: Properly linking your PR to an issue ensures that the issue is automatically closed when your PR is merged.

### Using GitHub Keywords

GitHub automatically links and closes issues when you use specific keywords in your PR description or commit messages. Use one of the following formats:

**In the PR description** (recommended):

```markdown
Fixes #123
Closes #123
Resolves #123
```

**Multiple issues**:

```markdown
Fixes #123, fixes #124
Resolves #123 and closes #124
```

**In commit messages**:

```bash
git commit -m "Fix connection timeout issue

Fixes #123"
```

### Supported Keywords

GitHub recognizes the following keywords for automatic issue closing:

- `close`, `closes`, `closed`
- `fix`, `fixes`, `fixed`
- `resolve`, `resolves`, `resolved`

### Best Practices for Issue Linking

1. **Always reference an issue**: Before starting work, check if an issue exists. If not, create one to discuss the problem or feature.

2. **Use the PR description**: Add the linking keyword in the PR description (not just the title) for clarity.

3. **Link early**: Add the issue reference when you create the PR, not later.

4. **One issue per PR when possible**: Keep PRs focused. If fixing multiple unrelated issues, consider separate PRs.

5. **Example PR description**:

   ```markdown
   ## Description
   This PR adds connection timeout configuration to the JDBC driver.
   
   ## Changes
   - Added `connectionTimeout` property to `OjpDataSource`
   - Updated configuration documentation
   - Added unit tests for timeout behavior
   
   ## Testing
   - Tested with H2 database
   - Verified timeout behavior with slow connections
   
   Fixes #123
   ```

6. **Cross-repository references**: If referencing an issue in another repository:

   ```markdown
   Fixes Open-J-Proxy/ojp-docs#45
   ```

---

## Code Style and Conventions

### Java Code Style

- **Follow standard Java conventions**: Use camelCase for variables and methods, PascalCase for classes
- **Use Lombok annotations**: This project uses Lombok for boilerplate reduction (`@Getter`, `@Setter`, `@Builder`, etc.)
- **Meaningful names**: Use descriptive variable and method names
- **Comments**: Add comments only when necessary to explain complex logic. Code should be self-documenting when possible
- **Formatting**: Use consistent indentation (4 spaces for Java files)

### Project Structure

OJP is a multi-module Maven project:

- **ojp-grpc-commons**: Shared gRPC protocol definitions
- **ojp-jdbc-driver**: JDBC driver implementation
- **ojp-server**: Proxy server implementation

### Dependencies

- **Minimize new dependencies**: Only add new dependencies when absolutely necessary
- **Use existing libraries**: HikariCP for connection pooling, gRPC for communication, OpenTelemetry for observability
- **License compatibility**: Ensure any new dependencies use Apache 2.0 compatible licenses

---

## Testing Requirements

### Test Coverage

- **All new features must include tests**: Integration tests are preferred for database-related functionality (transactions, queries, connection management). Unit tests are best for functionality not tied to database behavior (e.g., load balancing, circuit breaker logic).
- **Bug fixes must include regression tests**: Ensure the bug doesn't happen again
- **Maintain or improve coverage**: Don't decrease overall test coverage

### Test Types

1. **Integration Tests** (preferred for database behavior): Test interaction between components with real databases, including transactions, query execution, and connection management
2. **Unit Tests** (for non-database logic): Test individual classes and methods in isolation, best for logic like load balancing or circuit breaker functionality
3. **Multinode Tests**: Test high-availability scenarios (see [Multinode Configuration](https://github.com/Open-J-Proxy/ojp/blob/main/documents/multinode/README.md))

### Writing Tests

- Use **JUnit 5** for test framework
- Follow existing test patterns in the codebase
- Use **meaningful test names** that describe what is being tested:

  ```java
  @Test
  void shouldReturnNullWhenColumnDoesNotExist() {
      // test implementation
  }
  ```

### CI/CD Testing

Tests run automatically in GitHub Actions:

- **Main CI**: Runs H2 tests first (fast fail-fast mechanism)
- **Specialized Jobs**: Run only after Main CI succeeds (PostgreSQL, MySQL, MariaDB, CockroachDB, Oracle, SQL Server, DB2)
  - **PostgreSQL tests** run twice: once with standard OJP server and once with SQL enhancer enabled

For more details, see [Setup and Testing OJP Source](https://github.com/Open-J-Proxy/ojp/blob/main/documents/code-contributions/setup_and_testing_ojp_source.md).

---

## Pull Request Process

### Before Submitting a PR

1. ✅ **Ensure your code builds**: `mvn clean install`
2. ✅ **Run tests locally**: At minimum, run H2 tests
3. ✅ **Update documentation**: If your changes affect user-facing behavior
4. ✅ **Link to an issue**: Use `Fixes #issue-number` in the PR description
5. ✅ **Rebase on main**: Make sure your branch is up to date with the main branch

### Creating a Pull Request

1. **Push your branch** to your fork
2. **Open a Pull Request** on GitHub from your fork to `Open-J-Proxy/ojp:main`
3. **Fill out the PR description** with:
   - Clear description of what the PR does
   - Link to the related issue (using `Fixes #123`)
   - Testing steps or evidence
   - Any breaking changes or migration notes

### PR Title

Use a clear, descriptive title:

- ✅ `Add connection timeout configuration to JDBC driver`
- ✅ `Fix null pointer exception in ResultSet.getString()`
- ✅ `Update README with Docker installation instructions`
- ❌ `Fix bug`
- ❌ `Update`

### After Submitting

- **Respond to feedback**: Be prepared to make changes based on code review
- **Keep the PR updated**: Rebase if the main branch has moved forward
- **Be patient**: Maintainers are volunteers and may take time to review

---

## Code Review Process

### What to Expect

- All PRs require review before merging
- Reviewers may request changes, ask questions, or suggest improvements
- CI checks must pass before merging
- Large PRs may take longer to review

### Responding to Reviews

- **Be respectful**: Reviewers are helping improve the code
- **Ask questions**: If feedback is unclear, ask for clarification
- **Make requested changes**: Address feedback in new commits
- **Mark conversations as resolved**: After addressing comments

### Reviewing Others' PRs

We encourage contributors to review each other's PRs:

- Check for correctness and clarity
- Test the changes if possible
- Provide constructive feedback
- Approve if everything looks good

---

## Contributor Recognition

OJP has a comprehensive [Contributor Recognition Program](https://github.com/Open-J-Proxy/ojp/blob/main/documents/contributor-badges/contributor-recognition-program.md) that recognizes contributions across all tracks:

- **🔵 Code Track**: Contributor → Developer → Core Developer
- **🟢 Testing Track**: Tester → Quality Advocate → Reliability Engineer
- **🟣 Evangelism Track**: Advocate → Evangelist → Community Leader
- **🟠 Documentation Track**: Documenter → Author → Knowledge Builder

All tracks can lead to the highest honor: **🏆 OJP Champion**

Badges can be used on CVs, LinkedIn profiles, and presentations!

---

## Getting Help

### Communication Channels

- **GitHub Issues**: For bug reports and feature requests
- **GitHub Discussions**: For questions and general discussions
- **Discord**: Join our [Discord server](https://discord.gg/J5DdHpaUzu) for real-time chat

### Documentation

- [README](https://github.com/Open-J-Proxy/ojp/blob/main/README.md) - Project overview and quick start
- [Setup and Testing Guide](https://github.com/Open-J-Proxy/ojp/blob/main/documents/code-contributions/setup_and_testing_ojp_source.md) - Detailed development setup
- [OJP Components](https://github.com/Open-J-Proxy/ojp/blob/main/documents/OJPComponents.md) - Architecture overview
- [Configuration Guides](https://github.com/Open-J-Proxy/ojp/blob/main/documents/configuration/) - JDBC and server configuration
- [ADRs](https://github.com/Open-J-Proxy/ojp/blob/main/documents/ADRs/) - Architectural Decision Records

### Questions?

If you're unsure about something:

1. Check existing documentation
2. Search existing issues and discussions
3. Ask in Discord or open a discussion on GitHub

We're here to help! Don't hesitate to ask questions.

---

## License

By contributing to OJP, you agree that your contributions will be licensed under the [Apache License 2.0](https://github.com/Open-J-Proxy/ojp/blob/main/LICENSE).

---

Thank you for contributing to Open J Proxy! 🚀
