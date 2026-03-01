APP = search-api
JAR = target/$(APP)-*.jar

.PHONY: build run test clean

build:
	mvn clean package -DskipTests

run:
	java --add-opens java.base/javax.security.auth=ALL-UNNAMED \
	  -jar $(JAR)

run-dev:
	java --add-opens java.base/javax.security.auth=ALL-UNNAMED \
	  -jar $(JAR) \
	  --elasticsearch.host=localhost \
	  --elasticsearch.password=changeme

test:
	mvn test

clean:
	mvn clean

docker-build: build
	docker build -t $(APP):latest .

# Quick smoke tests against local instance
smoke:
	@echo "=== Health ==="
	curl -s http://localhost:8080/actuator/health | python3 -m json.tool
	@echo "\n=== Hybrid search ==="
	curl -s "http://localhost:8080/api/v1/search?q=wireless+headphones&size=3" | python3 -m json.tool
	@echo "\n=== Vector search ==="
	curl -s "http://localhost:8080/api/v1/search?q=running+shoes&mode=vector&size=3" | python3 -m json.tool
	@echo "\n=== Keyword search ==="
	curl -s "http://localhost:8080/api/v1/search?q=coffee+maker&mode=keyword&size=3" | python3 -m json.tool
	@echo "\n=== Filtered search ==="
	curl -s "http://localhost:8080/api/v1/search?q=laptop&category=Electronics&maxPrice=500&size=3" | python3 -m json.tool
