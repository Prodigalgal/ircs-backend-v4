FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY services/ircs-notification-worker/build/libs/ircs-notification-worker-0.1.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
