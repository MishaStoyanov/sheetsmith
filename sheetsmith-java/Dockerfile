# Build context is the REPOSITORY ROOT, not sheetsmith-java/ — the React UI is a sibling
# directory and has to be reachable. Build with:
#   docker build -f sheetsmith-java/Dockerfile -t xlsxai .
# (docker-compose.yml already sets context: ..)

# Stage 1: build the SheetSmith React UI
FROM node:20-alpine AS ui
WORKDIR /repo/sheetsmith-react
# Lockfile first: dependencies only reinstall when they actually change.
COPY sheetsmith-react/package.json sheetsmith-react/package-lock.json ./
RUN npm ci
COPY sheetsmith-react/ ./
# vite.config.js writes to ../sheetsmith-java/src/main/resources/static — the same relative
# path as in a local checkout, hence the /repo/<module> working directories.
RUN npm run build

# Stage 2: build fat JAR, with the UI baked in as Spring Boot static resources
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY sheetsmith-java/pom.xml .
RUN mvn dependency:resolve -q -ntp 2>/dev/null; true
COPY sheetsmith-java/src ./src
# The UI is always generated, never taken from the build context: drop whatever a local
# `npm run build` may have left behind before laying down the freshly built bundle.
RUN rm -rf ./src/main/resources/static
COPY --from=ui /repo/sheetsmith-java/src/main/resources/static ./src/main/resources/static
RUN mvn package -DskipTests -q -ntp

# Stage 3: minimal runtime
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S xlsxai && adduser -S xlsxai -G xlsxai

COPY --from=build /build/target/sheetsmith-*.jar app.jar
# These must exist in the image: a named volume mounted onto a missing path is created as root,
# which would leave the app unable to write to it.
RUN mkdir -p uploads results sessions && chown -R xlsxai:xlsxai /app

USER xlsxai
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
