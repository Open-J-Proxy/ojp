# Analysis: Scanning .class Files for SPI Implementations in ojp-libs

## Executive Summary

This document analyzes the feasibility and requirements for extending OJP's current JAR-based driver loading mechanism to also support loading individual `.class` files from the `ojp-libs` directory. This would allow customers to deploy single SPI implementations without building a complete JAR file.

**Recommendation**: **Proceed with caution** - While technically feasible, loading individual `.class` files introduces significant complexity, security risks, and maintenance challenges that may outweigh the convenience benefit. Consider alternative approaches first.

---

## Current Architecture

### Existing JAR Loading Mechanism

OJP currently supports loading JDBC drivers and SPI implementations from JAR files in the `ojp-libs` directory through:

1. **DriverLoader.java** - Uses `URLClassLoader` to load all `.jar` files from the configured directory
2. **ServiceLoader Pattern** - Discovers JDBC drivers and SPI implementations via `META-INF/services/` files
3. **Two Main SPIs**:
   - `org.openjproxy.datasource.ConnectionPoolProvider` - For standard connection pools
   - `org.openjproxy.xa.pool.spi.XAConnectionPoolProvider` - For XA transaction pools

### Current Loading Flow

```
ojp-libs directory (JARs only)
    ↓
DriverLoader.loadDriversFromPath()
    ↓
URLClassLoader with JAR URLs
    ↓
ServiceLoader.load(Driver.class)
ServiceLoader.load(ConnectionPoolProvider.class)
ServiceLoader.load(XAConnectionPoolProvider.class)
    ↓
Automatic registration via DriverShim/Registry
```

### Key Files

- `/ojp-server/src/main/java/org/openjproxy/grpc/server/utils/DriverLoader.java` - JAR loading logic
- `/ojp-datasource-api/src/main/java/org/openjproxy/datasource/ConnectionPoolProviderRegistry.java` - SPI discovery
- `/ojp-server/src/main/java/org/openjproxy/grpc/server/ServerConfiguration.java` - Configuration defaults

---

## Requirements for .class File Loading

To support loading individual `.class` files for SPI implementations, the following would be required:

### 1. ClassLoader Enhancement

**Current**: `URLClassLoader` with JAR file URLs  
**Required**: Extended to also load individual `.class` files

**Technical Approach**:
```java
// Option A: Create custom ClassLoader that searches a directory
public class DirectoryClassLoader extends ClassLoader {
    private final Path classesDirectory;
    
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // Convert class name to path: com.example.MyClass -> com/example/MyClass.class
        Path classFile = classesDirectory.resolve(name.replace('.', '/') + ".class");
        if (!Files.exists(classFile)) {
            throw new ClassNotFoundException(name);
        }
        byte[] classData = Files.readAllBytes(classFile);
        return defineClass(name, classData, 0, classData.length);
    }
}

// Option B: Add directory as URLClassLoader URL (works in some JVMs)
URL dirUrl = new File("/path/to/ojp-libs").toURI().toURL();
URLClassLoader loader = new URLClassLoader(new URL[]{dirUrl}, parent);
```

### 2. SPI Registration File Handling

**Current Challenge**: ServiceLoader expects `META-INF/services/` files inside JARs

**Solutions**:

**Option A: Require META-INF structure in ojp-libs**
```
ojp-libs/
├── MyCustomPoolProvider.class
└── META-INF/
    └── services/
        └── org.openjproxy.datasource.ConnectionPoolProvider
            (contains: MyCustomPoolProvider)
```

**Option B: Manual registration without ServiceLoader**
```java
// Scan for .class files matching SPI interfaces
// Manually instantiate and register providers
Path libsDir = Paths.get(driversPath);
Files.walk(libsDir)
    .filter(p -> p.toString().endsWith(".class"))
    .forEach(classFile -> {
        String className = deriveClassName(classFile);
        Class<?> clazz = loader.loadClass(className);
        if (ConnectionPoolProvider.class.isAssignableFrom(clazz)) {
            ConnectionPoolProvider provider = 
                (ConnectionPoolProvider) clazz.getDeclaredConstructor().newInstance();
            ConnectionPoolProviderRegistry.registerProvider(provider);
        }
    });
```

