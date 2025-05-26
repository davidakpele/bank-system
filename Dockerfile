# Dockerfile for Notification-service
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY target/notification-service.jar app.jar
EXPOSE 8079
ENTRYPOINT ["java", "-jar", "app.jar"]