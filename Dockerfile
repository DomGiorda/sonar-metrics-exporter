FROM  maven:3.9.0-eclipse-temurin-17-alpine AS build


WORKDIR /app

COPY pom.xml .
COPY src ./src


RUN mvn clean install

FROM  maven:3.9.0-eclipse-temurin-17-alpine

WORKDIR /

COPY --from=build /app/target/*.jar ./
