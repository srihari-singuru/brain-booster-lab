# Brain Booster Lab

Local-first production system for family-friendly puzzle and detective videos.

## Episode Studio (current workflow)

### Kids-first visual mysteries

New briefs now target roughly ages 6–10: three illustrated choices, one visible clue and one
simple inference. No mandatory arithmetic, time calculations, truth tables or number patterns.
The first puzzle should give an achievable win; later puzzles may be slightly subtler, not trickier.
Examples include a friendly ghost without a shadow in a clearly fictional world, a handmade robot costume,
or a wind-up toy. Appearance, ethnicity and disability are never evidence of being nonhuman.

On screen: a question of at most ten words, A/B/C badges, at most one essential short story rule,
and a reveal of at most fourteen words. The 16:9 artwork remains complete inside a large white-framed
centre stage; a blue patterned Brain Booster Lab board fills the rest of the 1920×1080 video. The question
sits above the frame rather than covering the art, and a comic-style puzzle number, vertical channel branding,
plus a generic play mark occupy the outer board. The board's gradient is derived from the individual scene's
dominant lively hue, with a gentle drifting diamond pattern. There is no stretching or crop of the scene artwork. The lower floor area stays free
of answer panels. The temporary silent pacing contract is six seconds
for the spoken-question lead-in, exactly ten seconds of visible thinking timer, then six seconds for the
spoken-answer explanation. Subtext is deliberately hidden from the video; narration will carry the optional
story rule and explanation. Narration is prepared in `narration.txt`; audio is still deferred.

Motion layout: the scene stays stationary during thinking. The artwork receives a restrained
thumbnail-grade saturation/contrast lift at render time; the original PNG remains unchanged.
The question is large, centred and outlined on the board background. The countdown includes a shrinking
ring, with three small progress dots. The answer badge pulses once; an editorially annotated clue gets a
yellow circle drawn over 0.8 seconds, then held for inspection. There is no unstable blue pointer.
A 3.0-second saturated purple/cyan/gold interlude with the all-caps cue `NEXT PUZZLE` separates
consecutive visual puzzles. The three-puzzle pilot is 72 seconds: 22 seconds of puzzle content per case
plus two transitions. Ten cases at this timing are about 4 minutes; an 8-minute episode
would need longer narration, more cases, or an additional story structure. No flashing or camera zoom.
Question/reveal stills show the same design as the video; reveal stills use the completed circle.

The concept reviewer checks the scene design. A separate **blind image solver** sees the actual
question frame without the intended answer or scene prompt. Ambiguous/missing clues or disagreement
block approval. AI checks are fallible: human artwork review remains required. Prior reasoning
episodes retain their original renderer and assets for comparison.