**Option C: Convention-based discovery**
```
ojp-libs/
├── providers/
│   ├── MyCustomPoolProvider.class
│   └── OracleUCPProvider.class
└── dependencies/
    ├── SomeHelper.class
    └── AnotherHelper.class
```
All classes under `providers/` are scanned and checked for SPI interface implementation.

### 3. Dependency Management

**Challenge**: SPI implementations often depend on other classes

**Current with JARs**: All dependencies bundled in the JAR  
**With .class files**: Dependencies must be either:
- Provided as additional `.class` files in the same directory
- Already on the OJP classpath
- Loaded from separate JARs

**Required**: Class path resolution for loose `.class` files with dependencies

### 4. Package Structure Preservation

**.class files must maintain package structure**:

**Correct**:
```
ojp-libs/
└── com/
    └── example/
        └── pool/
            └── MyCustomPoolProvider.class
```

**Incorrect** (would fail):
```
ojp-libs/
└── MyCustomPoolProvider.class  # Missing package structure
```

This adds complexity for users who need to understand Java package structure.

---

## Technical Challenges

### 1. ClassLoader Complexity

**Challenge**: Mixing JAR loading and directory-based class loading in a single ClassLoader

**Issues**:
- URLClassLoader works well with JARs but directory support varies by JVM implementation
- Custom ClassLoader adds maintenance burden
- Resource loading (properties files, etc.) becomes more complex
- Parent-child ClassLoader delegation must be carefully managed

**Impact**: High - Core infrastructure change with subtle bugs possible

### 2. ServiceLoader Compatibility

**Challenge**: Java's ServiceLoader expects `META-INF/services/` files

**Issues**:
- ServiceLoader doesn't automatically scan loose `.class` files
- Requires either:
  - Manual META-INF structure in the directory (confusing for users)
  - Custom provider discovery mechanism (bypasses standard Java SPI)
  - Annotation-based discovery (requires compile-time processing)

**Impact**: Medium - Requires workaround to standard Java patterns

### 3. Dependency Resolution

**Challenge**: Single `.class` files with external dependencies

**Example Scenario**:
```java
// MyCustomPoolProvider.class depends on:
// - Apache Commons Pool (external JAR)
// - MyPoolConfig.class (another .class file)
// - org.openjproxy.datasource.ConnectionPoolProvider (OJP classpath)
```

**Issues**:
- No automatic dependency management (unlike Maven/Gradle with JARs)
- User must manually ensure all dependent classes are present
- Debugging ClassNotFoundException becomes harder
- No transitive dependency resolution

**Impact**: High - Poor user experience, difficult troubleshooting

### 4. Security Risks

**Current with JARs**: 
- Signed JARs can be verified
- Clear unit of deployment
- Virus scanning at JAR level

**With .class files**:
- Individual files harder to verify/sign
- Easier to inject malicious code (single file vs entire JAR)
- No tamper protection
- Wider attack surface

**Security Concerns**:
1. **Code Injection**: Easier to slip malicious `.class` file into directory
2. **Incomplete Deployments**: Missing dependency classes cause runtime failures
3. **Version Conflicts**: Multiple versions of same class name in directory
4. **Verification**: Cannot easily verify integrity of loose files

**Impact**: High - Increased security risk

### 5. User Experience Issues

**Challenge**: Deploying loose `.class` files is error-prone

**Common Mistakes Users Will Make**:
1. Forgetting package directory structure
2. Missing dependent classes
3. Forgetting META-INF/services registration file
4. Class naming mismatches
5. Compiled with wrong Java version

**Example User Journey (Current with JAR)**:
```bash
# 1. Compile custom provider
javac -cp ojp-server.jar MyProvider.java

# 2. Create META-INF/services file
mkdir -p META-INF/services
echo "com.example.MyProvider" > META-INF/services/org.openjproxy.datasource.ConnectionPoolProvider

# 3. Create JAR
jar cf my-provider.jar MyProvider.class META-INF/

# 4. Deploy
cp my-provider.jar ojp-libs/
```

