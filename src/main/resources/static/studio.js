'use strict';

const api = '/api/v2/episodes';
const ACTIVE = new Set(['GENERATING', 'REVIEWING', 'NARRATING', 'NARRATION_GROUNDING', 'SPEAKING', 'PREPARING_ART', 'RENDERING']);
const STEPS = [
  ['puzzles', 'Puzzles'], ['review', 'Review'], ['artwork', 'Artwork'], ['narration', 'Narration'],
  ['grounding', 'Grounding'], ['voice', 'Voice'], ['preview', 'Preview'], ['approval', 'Approval'], ['final', 'Final video']
];
let selected = location.hash.slice(1), episode = null, busy = false, step = 0;
const REVIEW_ITEMS = [
  ['puzzles', 'The puzzle logic and options are fair.'],
  ['artwork', 'The artwork is clear and the reveal points to the right clue.'],
  ['narration', 'The narration matches the puzzle and its answer.'],
  ['voice', 'The voice pace and energy feel right.'],
  ['preview', 'I watched the preview video from start to finish.']
];
let defaultSettings = {channelName:'PUZZLE POP', puzzleCount:3, textModel:'gpt-4o-mini', imageModel:'gpt-image-1', narrationModel:'gpt-4o-mini', speechModel:'gpt-4o-mini-tts', speechVoice:'cedar', speechSpeed:1};
let modelCatalog = {text:['gpt-6-astra','gpt-5.6-sol','gpt-5.6-terra'],image:['gpt-image-2'],speech:['gpt-4o-mini-tts'],voices:['cedar','marin','alloy','ash','ballad','coral','echo','fable','nova','onyx','sage','shimmer','verse'],live:false};
const by = id => document.querySelector('#' + id);
const el = (tag, text, className) => { const node = document.createElement(tag); if (text != null) node.textContent = text; if (className) node.className = className; return node; };
const settings = e => e?.settings || defaultSettings;
const media = (e, file) => `${api}/${e.id}/media/${file}`;
const title = e => e.spec?.title || 'New puzzle episode';
function lockPlaybackToNormalSpeed(player) {
  // A review video must always be judged at the production rate. Some browsers
  // retain a changed media speed, so enforce the one-second timer contract here.
  player.defaultPlaybackRate = 1;
  player.playbackRate = 1;
  player.addEventListener('ratechange', () => {
    if (player.playbackRate !== 1) player.playbackRate = 1;
  });
  return player;
}

function notice(text) { const node = by('notice'); node.textContent = text; node.hidden = !text; }
function isReviewed(e) { return !!e.review?.findings?.length && e.review.findings.every(f => f.fair && e.spec?.puzzles?.[f.puzzleNumber - 1]?.answerId === f.independentlySolvedAnswerId); }
function reviewCanBeOverridden(e) { return !!e.review?.findings?.length && e.review.findings.length === e.spec?.puzzles?.length && e.review.findings.every(f => e.spec?.puzzles?.[f.puzzleNumber - 1]?.answerId === f.independentlySolvedAnswerId); }
function reviewGatePassed(e) { return isReviewed(e) || !!e.puzzleReviewOverridden; }
function isGrounded(e) { return !!e.narrationGrounding?.findings?.length && e.narrationGrounding.findings.every(f => f.visualClueConfirmed && f.narrationMatchesFrame && f.optionOnly); }
function groundingCanBeOverridden(e) { return !!e.narrationGrounding?.findings?.length && e.narrationGrounding.findings.length === e.spec?.puzzles?.length && e.narrationGrounding.findings.every(f => f.optionOnly); }
function groundingGatePassed(e) { return isGrounded(e) || !!e.narrationGroundingOverridden; }
function isWorking(e) { return ACTIVE.has(e?.status); }
function actionUsesAi(action) { return ['generate', 'review', 'artwork', 'narration', 'ground-narration', 'speech'].includes(action); }
function reviewKey(e) { return `brain-booster-review-${e.id}`; }
function reviewState(e) { try { return JSON.parse(localStorage.getItem(reviewKey(e))) || {}; } catch { return {}; } }
function reviewReady(e) { const checks = reviewState(e); return REVIEW_ITEMS.every(([key]) => checks[key]); }
function runningKey(e) { return `brain-booster-running-${e.id}`; }
function runningSince(e) { const value = Number(localStorage.getItem(runningKey(e))); return value > 0 ? value : Date.now(); }
function durationSince(e) { const elapsed = Math.max(0, Math.floor((Date.now() - runningSince(e)) / 1000)); return `${Math.floor(elapsed / 60)}:${String(elapsed % 60).padStart(2, '0')}`; }
function deadlineNote(e) {
  return ({GENERATING:'The request has a two-minute deadline.', REVIEWING:'The request has a two-minute deadline.', NARRATING:'The request has a two-minute deadline.', NARRATION_GROUNDING:'The request has a two-minute deadline.', PREPARING_ART:'Each puzzle gets one high-quality image and a conformance check. Only a detected mismatch can trigger one saved repair image; each request has a two-minute deadline.', SPEAKING:'Each voice request has a 90-second deadline; complete local clips are reused.', RENDERING:'Local rendering has a ten-minute deadline; frames and clips remain available.'})[e.status] || 'This action has a saved recovery path.';
}
function savedStep(e) { const value = Number(localStorage.getItem(`brain-booster-step-${e.id}`)); return Number.isInteger(value) && value >= 0 && value < STEPS.length ? value : firstOpenStep(e); }
function setStep(value) { step = Math.max(0, Math.min(STEPS.length - 1, value)); if (episode) localStorage.setItem(`brain-booster-step-${episode.id}`, String(step)); draw(); }
function firstOpenStep(e) {
  if (!e.spec) return 0; if (!reviewGatePassed(e)) return 1; if (!e.artworkReady || !e.artworkSelectionFinalized) return 2; if (!e.narration) return 3;
  if (!groundingGatePassed(e)) return 4; if (!e.speechReady) return 5; if (!e.previewReady) return 6; if (!e.approvedAt) return 7; if (!e.finalReady) return 8; return 8;
}
function retryDefinition(e) {
  if (!['FAILED', 'INTERRUPTED'].includes(e?.status)) return null;
  if (e.failedStage === 'SPEAKING' && /speech duration is outside its safe range/i.test(e.lastError || ''))
    return [5, 'Recover local voice clips', 'The renderer now follows each measured voice clip. Retry to recover the saved local clips without another Speech API request.', 'speech'];
  const recovered = {
    GENERATING:[0, 'Retry puzzle generation', 'Send a concise recovery request. No prior puzzle content was saved.', 'generate'],
    REVIEWING:[1, 'Retry puzzle review', 'Run the independent fairness review again using the saved puzzles.', 'review'],
    PREPARING_ART:[2, 'Retry artwork preparation', 'Resume from saved artwork and completed visual checks where possible.', 'artwork'],
    NARRATING:[3, 'Retry narration', 'Generate the narration again from the saved puzzles.', 'narration'],
    NARRATION_GROUNDING:[4, 'Retry narration grounding', 'Recheck narration against the saved question frames.', 'ground-narration'],
    SPEAKING:[5, 'Retry voice generation', 'Resume from complete local voice clips when available.', 'speech'],
    RENDERING:[e.approvedAt ? 8 : 6, e.approvedAt ? 'Retry final video render' : 'Retry preview render', 'Render again from the saved production assets.', e.approvedAt ? 'render' : 'preview']
  }[e.failedStage];
  if (recovered) return recovered;
  const inferred = firstOpenStep(e);
  const actions = ['generate','review','artwork','narration','ground-narration','speech','preview','approve','render'];
  const action = actions[inferred];
  return action ? [inferred, `Retry ${STEPS[inferred][1].toLowerCase()}`, 'Resume this failed stage using all saved work.', action] : null;
}

