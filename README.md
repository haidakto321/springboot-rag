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
- `GET /search?q=...&type=fts|pgvector|qdrant|hybrid|rerank&topK=10`
- `GET /compare?q=...&topK=10` - all backends side by side (scores + timing), including the `rerank` column
- `DELETE /docs/{docId}`
- `GET /actuator/health`

## Reranking (`type=rerank`)
`rerank` over-fetches hybrid candidates (`app.rerank.candidates`, default 50), reorders them with a
cross-encoder, then trims to `topK`. By default the reranker is a no-op `IdentityReranker`, so the
app and tests stay light and offline (no model download).

To enable the real cross-encoder, set `app.rerank.provider=djl` (in `application.yml` or as
`--app.rerank.provider=djl`). The first run downloads the `BAAI/bge-reranker-base` model **and** the
native PyTorch libraries (hundreds of MB) via DJL, then runs locally/offline after that.

| property | default | meaning |
|---|---|---|
| `app.rerank.provider` | `""` | `djl` = real bge-reranker; anything else = `IdentityReranker` |
| `app.rerank.model` | `BAAI/bge-reranker-base` | HuggingFace cross-encoder id |
| `app.rerank.candidates` | `50` | hybrid candidates fed to the reranker before trimming to `topK` |
| `app.rerank.maxLength` | `512` | tokenizer max sequence length |

## Knowledge base

UI at http://localhost:8080/ lets you import .md files, search with the backend dropdown, and ask questions. The backend runs RAG retrieval (hybrid, FTS, pgvector, or qdrant) and answers via a local chat model.

**Endpoints:**
- `POST /documents` - multipart form upload (*.md file, max 2 MB, UTF-8). Chunks the file by heading, stores chunks in Postgres + embeddings.
- `GET /documents` - list all imported documents and their chunk counts.
- `DELETE /documents/{docId}` - delete all chunks for a document.
- `GET /ask?q=...` - full RAG query: retrieves via hybrid + rerank (`app.chat.context-chunks` chunks, default 5), answers with the local chat model, cites chunk numbers. Returns answer plus the source chunks.

**Chat model prerequisite:**
```bash
ollama pull qwen3:8b  # or set app.chat.model to another Ollama model
ollama serve          # runs on localhost:11434
```

**Evaluation commands** (with your docs corpus as gold, needs Docker + Ollama):
```bash
./mvnw test "-Dgroups=eval" "-DexcludedGroups="        # retrieval metrics (top-K recall, MRR, hit@1)
./mvnw test "-Dgroups=eval-judge" "-DexcludedGroups="  # faithfulness smoke report (LLM judge, yes/no per answer)
```

## Run in WSL2 (no Docker Desktop)

For machines where policy forbids Docker Desktop: run the WHOLE stack inside Ubuntu WSL2
with native `docker-ce`. Do not split (tests on Windows + Docker in WSL) - Testcontainers
needs the Docker socket on the same side as the JVM.

**One-time setup (inside Ubuntu WSL):**
```bash
# 1. Enable systemd so the Docker service can run
sudo tee -a /etc/wsl.conf > /dev/null <<'EOF'
[boot]
systemd=true
EOF
# then from Windows: wsl --shutdown, and reopen Ubuntu

# 2. Docker Engine (docker-ce), NOT Docker Desktop
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker $USER   # then close + reopen the shell

# 3. JDK 21
sudo apt-get install -y openjdk-21-jdk

# 4. Ollama (native Linux, no container)
curl -fsSL https://ollama.com/install.sh | sh
ollama pull nomic-embed-text
ollama pull qwen3:8b            # or qwen3:4b on 16 GB machines (set app.chat.model)

# 5. Clone INTO the WSL filesystem (not /mnt/c - Maven is 5-10x slower there)
git clone <repo-url> ~/springboot-rag && cd ~/springboot-rag
```

**Run and test (same commands as everywhere):**
```bash
docker compose up -d      # postgres + qdrant inside WSL
./mvnw spring-boot:run    # app on :8080
./mvnw test               # Testcontainers finds /var/run/docker.sock natively
```

Notes:
- Ports bound in WSL auto-forward: open http://localhost:8080/ in the WINDOWS browser as usual.
- The surefire `api.version=1.44` pin in `pom.xml` already handles new Docker Engine versions
  (WSL docker-ce is typically Engine 29.x) - keep it.
- NVIDIA GPU: WSL2 CUDA passthrough works with the standard Windows NVIDIA driver; Ollama
  detects it automatically (`ollama ps` shows GPU vs CPU).
- RAM: the chat model is the heavy part (qwen3:8b ~6-7 GB while loaded). On 16 GB total,
  prefer `qwen3:4b` and cap WSL memory in `%UserProfile%\.wslconfig` if Windows starves.

## Test
```bash
./mvnw test          # unit + Testcontainers integration (needs Docker, not Ollama)
```
The real cross-encoder tests are gated behind an env var (they download a model + native libs):
```bash
RUN_DJL_SPIKE=true ./mvnw -Dtest=DjlSpikeTest,DjlRerankerManualTest test
```
