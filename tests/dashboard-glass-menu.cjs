'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const browser = require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const css = source.slice(0, source.indexOf('</head>')).match(/<style[^>]*>[\s\S]*?<\/style>/g).join('\n');
const script = source.slice(source.indexOf('    const glassMenu ='), source.indexOf('    let accountSheetMotion'));
const page = `<!doctype html><html class="glass-ui"><head>${css}</head><body class="liquid-glass">
<label for="providers">供应商</label><select id="providers"><option>当前</option><optgroup label="不可用" disabled><option>停用</option></optgroup><optgroup label="可用">${Array.from({length:25}, (_,i)=>'<option>选项 '+i+'</option>').join('')}</optgroup></select><pre id="result">WAIT</pre>
<script>let reduced=false; window.matchMedia=()=>({get matches(){return reduced}}); ${script}
async function verify(){
  const $ = selector => document.querySelector(selector);
  const check=(ok,message)=>{if(!ok)throw new Error(message)};
  const select=$('#providers'); const bounds=select.getBoundingClientRect();
  const frame=()=>$('.glass-menu-frame'); const options=()=>$('.glass-menu-options');
  const animations=()=>frame().getAnimations({subtree:true});
  const at=ms=>animations().forEach(a=>{a.pause();a.currentTime=ms});
  const settle=async()=>{const active=animations();active.forEach(a=>a.finish());await Promise.all(active.map(a=>a.finished));await Promise.resolve()};
  const events=[]; for (const name of ['input','change']) document.addEventListener(name,e=>{if(e.target===select)events.push(name)});
  try {
    select.click(); at(0);
    const initial=frame().getBoundingClientRect(), width=frame().offsetWidth;
    check(Math.abs(initial.width-bounds.width)<1 && Math.abs(initial.left-bounds.left)<1,'starts at trigger');
    check(getComputedStyle(frame()).backgroundColor==='rgb(255, 255, 255)' && Number(getComputedStyle(options()).opacity)===0,'light surface and staged text');
    at(265); check(frame().getBoundingClientRect().width>width && Number(getComputedStyle(options()).opacity)>.9,'slight overshoot followed by settle');
    await settle();
    check(options().scrollHeight>options().clientHeight && document.activeElement.textContent==='当前','long list and selected focus');
    $('[aria-disabled="true"]').click(); check(frame() && !events.length && select.value==='当前','disabled option cannot select or dismiss');
    document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'End',bubbles:true,cancelable:true}));
    check(document.activeElement.textContent==='选项 24' && options().scrollTop>0,'navigation skips disabled and scrolls');
    document.activeElement.click(); at(0);
    check(select.value==='选项 24' && events.join(',')==='input,change' && document.activeElement===select,'dispatch existing events once and return focus');
    check(animations().every(a=>a.effect.getTiming().duration===500),'closing duration');
    at(395); check(Math.abs(frame().getBoundingClientRect().width-bounds.width)<2 && Number(getComputedStyle(options()).opacity)===0,'collapse to anchor');
    await settle(); check(!frame(),'close cleanup');
    document.documentElement.dataset.theme='dark'; select.click();
    check(getComputedStyle(frame()).backgroundColor==='rgb(0, 0, 0)','true black dark surface');
    $('.glass-menu-overlay').click(); await settle(); check(!frame(),'outside close');
    reduced=true; select.click(); check(!animations().length,'reduced motion opens without animation');
    document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));
    check(!frame() && document.activeElement===select,'reduced motion escape and focus');
    $('#result').textContent='PASS';
  } catch(error) {$('#result').textContent='FAIL '+error.message;}
}
verify();</script></body></html>`;
const output = browser.dom(page, 2000).match(/id="result"[^>]*>([^<]*)<\/pre>/)?.[1];
assert.equal(output, 'PASS');
console.log('Shared menu geometry, colors, scrolling, disabled options, keyboard, and input/change events passed.');
