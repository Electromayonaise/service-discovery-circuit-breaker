# Etapa 1: compilar el jar con Maven (se usa la imagen de Maven y no ./mvnw porque en
# checkouts de Windows el wrapper puede quedar con fin de linea CRLF)
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Etapa 2: imagen de ejecucion solo con el JRE, el jar y el agente Java de OpenTelemetry
FROM eclipse-temurin:17-jre
WORKDIR /app
ARG OTEL_AGENT_VERSION=2.32.0
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
COPY --from=build /app/target/*.jar app.jar
# Se copia application.properties para que el agente lea de ahi las propiedades otel.*
COPY --from=build /app/src/main/resources/application.properties otel.properties
ENTRYPOINT ["java", "-javaagent:opentelemetry-javaagent.jar", "-Dotel.javaagent.configuration-file=otel.properties", "-jar", "app.jar"]
