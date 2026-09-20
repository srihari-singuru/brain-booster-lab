# Brain Booster Lab

Local-first production system for family-friendly puzzle and detective videos.

## Foundation

- Java 25 and Spring Boot application for orchestration and APIs
- PostgreSQL in Docker Compose for durable workflow state
- Flyway migrations for schema changes
- FFmpeg plus Java2D for deterministic media assembly: polished title card, cinematic puzzle board, countdown, answer reveal, and CTA
- OpenAI API only for optional AI generation and scene artwork, configured later through environment variables
- Future control and publishing adapters: WhatsApp and YouTube

## Start locally

```bash
cp .env.example .env
# Edit .env with your local values, then load it into this terminal:
set -a
source .env
set +a
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

Artwork is independently configurable. The default `ARTWORK_MODE=mock` creates a local illustrated mystery-room scene with Java2D. Set `ARTWORK_MODE=live`, `OPENAI_IMAGE_MODEL`, and `OPENAI_API_KEY` to have the OpenAI Image API create a polished scene artwork. The live prompt asks for a single fair visual clue, cinematic lighting, expressive characters, and a composition that survives a 16:9 crop. The local renderer parses the generated sections, keeps the clue and answer consistent, adds the countdown, reveal treatment, captions, and CTA, then FFmpeg assembles the MP4.

### Local-only OpenAI artwork configuration

The recommended current image model for this project is `gpt-image-2.5-flare`, a fast, high-quality image-generation model. If that model is not enabled for your account, use another image model available to your project, such as `gpt-image-2`.

Put these values only in the ignored local `.env` file:

```dotenv
ARTWORK_MODE=live
OPENAI_IMAGE_MODEL=gpt-image-2.5-flare
OPENAI_API_KEY=your-key-here
```

Keep `GENERATION_MODE=mock` if you want to use OpenAI only for artwork. Set `GENERATION_MODE=live` and provide `OPENAI_MODEL` as well if you also want OpenAI to write the puzzle script. Never put a real key in `.env.example`, source files, README files, or committed GitHub settings. The application reads the key from the process environment and does not persist it.

Render a generated script with `POST /api/v1/content-jobs/{id}/render`. The local renderer creates colorful 1920x1080 PNG scenes in memory, then FFmpeg assembles them into a reproducible MP4 under `outputs/rendered` with a five-second countdown, highlighted answer reveal, captions, and CTA. Temporary scene files are removed after rendering; the artifact path and render command are recorded in PostgreSQL.

Jobs move through explicit states: `DRAFT`, `APPROVED`, `GENERATING`, `READY`, `RENDERING`, `RENDERED`, `PUBLISHED`, and `FAILED`.

## Next build slices

1. Optional narration, sound effects, and background music
2. Richer puzzle templates, multi-puzzle episodes, and configurable video aspect ratios
3. WhatsApp command adapter and YouTube publishing adapter