**Example User Journey (With .class files)**:
```bash
# 1. Compile custom provider
javac -cp ojp-server.jar MyProvider.java

# 2. Create directory structure with package
mkdir -p ojp-libs/com/example/
cp MyProvider.class ojp-libs/com/example/

# 3. Create META-INF structure (confusing!)
mkdir -p ojp-libs/META-INF/services/
echo "com.example.MyProvider" > ojp-libs/META-INF/services/org.openjproxy.datasource.ConnectionPoolProvider

# 4. Deploy dependencies (if any)
# ... manually copy all dependency .class files with correct structure
```

**Impact**: High - More error-prone, harder to debug

### 6. Tooling and Ecosystem

**Challenge**: Java ecosystem built around JAR files

**Issues**:
- IDEs package to JARs by default
- Build tools (Maven, Gradle) output JARs
- No standard tooling for loose `.class` file deployment
- Debugging tools expect JARs
- Profilers and monitoring work better with JARs

**Impact**: Medium - Goes against Java conventions

### 7. Testing and Debugging

**Challenge**: Harder to test and debug loose class files

**Issues**:
- Cannot easily version loose files
- Hard to replicate environments (which exact .class files?)
- Stack traces are less clear
- Cannot easily "roll back" to previous version
- Logs don't show "source JAR" for classes

**Impact**: Medium - Operational complexity

### 8. Performance Considerations

**Challenge**: Loose files may have performance implications

**Issues**:
- File system overhead (many small files vs single JAR)
- Class loading may be slower (directory traversal vs ZIP index)
- No JAR-level caching by JVM
- More I/O operations during startup

**Impact**: Low-Medium - Likely not significant but measurable

---

## Security Analysis

### Attack Vectors with .class Files

1. **Malicious Code Injection**
   - **Scenario**: Attacker gains write access to `ojp-libs/` directory
   - **Impact**: Can inject malicious `.class` file that implements SPI
   - **Mitigation**: File system permissions, monitoring, checksum verification
   - **Severity**: HIGH

2. **Class Replacement Attack**
   - **Scenario**: Replace legitimate `.class` file with malicious version
   - **Impact**: Complete compromise of connection pool behavior
   - **Mitigation**: File integrity monitoring, immutable deployments
   - **Severity**: HIGH

3. **Incomplete/Corrupt Deployments**
   - **Scenario**: Only some `.class` files deployed, missing dependencies
   - **Impact**: Runtime failures, potential security bypasses
   - **Mitigation**: Validation checks, deployment automation
   - **Severity**: MEDIUM

### Comparison with JAR Security

| Aspect | JAR Files | .class Files |
|--------|-----------|--------------|
| Code Signing | ✅ Standard support | ❌ Not supported |
| Tamper Detection | ✅ JAR manifest/signing | ❌ Individual files |
| Integrity Verification | ✅ Single checksum | ❌ Multiple files |
| Virus Scanning | ✅ Single file | ⚠️ Many files |
| Deployment Atomicity | ✅ Single file | ❌ Multiple files |
| Version Control | ✅ Clear versioning | ❌ Unclear |

---

## Alternative Approaches

Rather than supporting loose `.class` files, consider these alternatives:

### Alternative 1: Simplified JAR Creation Tool

**Provide a simple script/tool to package single class files into JARs**

```bash
# ojp-server/create-spi-jar.sh
#!/bin/bash
# Usage: ./create-spi-jar.sh MyProvider.class org.openjproxy.datasource.ConnectionPoolProvider

CLASS_FILE=$1
SPI_INTERFACE=$2
CLASS_NAME=$(basename "$CLASS_FILE" .class)
PACKAGE_PATH=$(dirname "$CLASS_FILE")

# Create JAR structure
mkdir -p temp_jar_build/$PACKAGE_PATH
mkdir -p temp_jar_build/META-INF/services/

# Copy class file
cp "$CLASS_FILE" "temp_jar_build/$PACKAGE_PATH/"

# Create SPI registration
FULL_CLASS_NAME="${PACKAGE_PATH//\//.}.$CLASS_NAME"
echo "$FULL_CLASS_NAME" > "temp_jar_build/META-INF/services/$SPI_INTERFACE"

# Create JAR
cd temp_jar_build
jar cf "../${CLASS_NAME}.jar" .
cd ..

# Cleanup
rm -rf temp_jar_build

echo "Created ${CLASS_NAME}.jar"
echo "Deploy with: cp ${CLASS_NAME}.jar ojp-libs/"
```

