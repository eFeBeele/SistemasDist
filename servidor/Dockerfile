FROM maven:3.9.9-eclipse-temurin-21-alpine AS compilacao
WORKDIR /app
COPY servidor/pom.xml .
COPY servidor/src src
RUN mvn -q package
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=compilacao /app/target/servidor.jar .
CMD ["java", "-jar", "servidor.jar"]
