FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/search-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", \
  "--add-opens", "java.base/javax.security.auth=ALL-UNNAMED", \
  "-jar", "app.jar"]