**Pros**:
- ✅ Maintains current JAR-based architecture
- ✅ No security degradation
- ✅ Users still deploy one file (the JAR)
- ✅ Standard Java tooling compatibility
- ✅ Simple to maintain

**Cons**:
- ❌ Still requires one extra step (running the script)
- ❌ Users need to understand package structure

### Alternative 2: Hot-Reload JARs with Auto-Rebuild

**Provide development mode that watches .java files and auto-compiles to JAR**

```bash
# ojp-server/dev-mode.sh
#!/bin/bash
# Watches src/ directory for changes, auto-compiles and deploys

inotifywait -m -r -e modify --format '%w%f' src/ | while read FILE
do
    if [[ "$FILE" =~ \.java$ ]]; then
        echo "Detected change: $FILE"
        javac -d build/classes "$FILE"
        # Auto-create JAR with SPI registration
        create-jar-from-classes.sh build/classes ojp-libs/
        echo "Deployed to ojp-libs/"
    fi
done
```

**Pros**:
- ✅ Fast development cycle
- ✅ Still produces standard JARs
- ✅ Best of both worlds (convenience + safety)

**Cons**:
- ❌ Development-time only solution
- ❌ Requires additional tooling

### Alternative 3: Docker/Container-Based Dev Environment

**Provide dev container with pre-configured Maven project template**

```dockerfile
# ojp-spi-dev-env/Dockerfile
FROM maven:3-openjdk-11

# Template SPI project pre-configured
COPY spi-template/ /workspace/spi-template/

# One command to build and deploy
WORKDIR /workspace
CMD ["mvn", "clean", "package", "&&", "cp", "target/*.jar", "/ojp-libs/"]
```

**User Experience**:
```bash
# 1. Start dev environment
docker-compose up -d ojp-dev

# 2. Edit MyProvider.java in IDE
# 3. Run: docker exec ojp-dev mvn package
# 4. JAR automatically deployed to ojp-libs/
```

**Pros**:
- ✅ Complete development environment
- ✅ Proper dependency management (Maven/Gradle)
- ✅ Standard Java workflow
- ✅ Can include testing infrastructure

**Cons**:
- ❌ Requires Docker knowledge
- ❌ More complex initial setup

### Alternative 4: Groovy/Scripting Support (Advanced)

**Support Groovy scripts for simple SPI implementations**

```groovy
// ojp-libs/MyCustomPool.groovy
@groovy.transform.CompileStatic
class MyCustomPoolProvider implements ConnectionPoolProvider {
    String id() { return "my-pool" }
    
    DataSource createDataSource(PoolConfig config) {
        // Implementation using Groovy's concise syntax
        return new MyDataSource(config)
    }
    
    // ... other methods
}
```

**Pros**:
- ✅ No compilation step for users
- ✅ Dynamically loaded at runtime
- ✅ Groovy handles META-INF registration
- ✅ Good for simple use cases

**Cons**:
- ❌ Adds Groovy dependency to OJP
- ❌ Performance overhead
- ❌ Different language (not pure Java)
- ❌ Security implications of runtime scripting

---

## Implementation Approach (If Proceeding)

If you decide to proceed with .class file loading despite the risks, here's the recommended approach:

### Phase 1: Basic .class Loading

