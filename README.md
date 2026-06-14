# springboot-rag

Self-study sandbox comparing **Postgres FTS**, **pgvector**, **Qdrant**, and **hybrid (RRF)**
search in Java. See `docs/2026-06-13-springboot-rag-design.md` for the design and
`docs/plans/2026-06-13-springboot-rag.md` for the build plan.

## Prerequisites
- Java 21+ (JDK 25 works)
- Docker + Docker Compose
- Ollama: install using `winget install Ollama.Ollama --accept-package-agreements --accept-source-agreements `, then `ollama pull nomic-embed-text` and `ollama serve`
  (only needed to run the app / real smoke test; the integration test uses fake embeddings)

> Build tool: this project ships a Maven Wrapper. Use `./mvnw` (Linux/macOS/Git Bash) or
> `mvnw.cmd` (Windows) - no system Maven install required.

## Run
```bash
docker compose up -d            # postgres + qdrant
ollama serve                    # if not already running
./mvnw spring-boot:run
```
Swagger UI: http://localhost:8080/swagger-ui.html

## Endpoints
- `POST /ingest` - ingest a document `{ "docId": "...", "text": "..." }`
- `GET /search?q=...&type=fts|pgvector|qdrant|hybrid&topK=10`
- `GET /compare?q=...&topK=10` - all backends side by side (scores + timing)
- `DELETE /docs/{docId}`
- `GET /actuator/health`

## Test
```bash
./mvnw test          # unit + Testcontainers integration (needs Docker, not Ollama)
```
