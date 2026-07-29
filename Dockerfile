# ---- Build stage ----
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /build

# Cache dependencies first
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# Build the app
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Run stage ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /build/target/ec-api-*.jar app.jar

# Hosting platforms inject PORT; default to 8080 for local runs
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT}"]