1. **Extend DriverLoader.java**:
```java
public boolean loadDriversFromPath(String driversPath) {
    // Existing JAR loading
    List<URL> urls = loadJarFiles(driversPath);
    
    // NEW: Add directory itself as URL for .class loading
    Path libsDir = Paths.get(driversPath);
    if (Files.exists(libsDir) && Files.isDirectory(libsDir)) {
        urls.add(libsDir.toUri().toURL());
    }
    
    URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]), 
                                                     Thread.currentThread().getContextClassLoader());
    Thread.currentThread().setContextClassLoader(classLoader);
    
    // Existing ServiceLoader-based discovery still works
    // IF META-INF/services/ exists in the directory
    ServiceLoader<Driver> drivers = ServiceLoader.load(Driver.class, classLoader);
    // ... rest of existing code
}
```

2. **Document Requirements Clearly**:
- User guide showing exact directory structure needed
- Examples with full package paths
- Warning about security implications

### Phase 2: Enhanced Discovery (Optional)

1. **Add Manual Class Scanning**:
```java
private void scanForSPIClasses(Path libsDir, ClassLoader classLoader) {
    try {
        Files.walk(libsDir)
            .filter(p -> p.toString().endsWith(".class"))
            .filter(p -> !p.toString().contains("META-INF"))
            .forEach(classFile -> {
                try {
                    String className = deriveClassName(libsDir, classFile);
                    Class<?> clazz = classLoader.loadClass(className);
                    
                    // Check if implements ConnectionPoolProvider
                    if (ConnectionPoolProvider.class.isAssignableFrom(clazz) 
                        && !clazz.isInterface() 
                        && !Modifier.isAbstract(clazz.getModifiers())) {
                        
                        ConnectionPoolProvider provider = 
                            (ConnectionPoolProvider) clazz.getDeclaredConstructor().newInstance();
                        ConnectionPoolProviderRegistry.registerProvider(provider);
                        log.info("Discovered and registered SPI provider from .class file: {}", className);
                    }
                    
                    // Check if implements XAConnectionPoolProvider
                    if (XAConnectionPoolProvider.class.isAssignableFrom(clazz)
                        && !clazz.isInterface()
                        && !Modifier.isAbstract(clazz.getModifiers())) {
                        
                        XAConnectionPoolProvider provider = 
                            (XAConnectionPoolProvider) clazz.getDeclaredConstructor().newInstance();
                        // Register with XA registry (similar pattern)
                        log.info("Discovered and registered XA SPI provider from .class file: {}", className);
                    }
                    
                } catch (Exception e) {
                    log.debug("Skipping class file {}: {}", classFile, e.getMessage());
                }
            });
    } catch (IOException e) {
        log.error("Failed to scan directory for SPI classes", e);
    }
}

private String deriveClassName(Path baseDir, Path classFile) {
    Path relativePath = baseDir.relativize(classFile);
    String className = relativePath.toString()
        .replace(File.separatorChar, '.')
        .replaceAll("\\.class$", "");
    return className;
}
```

2. **Add Validation**:
```java
private void validateClassFile(Class<?> clazz) throws ValidationException {
    // Check for no-arg constructor
    try {
        clazz.getDeclaredConstructor();
    } catch (NoSuchMethodException e) {
        throw new ValidationException("SPI class must have no-arg constructor: " + clazz.getName());
    }
    
    // Check for proper SPI implementation
    if (ConnectionPoolProvider.class.isAssignableFrom(clazz)) {
        try {
            ConnectionPoolProvider provider = (ConnectionPoolProvider) clazz.getDeclaredConstructor().newInstance();
            if (provider.id() == null || provider.id().trim().isEmpty()) {
                throw new ValidationException("SPI provider must have non-empty id(): " + clazz.getName());
            }
        } catch (Exception e) {
            throw new ValidationException("Failed to instantiate SPI provider: " + clazz.getName(), e);
        }
    }
}
```

### Phase 3: Security Enhancements

1. **Add File Integrity Checks**:
```java
public class ClassFileIntegrityChecker {
    private final Map<Path, String> knownHashes = new ConcurrentHashMap<>();
    
    public void verifyIntegrity(Path classFile) throws SecurityException {
        try {
            byte[] fileBytes = Files.readAllBytes(classFile);
            String currentHash = DigestUtils.sha256Hex(fileBytes);
            
            String knownHash = knownHashes.get(classFile);
            if (knownHash != null && !knownHash.equals(currentHash)) {
                throw new SecurityException("Class file has been modified: " + classFile);
            }
            
            knownHashes.put(classFile, currentHash);
        } catch (IOException e) {
            throw new SecurityException("Cannot verify class file: " + classFile, e);
        }
    }
}
```

