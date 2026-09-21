# Family puzzle video: presentation review

## Direction

Aim for a calm look → guess → discover rhythm. The reward is understanding the clue,
not keeping up with effects. These are design hypotheses, not proven retention improvements.
Keep the existing questions and art for this iteration so presentation can be compared fairly.

## Implemented in the motion draft

- Large, centered all-caps questions on the branded board background; no confusing subtext or opaque top card.
- The complete 16:9 scene lives inside a large white-framed centre stage, while the patterned board fills the screen.
- A numbered comic burst at upper left identifies each puzzle; the board gradient uses a dominant vivid colour sampled from its scene.
- The background diamonds drift gently while the puzzle image remains still, so the clue stays easy to inspect.
- Vertical Brain Booster Lab branding and a generic play mark use the outer rails; no third-party channel identity or artwork is reused.
- Smaller A/B/C badges in the lower subject area, away from faces and the known clues.
  This episode contains three options; do not invent a fourth just to display D.
- No crop or stretch of the scene artwork; the white-framed stage preserves the complete image and all evidence.
- A six-second spoken-question lead-in, then a fixed 10-second timer during the stationary thinking period.
- One gentle answer-badge pulse. Draw a circle around the actual clue, then hold it long enough to inspect.
- A three-second saturated purple/cyan/gold interlude between scenes, with the brief all-caps cue `NEXT PUZZLE`.
- A six-second answer explanation after the timer. This is 22 seconds of content per puzzle;
  ten puzzles therefore produce about 4 minutes before transitions, not eight minutes.
- Retained original art, no generated lettering, no camera crop, and no extra image API requests.

Clue circles are manually positioned for these exact images and bound to their hashes. They
are not a general computer-vision locator. Every new scene needs a placement review. For the
ghost, circle the clear ground where the missing shadow would be, not the child's face.

## Next, only after presentation approval

1. Add warm, unhurried narration: one invitation to guess, a pause, and one concrete explanation.
   Synchronize the countdown after the spoken question rather than making children read and race.
2. Add quiet, optional reveal/transition sounds; avoid a loud ticking clock or failure buzzer.
3. Encourage pointing or saying A/B/C aloud. A normal MP4 cannot capture answers. A separate
   interactive player could later accept choices, but should not be confused with YouTube playback.
4. Vary the scene and clue type across episodes while preserving the familiar interaction rhythm.
   Avoid asking for likes/subscriptions in the middle of a puzzle or turning every reveal into confetti.
5. Review on a phone as well as a large screen, including with audio muted. Confirm badge ownership,
   readable lettering, uncropped clues, no early answer leak and enough time to understand each reveal.

## Validation

Before publishing, have a small family viewing session with adult supervision. Observe whether
children understand the question, can distinguish the choices, and can explain the answer after
the reveal. Ask which puzzle felt confusing; do not infer satisfaction simply from correct guesses.

After publishing is explicitly enabled, inspect the video-level audience-retention report,
particularly dips at waiting periods and transitions and replays around reveals. Replays may
mean interest OR confusion; interpret them alongside actual viewing feedback. Change one design
variable at a time. There is no guaranteed retention percentage from adding animation.

Reference: [YouTube's key moments for audience retention](https://support.google.com/youtube/answer/9314415?hl=en).

Audio, publishing, viewer tracking and a scored interactive player remain out of scope for this draft.
