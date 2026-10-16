FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/community-service-1.0.0.jar /app/community-service.jar
RUN mkdir -p /data/community-service && chown 10001:10001 /data/community-service
ENV SERVER_ADDRESS=0.0.0.0
ENV APP_STORAGE=/data/community-service
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java","-jar","/app/community-service.jar"]