2. **Configuration Option**:
```properties
# ojp-server.properties
ojp.classloading.allow-loose-classes=false  # Default: disabled for security
ojp.classloading.verify-integrity=true      # Default: enabled
ojp.classloading.scan-interval=0            # 0 = no scanning, >0 = hot reload interval
```

### Phase 4: Documentation and Tooling

1. **Comprehensive User Guide**
2. **Helper Script** (create-spi-jar.sh as shown in Alternative 1)
3. **Example Projects** in repository
4. **Troubleshooting Guide** for common issues

---

## Recommendation

### Summary Opinion

**Do NOT implement .class file loading at this time** for the following reasons:

#### High-Impact Concerns (Blocking)

1. **Security Risk** - Significantly increases attack surface with minimal security controls
2. **User Experience** - More error-prone than creating a simple JAR file
3. **Maintenance Burden** - Custom ClassLoader logic is complex and fragile
4. **Against Java Conventions** - Goes against 25+ years of Java best practices

#### Medium-Impact Concerns

5. **Dependency Management** - No way to handle dependencies effectively
6. **Tooling Compatibility** - Breaks compatibility with standard Java tools
7. **Debugging Complexity** - Harder to troubleshoot production issues

#### Alternative Recommendation

**Implement Alternative 1: Simplified JAR Creation Tool**

Provide a simple shell script and/or Maven archetype that makes it trivial to:
1. Create a minimal SPI implementation
2. Package it as a JAR with correct META-INF/services registration
3. Deploy to ojp-libs/

**Benefits of This Approach**:
- ✅ 95% of the convenience (one command to create JAR)
- ✅ Maintains all security benefits of JAR-based deployment
- ✅ Compatible with existing infrastructure
- ✅ No maintenance burden on OJP core
- ✅ Works with standard Java ecosystem
- ✅ Can be enhanced later (e.g., Maven plugin, Docker template)

### Implementation Plan for Alternative

1. **Phase 1** (Low effort): Create `create-spi-jar.sh` script in `ojp-server/`
2. **Phase 2** (Medium effort): Create Maven archetype for SPI projects
3. **Phase 3** (Medium effort): Provide Docker dev environment with hot-reload

This provides the convenience of quick development while maintaining security and stability.

---

## Questions for Stakeholders

Before making a final decision, consider these questions:

1. **Use Case Validation**: How many customers actually need this? Is it solving a real pain point?

2. **Security Posture**: What is the risk tolerance for production deployments?

3. **Support Burden**: Are we prepared to support users troubleshooting ClassNotFoundException from loose .class files?

4. **Alternative Acceptance**: Would a simple JAR creation script (Alternative 1) address 90% of the use case?

5. **Future Roadmap**: Does this align with long-term OJP architecture goals?

6. **Development Environment**: Are users primarily developing in IDEs (which produce JARs) or via command line?

7. **Container Usage**: What percentage of deployments use Docker/Kubernetes where multi-stage builds make JAR creation trivial?

---

## Conclusion

While technically feasible to support scanning `.class` files in `ojp-libs/`, the **risks and complexity outweigh the benefits**. The current JAR-based approach is:
- Secure by design
- Follows Java best practices
- Compatible with tooling
- Easy to debug
- Industry standard

**Recommended Path Forward**: 

1. ✅ **Implement** simple JAR creation tooling (Alternative 1)
2. ✅ **Enhance** documentation with step-by-step SPI development guide
3. ✅ **Provide** Maven archetype for SPI projects
4. ⚠️ **Reconsider** .class loading only if strong customer demand emerges
5. ❌ **Avoid** .class loading for production deployments

The juice is not worth the squeeze - better to make JAR creation easier than to support a more dangerous deployment method.

---

## Appendix: Code Examples

