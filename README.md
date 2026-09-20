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

## Next build slices

1. Content-job lifecycle and approval queue
2. OpenAI-backed puzzle/script generation adapter
3. FFmpeg render pipeline with reproducible local artifacts
4. WhatsApp command adapter and YouTube publishing adapter
