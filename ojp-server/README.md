## Docker Image

The image is a custom Alpine JRE built with `jlink` (only the modules required by OJP), bringing the final image size to **128MB**.

The build is a single multi-stage `Dockerfile`: Stage 1 runs `jlink` to produce the custom JRE, Stage 2 copies it alongside the fat JAR and JDBC drivers into a minimal Alpine image. The server process runs as a dedicated non-root `ojp` user.

### Build the image locally

```bash
cd ojp-server && ./docker-build.sh
```

This will:
1. Build the fat JAR via Maven
2. Run `docker build` using the multi-stage `Dockerfile`

The image copies whatever is in `ojp-libs/` at build time. Use `download-drivers.sh` to populate it with the open source drivers you want before building:

```bash
./download-drivers.sh ./ojp-libs
```

### Run locally

```bash
docker run -p 1059:1059 rrobetti/ojp:<version>
```

### Build and push to Docker Hub

PS: Only authorized users.

```bash
docker login
cd ojp-server && ./docker-build.sh push
```

### Run with JVM parameters

```bash
docker run -d \
  -p 1059:1059 \
  -e JAVA_TOOL_OPTIONS="-Xmx4g -Xms2g -Dfile.encoding=UTF-8 -Duser.timezone=UTC" \
  rrobetti/ojp:<version>
```

### Verify the image (integration test)

The `docker-build` Maven profile builds the image and runs `OjpDockerImageIT`, which starts the container and confirms the server comes up without missing class errors. Requires `ojp-libs/` to be populated and Docker daemon to be running.

```bash
# Optional: populate ojp-libs with open source drivers first
./download-drivers.sh ./ojp-libs

mvn verify -pl ojp-server -am -Pdocker-build
```

For comprehensive Docker deployment examples and configuration options, see the **[Docker Deployment Guide](../documents/configuration/DOCKER_DEPLOYMENT.md)**.
