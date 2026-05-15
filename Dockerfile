FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY target/search-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