Open [the local studio](http://localhost:8080/) after starting the app. It is bound to
127.0.0.1, not exposed to your network. New pilots use three short visual mysteries.
The earlier reasoning-pilot format remains supported. No narration audio or publishing yet.

1. Create a brief (no API call), then generate three structured puzzles.
2. An independent AI review solves the questions without seeing the proposed answers.
   Disagreement blocks artwork generation. This is a quality check, not a formal proof.
3. Prepare artwork. The app requests high-quality 1536×864 illustrations, retains the originals,
   and creates question/reveal previews. A vision check flags material defects.
4. Ground narration against the completed images, then generate and listen to the local AI voice soundtrack.
5. Inspect each script, still, review and voice; optionally render a **draft** video first.
6. Explicitly approve the episode, then render the final MP4. Rendering reuses the locally saved artwork and speech,
   and makes no OpenAI calls. Approved episodes are immutable; create a revised brief for changes.

Selected quality-first models (put these in your ignored local `.env`, not committed source):

```dotenv
GENERATION_MODE=live
OPENAI_MODEL=gpt-6-astra
ARTWORK_MODE=live
OPENAI_IMAGE_MODEL=gpt-image-2.5-sunburst
SPEECH_MODE=live
OPENAI_TTS_MODEL=gpt-4o-mini-tts
OPENAI_TTS_VOICE=cedar
```

The existing `OPENAI_API_KEY` is loaded from the process environment. Every narration phase uses the same 1.00x speech speed to avoid perceptible pacing shifts. Pitch is specified through the delivery instruction as a natural bright medium register because the Speech API does not expose an independent numeric pitch control. ChatGPT subscriptions
do not pay these API calls. Each pilot uses one script request, one reasoning review,
three image generations and three visual reviews. No automatic paid retries are made by
the studio. An interrupted artwork stage reuses saved images and completed visual checks.
Changing `.env` requires restarting the app; it is not read dynamically.

Assets live in `outputs/studio/<episode-id>/`: original PNGs, question/reveal PNGs,
model/prompt provenance, visual reviews, retained render frames, and preview/final MP4s.
Both `outputs/` and `.env` are ignored by Git. Existing v1 jobs and videos are preserved;
their old UI is at `/legacy.html`. New episodes have separate PostgreSQL records.

The older reasoning layout uses proportional image scaling (no stretching), with contextual artwork reframed
below the evidence header. The bottom background may extend off-canvas; the uncropped original
is always available in the studio. It uses code-rendered evidence and
choices, 10–25 seconds of thinking time, and a 12-second explanation. New visual puzzles use the
full-artwork, short-text layout described above. Unrenderable text
fails visibly instead of silently truncating evidence. Offline mock mode is explicitly
a layout-test placeholder, not publishable artwork.

API: `/api/v2/episodes` (GET list, POST JSON `{ "brief": "…" }`), then POST JSON `{}` to
`/{id}/generate`, `/review`, `/narration`, `/ground-narration`, `/artwork`, `/speech`, `/preview`, `/approve`, `/render`.
`/{id}/restyle` creates a separate rendering-only revision from saved artwork, without API calls.
It preserves the identical script's concept review but never copies approval, video, or completed
frame-review reports. Render a draft for design review; run artwork checks on the new pixels
before final approval. The original episode remains untouched.
Optionally pass `{"clueRegions":[{"x":0.45,"y":0.83,"width":0.2,"height":0.14}, ...]}`
to `/restyle`, with exactly three normalized image-space rectangles, one per puzzle. These are
editorial annotations requiring visual inspection, not automatically inferred clue locations.
Each `overlay-N.json` is bound to the artwork SHA-256 and answer ID. Changed artwork/answers
invalidate it. Without an annotation, only the answer badge is circled; no clue location is guessed.
Media is available at `/{id}/media/{name}`; arbitrary filesystem paths are not served.
The studio serializes mutations in this single-process local deployment. Requests are
synchronous; keep the page open until completion. Restart recovery preserves paid assets.

Run tests with `mvn test` (headless graphics enabled for macOS).

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

## Legacy content-job API (previous prototype)

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

The selected quality-first image model is `gpt-image-2.5-sunburst`. Model access still depends on your API project.

Put these values only in the ignored local `.env` file:

```dotenv
ARTWORK_MODE=live
OPENAI_IMAGE_MODEL=gpt-image-2.5-sunburst
OPENAI_API_KEY=your-key-here
```

Keep `GENERATION_MODE=mock` if you want to use OpenAI only for artwork. Set `GENERATION_MODE=live` and provide `OPENAI_MODEL` as well if you also want OpenAI to write the puzzle script. Never put a real key in `.env.example`, source files, README files, or committed GitHub settings. The application reads the key from the process environment and does not persist it.

Render a generated script with `POST /api/v1/content-jobs/{id}/render`. The local renderer creates colorful 1920x1080 PNG scenes in memory, then FFmpeg assembles them into a reproducible MP4 under `outputs/rendered` with a five-second countdown, highlighted answer reveal, captions, and CTA. Temporary scene files are removed after rendering; the artifact path and render command are recorded in PostgreSQL.

Jobs move through explicit states: `DRAFT`, `APPROVED`, `GENERATING`, `READY`, `RENDERING`, `RENDERED`, `PUBLISHED`, and `FAILED`.

## Next build slices

1. Optional narration, sound effects, and background music
2. Longer episodes, artwork revisions/reference conditioning, more puzzle templates and formal validators
3. WhatsApp command adapter and YouTube publishing adapter