### A1: Complete create-spi-jar.sh Script

```bash
#!/bin/bash
# OJP SPI JAR Creation Tool
# Creates a deployable JAR from compiled .class files with proper SPI registration

set -e

print_usage() {
    echo "Usage: $0 <class-file> <spi-interface> [output-jar]"
    echo ""
    echo "Arguments:"
    echo "  class-file      Path to the .class file (e.g., target/classes/com/example/MyProvider.class)"
    echo "  spi-interface   Fully qualified SPI interface name"
    echo "                  - org.openjproxy.datasource.ConnectionPoolProvider"
    echo "                  - org.openjproxy.xa.pool.spi.XAConnectionPoolProvider"
    echo "  output-jar      Optional: Output JAR name (default: derived from class name)"
    echo ""
    echo "Example:"
    echo "  $0 target/classes/com/example/MyProvider.class \\"
    echo "     org.openjproxy.datasource.ConnectionPoolProvider"
    exit 1
}

# Validate arguments
if [ $# -lt 2 ]; then
    print_usage
fi

CLASS_FILE="$1"
SPI_INTERFACE="$2"
OUTPUT_JAR="$3"

# Validate class file exists
if [ ! -f "$CLASS_FILE" ]; then
    echo "Error: Class file not found: $CLASS_FILE"
    exit 1
fi

# Extract class information
CLASS_NAME=$(basename "$CLASS_FILE" .class)
CLASS_DIR=$(dirname "$CLASS_FILE")

# Derive package from directory structure
# Assumes class file is in standard Maven structure: target/classes/com/example/MyClass.class
if [[ "$CLASS_DIR" =~ target/classes/ ]]; then
    PACKAGE_PATH="${CLASS_DIR#*target/classes/}"
elif [[ "$CLASS_DIR" =~ build/classes/ ]]; then
    PACKAGE_PATH="${CLASS_DIR#*build/classes/}"
else
    PACKAGE_PATH=$(dirname "$CLASS_FILE")
fi

# Build fully qualified class name
if [ -z "$PACKAGE_PATH" ] || [ "$PACKAGE_PATH" = "." ]; then
    FULL_CLASS_NAME="$CLASS_NAME"
else
    FULL_CLASS_NAME="${PACKAGE_PATH//\//.}.${CLASS_NAME}"
fi

# Determine output JAR name
if [ -z "$OUTPUT_JAR" ]; then
    OUTPUT_JAR="${CLASS_NAME}.jar"
fi

echo "Creating OJP SPI JAR..."
echo "  Class: $FULL_CLASS_NAME"
echo "  SPI Interface: $SPI_INTERFACE"
echo "  Output: $OUTPUT_JAR"
echo ""

# Create temporary build directory
BUILD_DIR="$(mktemp -d)"
trap "rm -rf $BUILD_DIR" EXIT

# Copy class file with package structure
if [ "$PACKAGE_PATH" != "." ] && [ ! -z "$PACKAGE_PATH" ]; then
    mkdir -p "$BUILD_DIR/$PACKAGE_PATH"
    cp "$CLASS_FILE" "$BUILD_DIR/$PACKAGE_PATH/"
else
    cp "$CLASS_FILE" "$BUILD_DIR/"
fi

# Create META-INF/services directory
mkdir -p "$BUILD_DIR/META-INF/services"

# Create SPI registration file
echo "$FULL_CLASS_NAME" > "$BUILD_DIR/META-INF/services/$SPI_INTERFACE"

# Copy any inner classes (MyClass$1.class, MyClass$Inner.class, etc.)
INNER_CLASSES=$(dirname "$CLASS_FILE")/${CLASS_NAME}\$*.class
if ls $INNER_CLASSES 2>/dev/null; then
    if [ "$PACKAGE_PATH" != "." ] && [ ! -z "$PACKAGE_PATH" ]; then
        cp $INNER_CLASSES "$BUILD_DIR/$PACKAGE_PATH/"
    else
        cp $INNER_CLASSES "$BUILD_DIR/"
    fi
    echo "  Found and included inner classes"
fi

# Create JAR
cd "$BUILD_DIR"
jar cf "$(pwd)/$OUTPUT_JAR" .
cd - > /dev/null

# Move JAR to current directory
mv "$BUILD_DIR/$OUTPUT_JAR" "./$OUTPUT_JAR"

echo ""
echo "✅ Successfully created: $OUTPUT_JAR"
echo ""
echo "To deploy:"
echo "  cp $OUTPUT_JAR ojp-libs/"
echo ""
echo "To verify:"
echo "  jar tf $OUTPUT_JAR"
```

