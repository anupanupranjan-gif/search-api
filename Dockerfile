FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY target/search-api-*.jar app.jar
COPY djl-cache/cache /root/.djl.ai/cache
COPY djl-pytorch/pytorch /root/.djl.ai/pytorch
EXPOSE 8080
ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
