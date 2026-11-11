#!/bin/bash

##############################################################################
# OJP Open Source JDBC Driver Downloader
#
# This script downloads open source JDBC drivers (H2, PostgreSQL, MySQL, 
# MariaDB) from Maven Central and places them in the ojp-libs directory
# for runtime loading by OJP Server.
#
# Usage:
#   ./download-drivers.sh [target-directory]
#
# Arguments:
#   target-directory  Optional. Directory where drivers will be downloaded.
#                     Default: ./ojp-libs
#
# Examples:
#   ./download-drivers.sh              # Downloads to ./ojp-libs
#   ./download-drivers.sh /opt/ojp/libs  # Downloads to /opt/ojp/libs
#
##############################################################################

set -e  # Exit on error

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Driver versions (matching those removed from pom.xml in Phase 2)
H2_VERSION="2.3.232"
POSTGRESQL_VERSION="42.7.8"
MYSQL_VERSION="9.5.0"
MARIADB_VERSION="3.5.2"

# Maven Central base URL
MAVEN_CENTRAL="https://repo1.maven.org/maven2"

# Target directory (default or from argument)
TARGET_DIR="${1:-./ojp-libs}"

echo -e "${GREEN}OJP Open Source JDBC Driver Downloader${NC}"
echo "========================================"
echo ""

# Create target directory if it doesn't exist
if [ ! -d "$TARGET_DIR" ]; then
    echo -e "${YELLOW}Creating directory: $TARGET_DIR${NC}"
    mkdir -p "$TARGET_DIR"
fi

echo -e "Target directory: ${GREEN}$TARGET_DIR${NC}"
echo ""

# Function to download a driver
download_driver() {
    local name=$1
    local group_path=$2
    local artifact=$3
    local version=$4
    local jar_name="${artifact}-${version}.jar"
    local url="${MAVEN_CENTRAL}/${group_path}/${artifact}/${version}/${jar_name}"
    local target_file="${TARGET_DIR}/${jar_name}"
    
    echo -e "Downloading ${GREEN}${name}${NC} driver (v${version})..."
    
    # Check if file already exists
    if [ -f "$target_file" ]; then
        echo -e "  ${YELLOW}Already exists: $target_file${NC}"
        echo -e "  ${YELLOW}Skipping download.${NC}"
    else
        # Download using curl or wget
        if command -v curl &> /dev/null; then
            if curl -f -L -o "$target_file" "$url" 2>/dev/null; then
                echo -e "  ${GREEN}✓ Downloaded: $target_file${NC}"
            else
                echo -e "  ${RED}✗ Failed to download from: $url${NC}"
                rm -f "$target_file"
                return 1
            fi
        elif command -v wget &> /dev/null; then
            if wget -q -O "$target_file" "$url" 2>/dev/null; then
                echo -e "  ${GREEN}✓ Downloaded: $target_file${NC}"
            else
                echo -e "  ${RED}✗ Failed to download from: $url${NC}"
                rm -f "$target_file"
                return 1
            fi
        else
            echo -e "  ${RED}✗ Error: Neither curl nor wget is available${NC}"
            return 1
        fi
    fi
    
    echo ""
}

# Download all drivers
echo "Downloading JDBC drivers from Maven Central..."
echo ""

download_driver "H2" "com/h2database" "h2" "$H2_VERSION"
download_driver "PostgreSQL" "org/postgresql" "postgresql" "$POSTGRESQL_VERSION"
download_driver "MySQL" "com/mysql" "mysql-connector-j" "$MYSQL_VERSION"
download_driver "MariaDB" "org/mariadb/jdbc" "mariadb-java-client" "$MARIADB_VERSION"

echo "========================================"
echo -e "${GREEN}Download complete!${NC}"
echo ""
echo "Drivers downloaded to: $TARGET_DIR"
echo ""

# List downloaded files
if command -v ls &> /dev/null; then
    echo "Files in $TARGET_DIR:"
    ls -lh "$TARGET_DIR"/*.jar 2>/dev/null || echo "  (no JAR files found)"
fi

echo ""
echo -e "${GREEN}You can now start OJP Server with these drivers.${NC}"
echo "The drivers will be automatically loaded from the ojp-libs directory."
echo ""
echo "To use a custom path, set the ojp.libs.path system property:"
echo "  java -Dojp.libs.path=$TARGET_DIR -jar ojp-server-<version>.jar"
