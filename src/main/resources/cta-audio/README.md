# Reusable Puzzle Pop voice clips

Generated once with the OpenAI Speech API; renders reuse these local WAV assets and do not call the API.
Profile: gpt-4o-mini-tts, voice cedar, speed 1.0. Keep the same male family-host delivery as puzzle narration.

- `intro.wav`: “Welcome to Puzzle Pop! Today, let’s solve some fun puzzles together. Look closely, think it through, and see how many clever clues you can spot. Ready? Let’s play!”
- `comment.wav`: “Which clue caught your eye? Tell us in the comments!”
- `like.wav`: “Solved it? Tap like and celebrate your sharp eyes!”
- `outro.wav`: “Subscribe for the next puzzle, and share this challenge with your family!”

To intentionally generate missing clips once, run only `mvn -Dtest=CtaAudioAssetGenerationTest -Dpuzzlepop.generateCtaAssets=true test`.
