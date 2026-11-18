'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const browser = require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const css = [...html.slice(0, html.indexOf('<body')).matchAll(/<style[^>]*>[\s\S]*?<\/style>/g)].map(match => match[0]).join('');
const markup = html.slice(html.indexOf('<section id="tab-providers"'), html.indexOf('<section id="tab-routing"')).replace('class="tab-pane"','class="tab-pane active"');
const script = html.slice(html.indexOf('    const providerRouteMotion'), html.indexOf('    const accountRoutingExpanded'));
const page = `<!doctype html><html class="glass-ui"><head>${css}<style>html{scroll-behavior:auto} body.liquid-glass{min-height:1800px} #tab-providers{margin:168px 18px}</style></head><body class="liquid-glass">
${markup}<pre id="result">WAIT</pre>
<script>
let providerView='list', providerDetail='', providerListScroll=0, reduced=false;
const appConfig={providers:[]};
window.matchMedia=()=>({get matches(){return reduced}});
const get=id=>document.getElementById(id);
function flushCommercialRender(){}
function renderProviderList(){}
function ensureAccountFileButtons(){}
${script}
(async function(){
 const check=(condition,message)=>{if(!condition)throw new Error(message)};
 const list=get('provider-list-view'), route=get('provider-catalog-route'), catalog=get('provider-catalog');
 const add=get('provider-add-button'), back=get('provider-back-button'), search=get('provider-search');
 const motion=()=>[...list.getAnimations(),...route.getAnimations()];
 const at=(ms,animations=motion())=>animations.forEach(animation=>{animation.pause();animation.currentTime=ms});
 const settle=async()=>{const animations=motion();animations.forEach(animation=>animation.finish());await Promise.all(animations.map(animation=>animation.finished));await Promise.resolve()};
 const x=node=>node.getBoundingClientRect().left;
 const alpha=node=>Number(getComputedStyle(node).opacity);
 try {
  for (const theme of ['light','dark']) {
   document.documentElement.dataset.theme=theme;
   applyProviderView();
   window.scrollTo(0,62); const scroll=scrollY;
   const base=x(list), top=list.getBoundingClientRect().top;
   search.value='filter-'+theme;
   add.click(); at(0);
   check(providerView==='add' && document.activeElement===get('provider-add-heading'),theme+' catalog and focus');
   check(list.inert && list.hidden && getComputedStyle(list).display!=='none' && !catalog.hidden,theme+' old and new views coexist');
   check(motion().length===4 && motion()[0].effect.getTiming().duration===450,theme+' enter timing');
   const start=x(route);
   check(start-base>45 && Math.abs(x(list)-base)<1 && Math.abs(list.getBoundingClientRect().top-top)<1 && alpha(list)===1 && alpha(route)===0,theme+' enter origins and scroll');
   at(165);
   check(x(list)<base-14 && x(route)<start-14 && x(route)>base+4 && alpha(list)>0 && alpha(route)>.2,theme+' shared-axis midframe');
   await settle();
   check(!route.hasAttribute('data-provider-arriving') && getComputedStyle(list).display==='none' && !list.inert,theme+' enter cleanup');
   back.click(); at(0);
   check(providerView==='list' && document.activeElement===add && search.value==='filter-'+theme && scrollY===scroll,theme+' return state restoration');
   check(route.inert && !catalog.hidden && x(list)<base-45 && Math.abs(x(route)-base)<1,theme+' return origins with directory content');
   at(140);
   check(x(route)>base+12 && x(list)<base-2 && alpha(list)>.2 && alpha(route)>0,theme+' return midframe');
   await settle();
   check(route.hidden && catalog.hidden && !route.inert && !motion().length,theme+' return cleanup');
  }
  add.click(); at(150);
  const before=[x(list),x(route),alpha(list),alpha(route)];
  check(window.allInHandleBack(), 'system back consumed'); at(0);
  const after=[x(list),x(route),alpha(list),alpha(route)];
  check(before.every((value,i)=>Math.abs(value-after[i])<1), 'rapid reversal should continue from the current frame');
  await settle();
  add.click(); window.dispatchEvent(new Event('resize'));
  check(providerView==='add' && !motion().length && !list.inert, 'resize settles current route');
  reduced=true; back.click();
  check(providerView==='list' && !motion().length && document.activeElement===add, 'reduced-motion return');
  add.click();
  check(providerView==='add' && !motion().length, 'reduced-motion entry');
  get('result').textContent='PASS';
 } catch(error) { get('result').textContent='FAIL '+error.message; }
})();</script></body></html>`;
const output = browser.dom(page, 2200).match(/id="result"[^>]*>([^<]*)<\/pre>/)?.[1];
assert.equal(output, 'PASS');
console.log('Provider route shared-axis entry and return, rapid reversal, scroll, focus and reduced motion passed.');
