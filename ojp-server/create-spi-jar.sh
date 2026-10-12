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
    # Extract package path from class file location
    PACKAGE_PATH="$CLASS_DIR"
fi

# Clean up package path (remove leading ./ or /)
PACKAGE_PATH="${PACKAGE_PATH#./}"
PACKAGE_PATH="${PACKAGE_PATH#/}"

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
