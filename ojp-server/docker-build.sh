#!/bin/bash

##############################################################################
# OJP Docker Build Helper
#
# Builds the OJP Docker image using the multi-stage Dockerfile with a
# jlink-trimmed JRE. Drivers are not bundled — populate ojp-libs/ first
# with download-drivers.sh if needed.
#
# Usage:
#   ./docker-build.sh [build|push]
#
# Commands:
#   build  - Build Docker image locally (default)
#   push   - Build and push Docker image to registry (requires docker login)
#
# Examples:
#   ./docker-build.sh           # Build image locally
#   ./docker-build.sh build     # Build image locally
#   ./docker-build.sh push      # Build and push to registry
#
##############################################################################

set -e  # Exit on error

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default command
COMMAND="${1:-build}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo -e "${BLUE}==========================================${NC}"
echo -e "${BLUE}   OJP Docker Build Helper${NC}"
echo -e "${BLUE}==========================================${NC}"
echo ""

# Step 1: Build fat JAR
echo -e "${GREEN}Step 1: Building fat JAR...${NC}"
cd "$SCRIPT_DIR/.."
mvn package -pl ojp-server -am -DskipTests -q
echo -e "${GREEN}✓ JAR built${NC}"
echo ""

# Step 2: Resolve image tag from project version
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout -pl ojp-server)
IMAGE="rrobetti/ojp:${VERSION}"

# Step 3: Build Docker image
echo -e "${GREEN}Step 3: Building Docker image ${IMAGE}...${NC}"
docker build -t "$IMAGE" "$SCRIPT_DIR"

echo -e "${GREEN}✓ Docker image built: ${IMAGE}${NC}"
echo ""

if [ "$COMMAND" = "push" ]; then
    echo -e "${YELLOW}Pushing to registry (requires docker login)...${NC}"
    docker push "$IMAGE"
    echo -e "${GREEN}✓ Pushed: ${IMAGE}${NC}"
fi

echo ""
echo -e "${BLUE}==========================================${NC}"
echo -e "${GREEN}Done!${NC}"
if [ "$COMMAND" = "build" ]; then
    echo -e "Run: ${YELLOW}docker run -d -p 1059:1059 --name ojp ${IMAGE}${NC}"
fi
echo -e "${BLUE}==========================================${NC}"
