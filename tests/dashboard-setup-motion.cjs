'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {dom} = require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const css = html.slice(0, html.indexOf('</head>')).match(/<style[^>]*>[\s\S]*?<\/style>/g).join('\n');
const markup = html.slice(html.indexOf('  <div id="setup-wizard"'), html.indexOf('<div id="app-update-modal"'));
const script = html.slice(html.indexOf('    const setupMotion ='), html.indexOf('    function setupSetBusy('));
const page = `<!doctype html><html class="glass-ui"><head>${css}</head><body class="liquid-glass"><header class="glass-masthead"></header><div class="glass-hero"><h1 id="glass-home-title">网关总览</h1></div><nav id="bottom-nav"></nav>${markup}<pre id="result">WAIT</pre><script>
let setupState={step:1}, reduced=false, resizeObserved;
window.ResizeObserver=class {
 constructor(callback){this.callback=callback}
 observe(){resizeObserved=this.callback}
 disconnect(){resizeObserved=null}
};
window.matchMedia=()=>({get matches(){return reduced}});
function setupGenKey(){return 'demo'}
function setupRefreshPerms(){document.getElementById('setup-perm-list').textContent='权限状态'}
function setupRefreshKeepalive(){document.getElementById('setup-keepalive-body').textContent='保活状态'}
${script}
const node=id=>document.getElementById(id);
const check=(condition,name)=>{if(!condition)throw new Error(name)};
const settle=async animations=>{const results=animations.map(a=>a.finished);animations.forEach(a=>a.finish());await Promise.all(results);await Promise.resolve()};
(async()=>{try{
 const wizard=node('setup-wizard'), stage=node('setup-steps'), brand=node('setup-brand');
 openSetupWizard();
 check(brand.querySelector('img').getAttribute('src')==='icon.png' && brand.getAnimations()[0].effect.getTiming().duration===510,'brand asset and slower entrance');
 await settle(wizard.getAnimations({subtree:true}));
 check(document.activeElement===node('setup-step-1').querySelector('h2') && getComputedStyle(document.activeElement).outlineStyle==='none','initial title has focus without visible outline');
 const brandTop=brand.getBoundingClientRect().top;
 for(const [from,to,direction] of [[1,2,1],[2,3,1],[3,2,-1],[2,3,1]]){
  const old=node('setup-step-'+from), next=node('setup-step-'+to), height=stage.offsetHeight;
  const value=node('setup-key').value;
  setupState.step=to; setupRenderStep();
  let animations=stage.getAnimations({subtree:true});
  check(animations.length===5 && animations.every(a=>a.effect.getTiming().duration===480),'slower panel pacing');
  animations.forEach(a=>{a.pause();a.currentTime=65});
  const offset=new DOMMatrixReadOnly(getComputedStyle(next).transform).m41;
  check(old.inert && old.style.display==='block' && offset*direction>2 && Number(getComputedStyle(next).opacity)>0,'directional overlap');
  check(document.activeElement===next.querySelector('h2') && getComputedStyle(document.activeElement).outlineStyle==='none' && node('setup-key').value===value,'focus and form preserved');
  check(node('setup-dot-'+to).getAttribute('aria-current')==='step' && brand.getBoundingClientRect().top===brandTop,'step indicator and stable header');
  check(stage.getAnimations().length===1,'form height interpolates from '+height);
  if(from===1){
   animations.forEach(a=>a.currentTime=240);
   const visibleHeight=stage.getBoundingClientRect().height;
   const previous=stage.getAnimations()[0];
   const details=node('setup-perm-list');details.style.minHeight='230px';
   resizeObserved();
   const resize=stage.getAnimations()[0];resize.pause();resize.currentTime=0;
   check(previous.playState==='idle' && Math.abs(stage.getBoundingClientRect().height-visibleHeight)<1,'live height adjustment starts from the visible frame');
   check(Math.abs(parseFloat(resize.effect.getKeyframes()[1].height)-next.offsetHeight)<1,'live height adjusts to the populated form');
   animations=stage.getAnimations({subtree:true});
  }
  await settle(animations);
  check(old.style.display==='none' && !old.inert && !setupMotion.moving(),'old panel released');
 }
 const leaving=setupMotion.leave();
 check(wizard.style.display==='block' && wizard.getAnimations()[0].effect.getTiming().duration===570 && node('bottom-nav').getAnimations().length===1,'home and wizard overlap');
 wizard.getAnimations()[0].finish(); await leaving;
 check(wizard.style.display==='none' && document.activeElement===node('glass-home-title') && getComputedStyle(document.activeElement).outlineStyle==='none','home focus and cleanup');
 reduced=true;openSetupWizard();setupState.step=2;setupRenderStep();
 check(!stage.getAnimations({subtree:true}).length && !brand.getAnimations().length && node('setup-step-2').style.display==='block','reduce motion');
 await setupMotion.leave();check(wizard.style.display==='none','reduce motion home');
 node('result').textContent='PASS';
}catch(error){node('result').textContent='FAIL '+error.message}})();</script></body></html>`;
assert.equal(dom(page, 2200).match(/<pre id="result">([^<]*)<\/pre>/)?.[1], 'PASS');
console.log('Setup icon, both forward steps, reverse direction, height, home transition and reduced motion passed.');
