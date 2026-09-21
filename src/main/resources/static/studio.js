'use strict';
const api = '/api/v2/episodes';
let selected = location.hash.slice(1), episodes = [], busy = false;
const activeStages = new Set(['GENERATING','REVIEWING','NARRATING','NARRATION_GROUNDING','SPEAKING','PREPARING_ART','RENDERING']);
const el = (tag, text, className) => {const node=document.createElement(tag); if(text!=null)node.textContent=text; if(className)node.className=className; return node;};
function notice(text) {const n=document.querySelector('#notice');n.textContent=text;n.hidden=!text;}
async function request(path, body) {
  const r=await fetch(api+path, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body || {})});
  const result=await r.json(); if(!r.ok)throw new Error(result.detail || result.message || `Request failed (${r.status})`); return result;
}
async function refresh() {
  const response=await fetch(api);if(!response.ok)throw new Error('Cannot reach the local studio.');
  episodes=await response.json();if(!episodes.some(e=>e.id===selected))selected=episodes[0]?.id || '';
  drawLibrary();draw();
}
function drawLibrary() {
  const library=document.querySelector('#library');library.replaceChildren();
  for(const episode of episodes){const button=el('button',episode.spec?.title || episode.brief.slice(0,70),'library-item'+(episode.id===selected?' active':''));
    button.append(el('small',episode.status.replaceAll('_',' ')));button.onclick=()=>{selected=episode.id;location.hash=selected;drawLibrary();draw();};library.append(button);}
}
function action(toolbar, title, path, className='secondary', confirmation) {
  const button=el('button',title,className);button.disabled=busy||activeStages.has(episodes.find(e=>e.id===selected)?.status);button.onclick=async()=>{
    if(confirmation&&!confirm(confirmation))return;
    busy=true;notice(`${title}… This can take several minutes. Saved work stays local.`);draw();
    try{const result=await request(`/${selected}/${path}`);selected=result.id;location.hash=selected;await refresh();notice(result.lastError || `${title}: complete.`);}
    catch(error){notice(error.message);}finally{busy=false;draw();}
  };toolbar.append(button);
}
function draw() {
  const editor=document.querySelector('#editor'), e=episodes.find(e=>e.id===selected);editor.replaceChildren();
  if(!e){editor.append(el('div','Create a brief to begin.','empty'));return;}
  const box=el('article',null,'episode'),head=el('div',null,'episode-head');
  head.append(el('h2',e.spec?.title || 'New episode brief'),el('span',e.status.replaceAll('_',' '),'status'));box.append(head);
  box.append(el('p',e.brief,'muted'));
  if(e.lastError)box.append(el('p',e.lastError,'error'));
  box.append(el('p','1. Generate & check puzzles  →  2. Review narration  →  3. Prepare artwork & ground narration  →  4. Generate & listen to voice  →  5. Preview, approve & render final','guide'));
  const toolbar=el('div',null,'toolbar');
  if(!e.spec)action(toolbar,'Generate three puzzles','generate','', 'Generate a script and an independent review using your API credits?');
  else if(!e.approvedAt){
    action(toolbar,'Recheck reasoning','review','secondary','Run another reasoning review using API credits?');
    action(toolbar,e.artworkReady?'Resume artwork checks':'Prepare artwork & checks','artwork','', 'Generate missing images and visual reviews using API credits. If narration exists, send the completed question frames and narration to OpenAI for final grounding. Existing artwork will be reused.');
    if(e.artworkReady&&(!e.narration||e.speechReady)){action(toolbar,'Render draft preview','preview');action(toolbar,'Approve reviewed episode','approve','approval','I have reviewed all three puzzles, question/reveal frames, AI findings, and the AI-generated voice. Approve this exact episode for final rendering?');}
  } else action(toolbar,'Render final video','render','approval');
  if(e.spec&&(e.artworkReady||e.previewReady||e.finalReady))action(toolbar,'Restyle saved artwork','restyle','secondary','Create a separate draft with the current overlay design? Questions and saved artwork stay unchanged. No API calls; the original is preserved. New visual checks and approval will be required for a final video.');
  if(e.spec&&e.review?.findings?.every(f=>f.fair))action(toolbar,e.narration ? "Regenerate narration draft" : "Generate narration draft","narration","secondary","Write and independently edit the voice-over script using API credits? This does not generate audio.");
  if(e.artworkReady&&e.narration)action(toolbar,"Ground narration against artwork","ground-narration","secondary","Send the three completed question frames and narration to OpenAI for a final visual-continuity check? This does not generate audio.");
  const groundingPasses=e.narrationGrounding?.findings?.every(f=>f.visualClueConfirmed&&f.narrationMatchesFrame&&f.optionOnly);
  if(e.artworkReady&&e.narration&&groundingPasses)action(toolbar,"Generate AI voice","speech","", "Use API credits to send the reviewed, artwork-grounded narration to OpenAI Speech. This generates nine local voice clips and a reviewable soundtrack; no video is published.");
  const revise=el('button','Create revised brief','secondary');revise.disabled=busy;revise.onclick=()=>{document.querySelector('#brief').value=e.brief+'\n\nRevision notes: ';document.querySelector('#brief').focus();notice('Add your revision notes, then create a new brief. This preserves the current episode and its artwork.');};toolbar.append(revise);box.append(toolbar);
  if(e.scriptModel)box.append(el("p","Narration, visual grounding, and AI voice are separate review stages.","muted"));
  if(e.narration){const narration=el("a","Reviewed narration script");narration.href=`${api}/${e.id}/media/narration.txt`;narration.target="_blank";narration.rel="noopener";box.append(narration);}
  if(e.narrationReview){const passed=e.narrationReview.findings?.every(f=>f.noAnswerLeak&&f.storyFitsPuzzle&&f.timeFits&&f.familySafe);box.append(el("p",passed?"Narration editor: ready for your script review.":"Narration editor: needs revision. Regenerate the narration draft.","review"+(passed?"":" warning")));}
  if(e.narrationGrounding){const passed=e.narrationGrounding.findings?.every(f=>f.visualClueConfirmed&&f.narrationMatchesFrame&&f.optionOnly);box.append(el("p",passed?"Art-grounding editor: every spoken clue matches the completed frames.":"Art-grounding editor: needs correction before speech.","review"+(passed?"":" warning")));}
  if(e.speechReady){const voice=(e.speechVoice||"marin").toUpperCase();box.append(el("p","AI voice ready — "+voice+" / "+(e.speechModel||"gpt-4o-mini-tts")+". Listen before approving.","review"));const audio=el("audio");audio.controls=true;audio.preload="metadata";audio.src=api+"/"+e.id+"/media/speech.m4a";box.append(audio);const disclosure=el("a","AI voice disclosure for publishing");disclosure.href=api+"/"+e.id+"/media/ai-voice-disclosure.txt";disclosure.target="_blank";disclosure.rel="noopener";box.append(disclosure);}
  if(e.previewReady||e.finalReady){const videoBlock=el('section',null,'video-block');videoBlock.append(el('h3',e.finalReady?'Approved final video':'Draft video — awaiting your approval'));
    const video=el('video');video.controls=true;video.preload='metadata';video.poster=`${api}/${e.id}/media/question-0.png`;video.src=`${api}/${e.id}/media/${e.finalReady?'final':'preview'}.mp4`;videoBlock.append(video);box.append(videoBlock);}
  e.spec?.puzzles.forEach((p,i)=>{
    const section=el('section',null,'puzzle');section.append(el('div',`CASE ${i+1} / ${p.kind}`,'kind'),el('h3',p.title));
    if(e.artworkReady){const tabs=el('div',null,'media-tabs'),image=el('img',null,'preview');image.alt=`Case ${i+1}: question frame`;image.src=`${api}/${e.id}/media/question-${i}.png`;
      for(const [label,name] of [['Question','question'],['Answer reveal','reveal'],['Original artwork','art']]){const b=el('button',label,'secondary');b.onclick=()=>{image.src=`${api}/${e.id}/media/${name}-${i}.png`;image.alt=`Case ${i+1}: ${label}`;};tabs.append(b);}section.append(tabs,image);
      const provenance=el('a','Artwork model & prompt');provenance.href=`${api}/${e.id}/media/provenance-${i}.json`;provenance.target='_blank';provenance.rel='noopener';section.append(provenance);
    }
    section.append(el('p',p.kind==='visual'?`Future narration (not a video text panel): ${p.setup}`:p.setup),el('strong',p.question));const facts=el('ul',null,'facts');p.facts.forEach(f=>facts.append(el('li',f)));section.append(facts);
    const choices=el('div',null,'choices');p.choices.forEach(c=>{const choice=el('div',null,'choice');choice.append(el('strong',`${c.id} · ${c.label}`),el('span',c.statement));choices.append(choice);});section.append(choices);
    const solution=el('details');solution.append(el('summary',`Answer ${p.answerId} · ${p.thinkSeconds}s thinking time`),el('p',p.explanation));section.append(solution);
    const finding=e.review?.findings?.find(f=>f.puzzleNumber===i+1);if(finding){const agrees=finding.fair&&finding.independentlySolvedAnswerId===p.answerId;section.append(el('p',`Independent reasoning check: ${agrees?'agrees':'NEEDS REVISION'}. ${finding.notes}`,'review'+(agrees?'':' warning')));}
    const visual=e.visualReviews[i];if(visual)section.append(el('p',`AI visual check (not your approval): ${visual.acceptable?'no material defect reported':'NEEDS REVIEW'}. ${visual.notes}`,'review'+(visual.acceptable?'':' warning')));
    else if(e.artworkReady)section.append(el('p','New layout — visual checks pending. You can review the draft now; run artwork checks before final approval.','review warning'));
    box.append(section);
  });editor.append(box);
}
document.querySelector('#brief-form').onsubmit=async event=>{event.preventDefault();if(busy)return;busy=true;notice('Creating your brief…');try{const created=await request('',{brief:document.querySelector('#brief').value});selected=created.id;location.hash=selected;await refresh();notice('Brief created. Review it, then generate your puzzles.');}catch(error){notice(error.message);}finally{busy=false;draw();}};
refresh().catch(error=>notice(error.message));
setInterval(()=>{if(!busy&&episodes.some(e=>activeStages.has(e.status)))refresh().catch(error=>notice(error.message));},8000);
