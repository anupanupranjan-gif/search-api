FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY target/search-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", \
  "--add-opens", "java.base/javax.security.auth=ALL-UNNAMED", \
  "--add-opens", "java.base/java.lang=ALL-UNNAMED", \
  "-jar", "app.jar"]
