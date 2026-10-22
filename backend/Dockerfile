# Build with the Maven Wrapper, run on a JRE. Configuration comes from the environment only.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/juristrack-backend-*.jar /app.jar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 juristrack
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER juristrack
EXPOSE 8082
HEALTHCHECK --interval=10s --timeout=5s --retries=12 CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8082 && printf 'GET /actuator/health HTTP/1.0\r\n\r\n' >&3 && grep -q UP <&3"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