### A2: Maven Archetype Descriptor

**File**: `ojp-spi-archetype/src/main/resources/archetype-resources/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>${groupId}</groupId>
    <artifactId>${artifactId}</artifactId>
    <version>${version}</version>
    <packaging>jar</packaging>

    <name>OJP Custom SPI Implementation</name>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <ojp.version>0.3.2-snapshot</ojp.version>
    </properties>

    <dependencies>
        <!-- OJP SPI Interfaces -->
        <dependency>
            <groupId>org.openjproxy</groupId>
            <artifactId>ojp-datasource-api</artifactId>
            <version>${ojp.version}</version>
            <scope>provided</scope>
        </dependency>
        
        <!-- Add your connection pool library here -->
        <!-- Example for Oracle UCP:
        <dependency>
            <groupId>com.oracle.database.jdbc</groupId>
            <artifactId>ucp</artifactId>
            <version>21.9.0.0</version>
        </dependency>
        -->
    </dependencies>

    <build>
        <plugins>
            <!-- Package with dependencies (fat JAR) -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.0</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>shade</goal>
                        </goals>
                        <configuration>
                            <transformers>
                                <!-- Merge META-INF/services files -->
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            
            <!-- Copy to ojp-libs directory (optional) -->
            <plugin>
                <artifactId>maven-antrun-plugin</artifactId>
                <version>3.1.0</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>run</goal>
                        </goals>
                        <configuration>
                            <target>
                                <copy file="${project.build.directory}/${project.build.finalName}.jar"
                                      todir="../ojp-libs" failonerror="false"/>
                            </target>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### A3: Template SPI Implementation

**File**: `ojp-spi-archetype/src/main/resources/archetype-resources/src/main/java/CustomPoolProvider.java`

```java
package ${package};

import org.openjproxy.datasource.ConnectionPoolProvider;
import org.openjproxy.datasource.PoolConfig;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom connection pool provider implementation.
 * 
 * This class will be automatically discovered by OJP Server when the JAR
 * is placed in the ojp-libs directory.
 */
public class CustomPoolProvider implements ConnectionPoolProvider {

    @Override
    public String id() {
        return "custom-pool";
    }

    @Override
    public DataSource createDataSource(PoolConfig config) throws SQLException {
        // TODO: Implement DataSource creation using your preferred connection pool
        // Example libraries: HikariCP, Apache DBCP, C3P0, Tomcat JDBC
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void closeDataSource(DataSource dataSource) throws Exception {
        // TODO: Implement DataSource cleanup
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public Map<String, Object> getStatistics(DataSource dataSource) {
        // TODO: Return connection pool statistics
        return new HashMap<>();
    }

    @Override
    public int getPriority() {
        // Return priority > 100 to override HikariCP default
        return 150;
    }

    @Override
    public boolean isAvailable() {
        // Check if required dependencies are on classpath
        try {
            // TODO: Check for your connection pool library
            // Example: Class.forName("com.zaxxer.hikari.HikariDataSource");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
```

### A4: SPI Registration File

**File**: `ojp-spi-archetype/src/main/resources/archetype-resources/src/main/resources/META-INF/services/org.openjproxy.datasource.ConnectionPoolProvider`

```
${package}.CustomPoolProvider
```

---

## Document Metadata

- **Author**: GitHub Copilot Agent
- **Date**: January 8, 2026
- **Version**: 1.0
- **Status**: Analysis Complete - Awaiting Decision
- **Related Issues**: N/A
- **Keywords**: SPI, ClassLoader, Security, Architecture, .class files, JAR loading
