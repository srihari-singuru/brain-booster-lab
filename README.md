# Brain Booster Lab

Local-first production system for family-friendly puzzle and detective videos.

## Foundation

- Java 25 and Spring Boot application for orchestration and APIs
- PostgreSQL in Docker Compose for durable workflow state
- Flyway migrations for schema changes
- FFmpeg for deterministic media assembly (to be wired into the rendering module)
- OpenAI API only for AI generation, configured later through environment variables
- Future control and publishing adapters: WhatsApp and YouTube

## Start locally

```bash
cp .env.example .env
docker compose up -d postgres
mvn spring-boot:run
```

Check the service at `http://localhost:8080/api/v1/status` and health at `http://localhost:8080/actuator/health`.

## Content-job API

Create a draft job:

```bash
curl --request POST http://localhost:8080/api/v1/content-jobs \
  --header 'Content-Type: application/json' \
  --data '{"title":"Find the Hidden Key","prompt":"A family-friendly visual puzzle"}'
```

List the approval queue with `GET /api/v1/content-jobs`, then approve a draft with `POST /api/v1/content-jobs/{id}/approve`.

Generate a script with `POST /api/v1/content-jobs/{id}/generate`. Local development defaults to a deterministic mock generator. To use the OpenAI Responses API, set `GENERATION_MODE=live`, `OPENAI_MODEL`, and `OPENAI_API_KEY` in the environment. The key is read by the official Java client and is never persisted by the application.

Jobs move through explicit states: `DRAFT`, `APPROVED`, `GENERATING`, `RENDERING`, `READY`, `PUBLISHED`, and `FAILED`.

## Next build slices

1. FFmpeg render pipeline with reproducible local artifacts
2. WhatsApp command adapter and YouTube publishing adapter