async function request(path, method = 'POST', body) {
  const response = await fetch(api + path, {method, headers:{'Content-Type':'application/json'}, body:body === undefined ? undefined : JSON.stringify(body)});
  let data; try { data = await response.json(); } catch { throw new Error('The local studio returned an unexpected response. Refresh once and try again.'); }
  if (!response.ok) throw new Error(data.detail || data.message || data.error || 'Request failed');
  return data;
}
function choices(values, current) { return [...new Set([...(values || []), current].filter(Boolean))]; }
function fillSelect(input, values, current, format = value => value) { if (!input) return; input.replaceChildren(); choices(values, current).forEach(value => { const option = el('option', format(value)); option.value = value; option.selected = value === current; input.append(option); }); }
function fillSettings(s) {
  for (const [id, key] of [['channel-name','channelName'], ['puzzle-count','puzzleCount'], ['text-model','textModel'], ['image-model','imageModel'], ['narration-model','narrationModel'], ['speech-model','speechModel'], ['speech-voice','speechVoice'], ['speech-speed','speechSpeed']]) {
    const input = by(id); if (!input) continue;
    if (key === 'textModel' || key === 'narrationModel') fillSelect(input, modelCatalog.text, s[key]);
    else if (key === 'imageModel') fillSelect(input, modelCatalog.image, s[key]);
    else if (key === 'speechModel') fillSelect(input, modelCatalog.speech, s[key]);
    else if (key === 'speechVoice') fillSelect(input, modelCatalog.voices, s[key]);
    else if (key === 'speechSpeed') fillSelect(input, [.85,.9,.95,1,1.05,1.1].map(String), String(s[key]), value => `${Number(value).toFixed(2)}×`);
    else input.value = s[key];
  }
}
function readSettings(host = document) {
  const get = key => host.querySelector(`[data-setting="${key}"]`) || host.querySelector('#' + ({channelName:'channel-name',puzzleCount:'puzzle-count',textModel:'text-model',imageModel:'image-model',narrationModel:'narration-model',speechModel:'speech-model',speechVoice:'speech-voice',speechSpeed:'speech-speed'}[key]));
  return {channelName:get('channelName')?.value.trim() || host.dataset.channelName || defaultSettings.channelName, puzzleCount:Number(get('puzzleCount').value), textModel:get('textModel').value.trim(), imageModel:get('imageModel').value.trim(), narrationModel:get('narrationModel').value.trim(), speechModel:get('speechModel').value.trim(), speechVoice:get('speechVoice').value, speechSpeed:Number(get('speechSpeed').value)};
}
async function load() {
  try {
    const [all, catalog] = await Promise.all([fetch(api).then(r => r.json()), fetch(api + '/models').then(r => r.ok ? r.json() : modelCatalog)]);
    modelCatalog = catalog || modelCatalog;
    if (all[0]?.settings) { defaultSettings = all[0].settings; fillSettings(defaultSettings); }
    episode = selected ? await fetch(`${api}/${selected}`).then(r => r.json()) : null;
    if (episode) step = savedStep(episode);
    draw();
  } catch { notice('Cannot reach the local studio. Check that it is running, then refresh.'); }
}
function addButton(parent, label, handler, className = 'primary', disabled = false) {
  const button = el('button', label, className); button.disabled = disabled || busy; button.onclick = handler; parent.append(button); return button;
}
function outputTitle(parent, label, description) { parent.append(el('p', label, 'output-kicker'), el('h3', description)); }
function emptyOutput(parent, text) { parent.append(el('p', text, 'output-empty')); }
function facts(parent, object) {
  const list = el('dl', null, 'facts');
  Object.entries(object).filter(([,value]) => value !== undefined && value !== null && value !== '').forEach(([key, value]) => {
    list.append(el('dt', key.replace(/([A-Z])/g, ' $1').replace(/^./, c => c.toUpperCase())), el('dd', typeof value === 'boolean' ? (value ? 'Yes' : 'No') : String(value)));
  }); parent.append(list);
}
function actionFor(e, index) {
  const retry = retryDefinition(e); if (retry && retry[0] === index) return [retry[1], retry[2], retry[3], false];
  const retryingGeneration = !e.spec && e.status === 'FAILED';
  const actions = [
    !e.spec && [retryingGeneration ? 'Retry puzzle generation' : 'Generate puzzles', retryingGeneration ? 'Send a concise recovery request. No prior puzzle content was saved.' : 'Generate the puzzle script with your configured text model.', 'generate', false],
    e.spec && !reviewGatePassed(e) && [e.review ? 'Run puzzle review again' : 'Review puzzles', e.review ? 'Run the fairness and reasoning check again using the saved puzzles.' : 'Run the fairness and reasoning check before artwork.', 'review', false],
    reviewGatePassed(e) && !e.artworkReady && ['Generate artwork', 'Create clean illustrations, then check candidate order and the clue before the blind review. A detected mismatch may use one repair image credit for that puzzle.', 'artwork', true],
    e.artworkReady && e.artworkSelectionFinalized && [e.narration ? 'Regenerate narration' : 'Generate narration', e.narration ? 'Write a fresh story-led narration for these selected puzzles. Existing voice clips will be cleared.' : 'Write the story-led narration for these selected puzzles only.', 'narration', false],
    e.narration && e.artworkReady && !groundingGatePassed(e) && ['Ground narration', 'Verify every narrated clue against the finished images.', 'ground-narration', false],
    groundingGatePassed(e) && [e.speechReady ? 'Regenerate voice' : 'Generate voice', e.speechReady ? 'Replace the saved voice clips. Each puzzle will use its own measured narration timing. This uses speech credits.' : 'Create local voice clips using the saved voice settings. This uses speech credits.', 'speech', true],
    e.speechReady && [e.previewReady ? 'Re-render preview' : 'Render preview', e.previewReady ? 'Rebuild individually voice-timed puzzle clips, then merge them with silent transitions. This does not use OpenAI credits.' : 'Create individually voice-timed puzzle clips and a reviewable merged video.', 'preview', false],
    e.previewReady && !e.approvedAt && ['Approve episode', 'Lock this reviewed episode for final rendering.', 'approve', false],
    e.approvedAt && !e.finalReady && ['Render final video', 'Create the downloadable final video.', 'render', false]
  ];
  return actions[index] || null;
}
function waitingMessage(e, index) {
  const requirements = [null, 'Generate puzzles first.', 'Complete the puzzle review or deliberately continue with its warnings before creating artwork.', 'Choose the final puzzle set after artwork, then generate narration.', 'Generate narration and artwork before grounding.', 'Pass narration grounding or deliberately continue with its saved warnings before creating voice.', 'Generate voice before rendering a preview.', 'Render a preview before approval.', 'Approve the episode before final rendering.'];
  return requirements[index];
}
function instructionKey(action) { return ({generate:'generate',review:'review',artwork:'artwork',narration:'narration','ground-narration':'grounding',speech:'speech'})[action]; }
function basePromptLabel(action) {
  return ({generate:'Create picture-first family puzzles from the creative prompt and selected puzzle count, using exactly three or four choices and avoiding concepts in local puzzle history.',review:'Independently check family difficulty, variety, fairness, and whether every answer has one clear proof.',artwork:'Create a pure, unlabelled 16:9 scene with three or four candidates in clear left-to-right lanes. Keep a quiet strip along the bottom; the application places the OPTION letters there without shrinking the picture.',narration:'Write an energetic but natural story-led voice-over grounded in the reviewed puzzles.', 'ground-narration':'Compare the narration against the finished question frames and correct only what the image proves.',speech:'Perform the approved narration with the selected OpenAI voice and speed.'})[action] || '';
}
function stageDirection(e, action) {
  const key = instructionKey(action); if (!key) return null;
  const box = el('section', null, 'prompt-card'); box.append(el('p', 'AI prompt for this stage', 'output-kicker'), el('p', basePromptLabel(action), 'prompt-summary'));
  const label = el('label', 'ADDITIONAL DIRECTION — OPTIONAL'); const area = document.createElement('textarea'); area.id = 'stage-instruction'; area.rows = 4; area.maxLength = 3000; area.placeholder = 'Add a direction for this run, for example: “Make the first puzzle a cheerful garden mystery.”'; area.value = e.stageInstructions?.[key] || ''; label.append(area); box.append(label, el('p', 'This text is saved and appended to the protected production prompt when you click the stage button.', 'prompt-note')); return box;
}
function actionPreflight(e, action) {
  const box = el('aside', null, 'action-preflight');
  const ai = actionUsesAi(action[2]);
  box.append(el('p', ai ? 'Before you run this' : 'Run details', 'preflight-title'));
  const copy = ai
    ? `This makes an OpenAI API request using your account credits. It uses ${action[2] === 'artwork' ? settings(e).imageModel : action[2] === 'speech' ? `${settings(e).speechVoice} at ${settings(e).speechSpeed}×` : settings(e).textModel}. The saved result appears below.`
    : 'This step runs locally. No OpenAI request is made; the saved result appears below.';
  box.append(el('p', copy));
  return box;
}
function continueWithReviewWarnings(e) {
  return async () => {
    if (busy) return;
    busy = true; draw(); notice('Saving your decision…');
    try {
      episode = await request('/' + e.id + '/continue-with-review-warnings');
      step = firstOpenStep(episode);
      localStorage.setItem(`brain-booster-step-${episode.id}`, String(step));
      notice('Review warnings accepted. You can now choose artwork when ready.');
    } catch (error) { notice(error.message); }
    finally { busy = false; draw(); }
  };
}
function regenerateFailures(e, path, singular, count, nextStep) {
  return async () => {
    if (busy) return;
    const subject = `${count} failed ${count === 1 ? singular : singular + 's'}`;
    busy = true; draw(); notice(`Creating a new version and regenerating ${subject}…`);
    try {
      episode = await request('/' + e.id + path);
      selected = episode.id; location.hash = selected; step = nextStep;
      localStorage.setItem(`brain-booster-step-${episode.id}`, String(nextStep));
      if (isWorking(episode)) localStorage.setItem(runningKey(episode), String(Date.now()));
      notice(`${subject} ${count === 1 ? 'is' : 'are'} regenerating in a new version. The original episode is unchanged.`);
    } catch (error) { notice(error.message); }
    finally { busy = false; draw(); }
  };
}
function regenerateItem(e, path, puzzleNumber, stageIndex, label) {
  return async () => {
    if (busy || isWorking(e) || e.approvedAt) return;
    busy = true; draw(); notice(`Creating a new version and regenerating Puzzle ${puzzleNumber} ${label}…`);
    try {
      episode = await request('/' + e.id + path, 'POST', {puzzleNumber});
      selected = episode.id; location.hash = selected; step = stageIndex;
      localStorage.setItem(`brain-booster-step-${episode.id}`, String(stageIndex));
      if (isWorking(episode)) localStorage.setItem(runningKey(episode), String(Date.now()));
      notice(`Puzzle ${puzzleNumber} ${label} is regenerating in a new version. The original episode is unchanged.`);
    } catch (error) { notice(error.message); }
    finally { busy = false; draw(); }
  };
}
function reviewDecision(e) {
  if (!e.review || reviewGatePassed(e)) return null;
  const box = el('aside', null, 'review-decision');
  if (!reviewCanBeOverridden(e)) {
    box.append(el('h3', 'Resolve the failed puzzles'), el('p', 'Use the single batch action below. It creates a new version and replaces only the puzzles the reviewer rejected; your original episode remains unchanged.'));
    return box;
  }
  box.append(el('h3', 'Choose the next path'), el('p', 'The reviewer agrees with every answer, but flagged quality concerns. You can run the review again, or continue with these saved warnings. Continuing does not make an OpenAI call.'));
  addButton(box, 'Continue with review warnings', continueWithReviewWarnings(e), 'secondary', isWorking(e));
  return box;
}
function continueWithGroundingWarnings(e) {
  return async () => {
    if (busy) return;
    busy = true; draw(); notice('Saving your decision…');
    try {
      episode = await request('/' + e.id + '/continue-with-grounding-warnings');
      step = firstOpenStep(episode);
      localStorage.setItem(`brain-booster-step-${episode.id}`, String(step));
      notice('Grounding warnings accepted. You can now generate voice when ready.');
    } catch (error) { notice(error.message); }
    finally { busy = false; draw(); }
  };
}
function applyGroundedNarration(e) {
  return async () => {
    if (busy) return;
    busy = true; draw(); notice('Applying the grounding editor’s corrected narration…');
    try {
      episode = await request('/' + e.id + '/apply-grounded-narration');
      step = firstOpenStep(episode); localStorage.setItem(`brain-booster-step-${episode.id}`, String(step));
      notice('Corrected narration is saved. No OpenAI request was made. Generate voice when you are ready.');
    } catch (error) { notice(error.message); }
    finally { busy = false; draw(); }
  };
}
function groundingDecision(e) {
  if (!e.narrationGrounding || groundingGatePassed(e)) return null;
  const box = el('aside', null, 'review-decision');
  if (!groundingCanBeOverridden(e)) {
    box.append(el('h3', 'Resolve the failed items'), el('p', 'Use the batch artwork action below for visual mismatches. A wording issue can return to Narration.'));
    return box;
  }
  box.append(el('h3', 'Choose the next path'), el('p', 'The grounding editor found visual or wording concerns, but OPTION-only narration is intact. You can ground again, or consciously continue with these saved warnings. Continuing makes no OpenAI call.'));
  addButton(box, 'Continue with grounding warnings', continueWithGroundingWarnings(e), 'secondary', isWorking(e));
  return box;
}
function selectionKey(e) { return `brain-booster-artwork-selection-${e.id}`; }
function savedSelection(e) {
  try {
    const saved = JSON.parse(localStorage.getItem(selectionKey(e)) || 'null');
    if (Array.isArray(saved)) return saved.filter(Number.isInteger);
  } catch { /* Use every puzzle if browser storage is unavailable. */ }
  return e.spec.puzzles.map((_, index) => index + 1);
}
function artworkSelection(e) {
  if (!e.artworkReady || e.artworkSelectionFinalized) return null;
  if ((e.visualReviews || []).some(review => !review.acceptable)) return null;
  const section = el('section', null, 'artwork-selection');
  section.append(el('h3', 'Choose puzzles for the video'), el('p', 'Keep the images you want. The next episode version will use only these puzzles for narration, voice, preview, and final video. Choosing them confirms your visual decision; automated review notes remain visible. This is local and makes no OpenAI call.'));
  const selectedNumbers = new Set(savedSelection(e)); const choices = el('div', null, 'selection-list'); const count = el('p', null, 'selection-count');
  const update = () => {
    const values = [...selectedNumbers].sort((a, b) => a - b); localStorage.setItem(selectionKey(e), JSON.stringify(values));
    count.textContent = `${values.length} of ${e.spec.puzzles.length} puzzles selected`;
    button.disabled = busy || values.length === 0;
  };
  e.spec.puzzles.forEach((puzzle, index) => {
    const number = index + 1; const label = el('label', null, 'selection-option'); const input = document.createElement('input'); input.type = 'checkbox'; input.checked = selectedNumbers.has(number);
    input.onchange = () => { if (input.checked) selectedNumbers.add(number); else selectedNumbers.delete(number); update(); };
    label.append(input, el('span', `Puzzle ${number}`, 'selection-number'), el('span', puzzle.title || puzzle.question, 'selection-title')); choices.append(label);
  });
  const button = addButton(section, 'Use selected puzzles', async () => {
    if (busy) return;
    busy = true; draw(); notice('Creating your selected puzzle episode…');
    try {
      const values = [...selectedNumbers].sort((a, b) => a - b);
      episode = await request('/' + e.id + '/artwork-selection', 'POST', {puzzleNumbers:values});
      selected = episode.id; location.hash = selected; step = firstOpenStep(episode);
      localStorage.removeItem(selectionKey(e)); localStorage.setItem(`brain-booster-step-${episode.id}`, String(step));
      notice('Selected puzzles are ready. Continue with narration when you are ready.');
    } catch (error) { notice(error.message); }
    finally { busy = false; draw(); }
  }, 'primary');
  section.append(choices, count, button); update(); return section;
}
function approvalChecklist(e) {
  const state = reviewState(e); const box = el('section', null, 'approval-checklist');
  box.append(el('h3', 'Final review'), el('p', 'Confirm these checks before locking this episode. These notes are saved in this browser for this episode.'));
  REVIEW_ITEMS.forEach(([key, text]) => {
    const label = el('label', null, 'review-item'); const input = document.createElement('input'); input.type = 'checkbox'; input.checked = !!state[key];
    input.onchange = () => { const next = reviewState(e); next[key] = input.checked; localStorage.setItem(reviewKey(e), JSON.stringify(next)); draw(); };
    label.append(input, document.createTextNode(text)); box.append(label);
  });
  return box;
}
function runAction(e, action) {
  return async () => {
    const setup = by('episode-settings'); const direction = by('stage-instruction')?.value || ''; const creativePrompt = setup?.querySelector('textarea')?.value;
    busy = true; draw(); notice('Saving this run’s settings…');
    try {
      let prepared = e;
      if (!e.approvedAt && setup) prepared = await request('/' + e.id + '/settings', 'POST', readSettings(setup));
      if (!e.spec && creativePrompt?.trim()) prepared = await request('/' + prepared.id + '/brief', 'PUT', {brief:creativePrompt.trim()});
      const key = instructionKey(action[2]);
      if (!e.approvedAt && key) { const instructions = {...(prepared.stageInstructions || {generate:'',review:'',artwork:'',narration:'',grounding:'',speech:''}), [key]:direction}; prepared = await request('/' + prepared.id + '/stage-instructions', 'POST', instructions); }
      episode = await request('/' + prepared.id + '/' + action[2]);
      if (isWorking(episode)) localStorage.setItem(runningKey(episode), String(Date.now()));
      notice(isWorking(episode) ? 'Working in the background. This page updates automatically.' : 'Step completed. Review the result below.');
    }
    catch (error) { notice(error.message); }
    finally { busy = false; draw(); }
  };
}
function buildSettings(e) {
  const section = el('section', null, 'settings-top'); section.id = 'episode-settings'; section.dataset.channelName = settings(e).channelName;
  const head = el('div', null, 'settings-heading'); head.append(el('div', null, 'channel-lock')); head.firstChild.append(el('h2', settings(e).channelName), el('p', 'Production profile for this episode', 'settings-subtitle')); head.append(el('p', modelCatalog.live ? 'Model choices refreshed from OpenAI' : 'Using the saved model choices', 'catalog-status')); section.append(head);
  const summary = el('p', `${settings(e).puzzleCount} puzzles · ${settings(e).textModel} · ${settings(e).imageModel} · ${settings(e).speechVoice} at ${settings(e).speechSpeed}×`, 'profile-summary'); section.append(summary);
  const profile = document.createElement('details'); profile.className = 'production-profile'; const profileLabel = el('summary', 'Edit production profile'); profile.append(profileLabel);
  const grid = el('div', null, 'settings-grid');
  const fields = [['Puzzle count','puzzleCount','number'], ['Text model','textModel','text'], ['Image model','imageModel','image'], ['Narration model','narrationModel','text'], ['Speech model','speechModel','speech'], ['Voice','speechVoice','voice'], ['Voice speed','speechSpeed','speed']];
  for (const [label, key, type] of fields) {
    const wrap = el('label', label.toUpperCase()); let input;
    if (type === 'number') { input = document.createElement('input'); input.type = 'number'; input.value = settings(e)[key]; input.min = 1; input.max = 10; input.step = 1; input.disabled = !!e.spec || isWorking(e); }
    else { input = document.createElement('select'); const source = type === 'image' ? modelCatalog.image : type === 'speech' ? modelCatalog.speech : type === 'voice' ? modelCatalog.voices : type === 'speed' ? [.85,.9,.95,1,1.05,1.1].map(String) : modelCatalog.text; fillSelect(input, source, String(settings(e)[key]), type === 'speed' ? value => `${Number(value).toFixed(2)}×` : value => value); }
    input.dataset.setting = key; if (isWorking(e)) input.disabled = true; wrap.append(input); grid.append(wrap);
  }
  profile.append(grid);
  const controls = el('div', null, 'settings-controls'); addButton(controls, e.approvedAt ? 'Create settings version' : 'Save settings', async () => { busy = true; draw(); try { const updated = await request('/' + e.id + (e.approvedAt ? '/settings-revision' : '/settings'), 'POST', readSettings(section)); selected = updated.id; location.hash = selected; episode = updated; step = savedStep(updated); notice(e.approvedAt ? 'A settings version was created.' : 'Settings saved.'); } catch (error) { notice(error.message); } finally { busy = false; draw(); } }, 'secondary', isWorking(e)); section.append(controls);
  profile.append(controls); section.append(profile);
  if (!e.spec) { const label = el('label', 'CREATIVE PROMPT', 'prompt-top'); const area = document.createElement('textarea'); area.value = e.brief; area.maxLength = 4000; area.rows = 4; label.append(area); section.append(label); }
  return section;
}
function buildNavigation(e) {
  const nav = el('nav', null, 'wizard-nav'); nav.setAttribute('aria-label', 'Episode stages');
  const open = firstOpenStep(e); const running = {GENERATING:0, REVIEWING:1, PREPARING_ART:2, NARRATING:3, NARRATION_GROUNDING:4, SPEAKING:5, RENDERING:e.approvedAt ? 8 : 6}[e.status];
  STEPS.forEach(([id, label], index) => { const state = index < open ? 'complete' : index === (running ?? open) ? 'current' : 'pending'; const button = el('button', null, `stage-tab ${state}${index === step ? ' active' : ''}`); button.type = 'button'; button.append(el('span', String(index + 1), 'stage-number'), el('span', label), el('span', state === 'complete' ? 'Done' : state === 'current' ? 'Now' : 'Later', 'stage-state')); button.onclick = () => setStep(index); nav.append(button); });
  return nav;
}
function puzzleOutput(e, pane) {
  outputTitle(pane, 'Generated result', 'Puzzles');
  if (!e.spec) return emptyOutput(pane, 'Your generated puzzles will appear here after you choose Generate puzzles.');
  e.spec.puzzles.forEach((puzzle, index) => {
    const card = el('article', null, 'puzzle-output'); card.append(el('span', `Puzzle ${index + 1}`, 'puzzle-number'), el('h4', puzzle.title || `Puzzle ${index + 1}`), el('p', puzzle.question, 'puzzle-question'));
    const choices = el('div', null, 'choice-list'); puzzle.choices?.forEach(choice => choices.append(el('span', `${choice.id}. ${choice.label}`, choice.id === puzzle.answerId ? 'answer-choice' : ''))); card.append(choices);
    card.append(el('p', `Answer: Option ${puzzle.answerId}`, 'answer-line'), el('p', `Reasoning: ${puzzle.explanation}`, 'reasoning-line'));
    if (!e.approvedAt && !isWorking(e)) addButton(card, 'Regenerate this puzzle', regenerateItem(e, '/regenerate-puzzle', index + 1, 0, 'puzzle'), 'secondary');
    pane.append(card);
  });
}
function reviewOutput(e, pane) {
  outputTitle(pane, 'Generated result', 'Puzzle review');
  if (!e.review) return emptyOutput(pane, 'The independent fairness review will appear here.');
  const pass = isReviewed(e); const overridden = !!e.puzzleReviewOverridden;
  pane.append(el('p', pass ? 'All puzzles passed the independent review.' : overridden ? 'You chose to continue with the saved review warnings.' : 'One or more puzzles need changes before artwork.', pass || overridden ? 'pass-note' : 'warning-note'));
  const failures = e.review.findings?.filter(f => !f.fair || e.spec?.puzzles?.[f.puzzleNumber - 1]?.answerId !== f.independentlySolvedAnswerId) || [];
  if (failures.length) {
    const recovery = el('aside', null, 'review-decision');
    recovery.append(el('h3', 'Regenerate every failed puzzle'), el('p', `This manually makes ${failures.length} text-generation ${failures.length === 1 ? 'request' : 'requests'}—one for each rejected puzzle—and opens a new version. Puzzles that passed are retained.`));
    addButton(recovery, `Regenerate all ${failures.length} failed ${failures.length === 1 ? 'puzzle' : 'puzzles'}`, regenerateFailures(e, '/regenerate-failed-puzzles', 'puzzle', failures.length, 0), 'secondary', isWorking(e));
    pane.append(recovery);
  }
  e.review.findings?.forEach(f => {
    const expected = e.spec?.puzzles?.[f.puzzleNumber - 1]?.answerId;
    const failed = !f.fair || expected !== f.independentlySolvedAnswerId;
    const card = el('article', null, 'finding'); card.append(el('h4', `Puzzle ${f.puzzleNumber} — ${failed ? 'Needs changes' : 'Passed'}`));
    facts(card, {IndependentlySolvedAnswerId:f.independentlySolvedAnswerId, Fair:f.fair, Notes:f.notes || f.reasoning || ''});
    pane.append(card);
  });
}
function artworkOutput(e, pane) {
  outputTitle(pane, 'Generated result', 'Artwork');
  if (!e.artworkReady) return emptyOutput(pane, 'The finished question and answer images will appear here for your review.');
  const grid = el('div', null, 'art-grid'); e.spec.puzzles.forEach((puzzle, index) => {
    const card = el('figure', null, 'art-card'); const image = document.createElement('img'); image.src = media(e, `question-${index}.png`); image.alt = `Puzzle ${index + 1} question artwork`; image.loading = 'lazy'; card.append(image, el('figcaption', `Puzzle ${index + 1} · question`));
    if (!e.approvedAt && !isWorking(e)) addButton(card, 'Regenerate this image', regenerateItem(e, '/regenerate-artwork', index + 1, 2, 'artwork'), 'secondary');
    grid.append(card);
    const reveal = el('figure', null, 'art-card'); const revealImage = document.createElement('img'); revealImage.src = media(e, `reveal-${index}.png`); revealImage.alt = `Puzzle ${index + 1} answer artwork with highlighted clue`; revealImage.loading = 'lazy'; reveal.append(revealImage, el('figcaption', `Puzzle ${index + 1} · answer highlight`)); grid.append(reveal);
  }); pane.append(grid);
  pane.append(el('p', 'The answer image automatically locates and circles the decisive clue during its animated reveal.', 'visual-note'));
  const failedArtwork = (e.visualReviews || []).filter(review => !review.acceptable);
  if (failedArtwork.length) {
    const recovery = el('aside', null, 'review-decision');
    recovery.append(el('h3', 'Regenerate every failed artwork'), el('p', `A failed image may not have a safe clue ring, because we never circle a clue that the blind solver could not confirm. Regenerate before selecting puzzles. This manually makes ${failedArtwork.length} image-generation ${failedArtwork.length === 1 ? 'request' : 'requests'} and opens a new version; artwork that passed is retained.`));
    addButton(recovery, `Regenerate all ${failedArtwork.length} failed ${failedArtwork.length === 1 ? 'image' : 'images'}`, regenerateFailures(e, '/regenerate-failed-artwork', 'artwork item', failedArtwork.length, 2), 'secondary', isWorking(e));
    pane.append(recovery);
  }
  e.visualReviews?.forEach((review, index) => { const note = el('p', `Visual check ${index + 1}: ${review.notes || review.summary || 'completed'}`, 'visual-note'); pane.append(note); });
  const selection = artworkSelection(e); if (selection) pane.append(selection);
}
function narrationOutput(e, pane) {
  outputTitle(pane, 'Generated result', 'Narration script');
  if (!e.narration) return emptyOutput(pane, 'The narration script will appear here before any speech is generated.');
  if (e.narration.episodeOpening) pane.append(el('p', e.narration.episodeOpening, 'script-opening'));
  e.narration.puzzles?.forEach(beat => { const card = el('article', null, 'script-card'); card.append(el('h4', `Puzzle ${beat.puzzleNumber}`)); [['Question', beat.questionLeadIn], ['Timer', beat.timerCue], ['Answer', beat.revealExplanation]].forEach(([label, value]) => { const line = el('p'); line.append(el('strong', label + ': '), document.createTextNode(value)); card.append(line); }); pane.append(card); });
  if (e.narration.episodeClosing) pane.append(el('p', e.narration.episodeClosing, 'script-closing'));
  if (e.narrationReview) pane.append(el('p', 'Narration review completed.', 'pass-note'));
}
function groundingOutput(e, pane) {
  outputTitle(pane, 'Generated result', 'Narration grounding');
  if (!e.narrationGrounding) return emptyOutput(pane, 'The visual grounding findings will appear here.');
  const pass = isGrounded(e), overridden = !!e.narrationGroundingOverridden;
  pane.append(el('p', pass ? 'Every narration clue matches the completed artwork.' : overridden ? 'You chose to continue with the saved grounding warnings.' : 'Grounding found an issue that needs review.', pass || overridden ? 'pass-note' : 'warning-note'));
  const narrationFixAvailable = e.narrationGrounding.findings?.some(f => f.visualClueConfirmed && f.optionOnly && !f.narrationMatchesFrame)
    && e.narrationGrounding.findings.every(f => f.visualClueConfirmed && f.optionOnly);
  const groundingArtworkFailures = e.narrationGrounding.findings?.filter(f => !f.visualClueConfirmed) || [];
  if (groundingArtworkFailures.length) {
    const recovery = el('aside', null, 'review-decision');
    recovery.append(el('h3', 'Regenerate every failed artwork'), el('p', `This manually makes ${groundingArtworkFailures.length} image-generation ${groundingArtworkFailures.length === 1 ? 'request' : 'requests'} and opens a new version. Artwork that passed is retained.`));
    addButton(recovery, `Regenerate all ${groundingArtworkFailures.length} failed ${groundingArtworkFailures.length === 1 ? 'image' : 'images'}`, regenerateFailures(e, '/regenerate-grounding-artwork', 'artwork item', groundingArtworkFailures.length, 2), 'secondary', isWorking(e));
    pane.append(recovery);
  }
  let narrationFixShown = false;
  e.narrationGrounding.findings?.forEach(f => {
    const card = el('article', null, 'finding'); card.append(el('h4', `Puzzle ${f.puzzleNumber}`)); facts(card, {VisualClueConfirmed:f.visualClueConfirmed, NarrationMatchesFrame:f.narrationMatchesFrame, OptionOnly:f.optionOnly, Notes:f.notes});
    if (!f.visualClueConfirmed) card.append(el('p', 'Included in the batch artwork recovery above.', 'visual-note'));
    else if (!f.optionOnly) {
      card.append(el('p', 'This is a narration wording issue. Return to Narration to generate a fresh episode script before grounding again.', 'visual-note'));
      addButton(card, 'Go to narration', () => setStep(3), 'secondary', isWorking(e));
    } else if (narrationFixAvailable && !narrationFixShown && !f.narrationMatchesFrame) {
      narrationFixShown = true;
      card.append(el('p', 'The grounding editor supplied corrected narration for every affected puzzle. Applying it is local and uses no credits.', 'visual-note'));
      addButton(card, 'Apply corrected narration', applyGroundedNarration(e), 'secondary', isWorking(e));
    }
    pane.append(card);
  });
}
function voiceOutput(e, pane) {
  outputTitle(pane, 'Generated result', 'Voice clips');
  if (!e.speechReady) return emptyOutput(pane, 'The generated voice track will appear here.');
  const audio = lockPlaybackToNormalSpeed(document.createElement('audio')); audio.controls = true; audio.src = media(e, 'speech.m4a'); pane.append(audio, el('p', `Voice: ${e.speechVoice || settings(e).speechVoice} · speed ${settings(e).speechSpeed}×`, 'media-caption'));
  pane.append(el('p', 'Each puzzle is rendered from its own measured question, cue, and answer clips. The countdown remains exactly 8.0 seconds; the 2.5-second transition has no voice.', 'track-line'));
  e.speech?.puzzles?.forEach(track => pane.append(el('p', `Puzzle ${track.puzzleNumber}: question ${Number(track.questionSeconds).toFixed(1)}s · cue ${Number(track.timerCueSeconds).toFixed(1)}s · countdown 8.0s · answer ${Number(track.revealSeconds).toFixed(1)}s.`, 'track-line')));
}
function videoOutput(e, pane, kind) {
  const final = kind === 'final'; outputTitle(pane, 'Generated result', final ? 'Final video' : 'Preview video');
  const ready = final ? e.finalReady : e.previewReady; if (!ready) return emptyOutput(pane, final ? 'The approved final video will appear here.' : 'The reviewable preview video will appear here.');
  const video = lockPlaybackToNormalSpeed(document.createElement('video')); video.controls = true; video.preload = 'metadata'; video.src = media(e, final ? 'final.mp4' : 'preview.mp4'); pane.append(video);
  const download = el('a', final ? 'Download final video' : 'Open preview video', 'secondary link-button'); download.href = video.src; download.target = '_blank'; pane.append(download);
  if (!final && e.speech?.puzzles?.length) {
    pane.append(el('h4', 'Individual puzzle clips'));
    e.speech.puzzles.forEach(track => { const clip = lockPlaybackToNormalSpeed(document.createElement('video')); clip.controls = true; clip.preload = 'metadata'; clip.src = media(e, `preview-puzzle-${track.puzzleNumber}.mp4`); pane.append(el('p', `Puzzle ${track.puzzleNumber}`, 'output-kicker'), clip); });
  }
}
function approvalOutput(e, pane) {
  outputTitle(pane, 'Generated result', 'Approval');
  if (!e.approvedAt) return emptyOutput(pane, 'Approve only after you have reviewed the preview video, voice, narration, and artwork.');
  pane.append(el('p', `Approved on ${new Date(e.approvedAt).toLocaleString()}. This exact episode is locked for final rendering.`, 'pass-note'));
}
function stageOutput(e, index) {
  const output = el('section', null, 'stage-output');
  const renderers = [
    () => puzzleOutput(e, output), () => reviewOutput(e, output), () => artworkOutput(e, output),
    () => narrationOutput(e, output), () => groundingOutput(e, output), () => voiceOutput(e, output),
    () => videoOutput(e, output, 'preview'), () => approvalOutput(e, output), () => videoOutput(e, output, 'final')
  ];
  renderers[index]();
  return output;
}
function stagePane(e) {
  const [id, label] = STEPS[step]; const pane = el('section', null, 'stage-pane'); pane.append(el('p', `Stage ${step + 1} of ${STEPS.length}`, 'section-label'), el('h2', label));
  const action = actionFor(e, step), working = isWorking(e);
  if (working) {
    pane.append(el('p', `${label} is running · ${durationSince(e)} elapsed. ${deadlineNote(e)} You can safely leave this page; the result is retained locally and will appear below.`, 'working-copy'));
  } else if (action) {
    pane.append(el('p', action[1], 'stage-description'));
    if (action[2] === 'review') { const decision = reviewDecision(e); if (decision) pane.append(decision); }
    if (action[2] === 'ground-narration') { const decision = groundingDecision(e); if (decision) pane.append(decision); }
    const direction = stageDirection(e, action[2]); if (direction) pane.append(direction);
    pane.append(actionPreflight(e, action));
    if (action[2] === 'approve') pane.append(approvalChecklist(e));
    addButton(pane, action[0], runAction(e, action), 'primary', action[2] === 'approve' && !reviewReady(e));
  } else if (step === 2 && e.artworkReady && !e.artworkSelectionFinalized) {
    pane.append(el('p', 'Inspect the artwork below, then choose the puzzles you want to keep for this video. Narration remains unavailable until you save that choice.', 'stage-description'));
  } else if (!e.finalReady && waitingMessage(e, step)) pane.append(el('p', waitingMessage(e, step), 'stage-description'));
  else if (e.finalReady && step === 8) pane.append(el('p', 'Your final video is ready to watch or download below.', 'stage-description'));
  const controls = el('div', null, 'stage-controls'); addButton(controls, 'Previous', () => setStep(step - 1), 'secondary', step === 0); addButton(controls, step === STEPS.length - 1 ? 'Back to first stage' : 'Next', () => setStep(step === STEPS.length - 1 ? 0 : step + 1), 'secondary'); pane.append(controls);
  return pane;
}
function workflow(e) {
  const shell = el('article', null, 'workflow'); const header = el('header', null, 'workflow-header'); const meta = el('div', null, 'episode-meta'); meta.append(el('span', settings(e).channelName, 'channel-badge'), el('span', e.status.replaceAll('_', ' ').toLowerCase(), `status-badge ${isWorking(e) ? 'working' : e.finalReady ? 'complete' : 'ready'}`)); header.append(el('h1', title(e)), meta); shell.append(header);
  if (e.lastError) { const error = el('div', null, 'error'); const timingIssue = e.failedStage === 'SPEAKING' && /speech duration is outside its safe range/i.test(e.lastError); error.append(el('strong', 'This step needs attention. '), document.createTextNode(timingIssue ? `${e.lastError} The updated renderer follows measured speech duration per puzzle. Refresh, then recover the saved local voice clips; no new Speech API request is needed.` : `${e.lastError} Update the stage direction or production profile, then run the unfinished step again.`)); shell.append(error); }
  shell.append(buildSettings(e), buildNavigation(e), stagePane(e), stageOutput(e, step));
  const footer = el('div', null, 'episode-footer'); addButton(footer, 'Start another episode', () => { selected = ''; location.hash = ''; episode = null; fillSettings(settings(e)); by('brief').value = e.brief; draw(); }, 'text-button'); shell.append(footer); return shell;
}
function draw() {
  const host = by('workspace'), form = by('brief-form'), home = document.querySelector('.home'); form.hidden = !!episode; home.classList.toggle('episode-open', !!episode); host.replaceChildren();
  if (!episode) { host.append(el('h1', 'Make the next puzzle video.'), el('p', 'A calm, manual studio for original family puzzle videos.', 'intro')); return; }
  host.append(workflow(episode));
}
by('brief-form').onsubmit = async event => { event.preventDefault(); if (busy) return; busy = true; notice('Creating episode…'); try { episode = await request('', 'POST', {brief:by('brief').value, settings:readSettings()}); selected = episode.id; location.hash = selected; step = 0; localStorage.setItem(`brain-booster-step-${selected}`, '0'); notice('Episode created. Generate puzzles when you are ready.'); } catch (error) { notice(error.message); } finally { busy = false; draw(); } };
load();
setInterval(async () => { if (!busy && episode && isWorking(episode)) { try { const wasWorking = true; const response = await fetch(`${api}/${episode.id}`); if (!response.ok) throw new Error(); episode = await response.json(); if (wasWorking && !isWorking(episode)) { localStorage.removeItem(runningKey(episode)); notice('Step finished. Review the result below, then choose the next action when ready.'); } draw(); } catch { notice('Cannot refresh this running episode. Check your local server, then refresh.'); } } }, 3000);
