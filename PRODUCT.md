# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

The primary user is the channel operator creating family puzzle videos locally. They need to make deliberate decisions at every production stage, inspect what was generated, revise when unhappy, and download a finished video. The audience of the resulting videos is children ages 6–18 solving alongside parents.

## Product Purpose

Puzzle Pop turns a creative brief into an original family puzzle-video episode: puzzles, review, artwork, narration, image grounding, voice, preview, approval, and final rendering. Success means a creator can confidently produce and review a polished episode without losing track of the next action or the output of any prior action.

## Positioning

It is a local-first, human-controlled production studio rather than an automatic publishing pipeline. Every paid or creative stage is manually initiated, inspectable, and resumable.

## Operating Context

The portal runs locally in a browser. Each episode has configurable channel, puzzle count, OpenAI models, voice, and speed. The creator reviews generated puzzles, images, narration, audio, and video before approval; history is a separate screen.

## Capabilities and Constraints

- Spring Boot and FFmpeg run locally; OpenAI APIs are used only for AI generation.
- API credentials remain in the local `.env` file and must never be displayed or committed.
- The home screen must stay focused; history is separate.
- The workflow must never auto-run a stage or make a paid call without the creator clicking its action.
- A completed episode must preserve its results across refreshes and allow navigation to earlier stages.

## Brand Commitments

- Product name: Puzzle Pop.
- Puzzle challenge: medium to moderately challenging, with family-friendly wording and an enjoyable reasoning step for parents and children.
- Options: exactly three or four, placed along the lower safe strip of the full-size artwork.
- The operator has requested a clean, minimal, white, professional interface with an OpenAI-style typography preference.
- The experience should feel calm and clear, not clumsy, congested, or over-decorated.

## Evidence on Hand

- Live local portal at `http://localhost:8090/`.
- Existing home, episode workflow, and history implementations in `src/main/resources/static/`.
- Generated local media and episode records are retained under `outputs/studio/`.

## Product Principles

- One obvious next action.
- Show the result of each action where it is made.
- Keep high-cost or irreversible actions explicit.
- Let the creator move backward to inspect or revise without losing work.
- Make production state understandable at a glance.
