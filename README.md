# Brain Booster Lab

Local-first production system for family-friendly puzzle and detective videos.

## Foundation

- Java 25 and Spring Boot application for orchestration and APIs
- PostgreSQL in Docker Compose for durable workflow state
- Flyway migrations for schema changes
- FFmpeg plus Java2D for deterministic media assembly: title card, colorful puzzle board, countdown, answer reveal, and CTA
- OpenAI API only for AI generation, configured later through environment variables
- Future control and publishing adapters: WhatsApp and YouTube

## Start locally

```bash
cp .env.example .env
docker compose up -d postgres
mvn spring-boot:run
```

Check the service at `http://localhost:8080/api/v1/status` and health at `http://localhost:8080/actuator/health`.

Open `http://localhost:8080/` for the local Brain Booster Lab control center. It provides buttons to create briefs, approve scripts, generate content, and render videos; no WhatsApp or publishing account is required.

## Content-job API

Create a draft job:

```bash
curl --request POST http://localhost:8080/api/v1/content-jobs \
  --header 'Content-Type: application/json' \
  --data '{"title":"Find the Hidden Key","prompt":"A family-friendly visual puzzle"}'
```

List the approval queue with `GET /api/v1/content-jobs`, then approve a draft with `POST /api/v1/content-jobs/{id}/approve`.

Generate a script with `POST /api/v1/content-jobs/{id}/generate`. Local development defaults to a deterministic mock generator. To use the OpenAI Responses API, set `GENERATION_MODE=live`, `OPENAI_MODEL`, and `OPENAI_API_KEY` in the environment. The key is read by the official Java client and is never persisted by the application.

Render a generated script with `POST /api/v1/content-jobs/{id}/render`. The local renderer creates colorful 1920x1080 PNG scenes in memory, then FFmpeg assembles them into a reproducible MP4 under `outputs/rendered` with a five-second countdown, highlighted answer reveal, captions, and CTA. Temporary scene files are removed after rendering; the artifact path and render command are recorded in PostgreSQL.

Jobs move through explicit states: `DRAFT`, `APPROVED`, `GENERATING`, `READY`, `RENDERING`, `RENDERED`, `PUBLISHED`, and `FAILED`.

## Next build slices

1. Optional narration, sound effects, and background music
2. Richer puzzle templates and configurable video aspect ratios
3. WhatsApp command adapter and YouTube publishing adapter
