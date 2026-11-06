.PHONY: run test lint mcp demo demo-llm postman docker clean

JAR := mcp/target/support-copilot-mcp-0.1.0.jar

run:
	./mvnw -q spring-boot:run

test:
	./mvnw test
	./mvnw -f mcp/pom.xml test

lint:
	npx -y @stoplight/spectral-cli lint openapi.yaml

mcp:
	./mvnw -q -f mcp/pom.xml -DskipTests package

demo: mcp
	cd mcp && java -jar target/support-copilot-mcp-0.1.0.jar scripted

demo-llm: mcp
	cd mcp && java -jar target/support-copilot-mcp-0.1.0.jar llm

postman:
	npx -y newman run postman/support-platform.postman_collection.json -e postman/local.postman_environment.json

docker:
	docker compose up --build

clean:
	./mvnw -q clean
	./mvnw -q -f mcp/pom.xml clean
	rm -rf data
