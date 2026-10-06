# Stage 1: Build application using Gradle wrapper
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

# Copy Gradle wrapper and configuration files first to cache dependencies
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle/libs.versions.toml gradle/

RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon -q || true

# Copy source code and build executable jar
COPY src src
RUN ./gradlew bootJar --no-daemon -x test -q

# Stage 2: Runtime image with JRE 17, Node.js, Chromium, and mermaid-cli
FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="Diagram Agent Team"
LABEL description="Spring AI Diagram Agent with Mermaid CLI image rendering"

ENV DEBIAN_FRONTEND=noninteractive
ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium
ENV DIAGRAM_MERMAID_CLI_PATH=mmdc
ENV DIAGRAM_PUPPETEER_CONFIG_FILE=/app/puppeteer-config.json
ENV DIAGRAM_ALLOWED_ROOT=/projects
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Install Chromium, fonts, Node.js and @mermaid-js/mermaid-cli
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    gnupg \
    ca-certificates \
    chromium \
    fonts-liberation \
    fonts-noto-color-emoji \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g @mermaid-js/mermaid-cli@11.4.2 \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user and directories
RUN groupadd -r diagramagent && useradd -r -g diagramagent -m -d /home/diagramagent diagramagent \
    && mkdir -p /app /projects \
    && chown -R diagramagent:diagramagent /app /projects /home/diagramagent

WORKDIR /app

# Copy configuration and compiled application jar
COPY puppeteer-config.json /app/puppeteer-config.json
COPY --from=builder /workspace/build/libs/diagram-agent-*.jar /app/diagram-agent.jar

RUN chown diagramagent:diagramagent /app/diagram-agent.jar /app/puppeteer-config.json

USER diagramagent

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/ || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/diagram-agent.jar \"$@\"", "--"]
