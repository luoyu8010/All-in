'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const browser = require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const css = source.slice(0, source.indexOf('</head>')).match(/<style[^>]*>[\s\S]*?<\/style>/g).join('');
const script = source.slice(source.indexOf('    const disclosureMotion ='), source.indexOf('    const cardMorph ='));
const start = source.indexOf('<div id="gateway-advanced-view"');
const end = source.indexOf('<section id="tab-gateway"', start);
assert(start >= 0 && end > start);
const advanced = source.slice(start, source.lastIndexOf('</section>', end));
const fixture = `<!doctype html><html class="glass-ui"><head>${css}<style>html{scroll-behavior:auto}</style></head><body class="liquid-glass">
<details class="card" id="outer"><summary class="card-title">供应商兼容规则</summary><p>规则内容</p><details id="nested"><summary>更多</summary><p>嵌套内容</p></details><div style="height:130px">详细配置</div></details>
<div id="next">下一张卡片</div><div id="custom" class="fold-container" hidden><div style="height:88px;margin:12px 0">输入区域</div></div>${advanced}<pre id="result">WAIT</pre>
<script>
const motion = {matches:false, addEventListener(type, callback) { this.onchange = callback; }};
window.matchMedia = () => motion;
${script}
(async () => {
  const $ = id => document.getElementById(id);
  const outer = $('outer'), summary = outer.querySelector('summary'), next = $('next'), custom = $('custom');
  const height = el => el.getBoundingClientRect().height;
  const closeTo = (a,b) => Math.abs(a-b) < 1.5;
  const check = (value,message) => { if (!value) throw Error(message); };
  function seek(el,fraction) { const animation = el.getAnimations()[0]; animation.pause(); animation.currentTime = animation.effect.getTiming().duration * fraction; }
  const baseline = height(outer), closedNext = next.getBoundingClientRect().top;
  summary.click();
  check(outer.open && outer.dataset.foldMotion === 'opening','native opening');
  seek(outer,.42);
  const midpoint = height(outer);
  check(midpoint > baseline + 15 && next.getBoundingClientRect().top > closedNext + 15,'expansion did not move siblings');
  summary.click(); seek(outer,0);
  check(outer.dataset.foldMotion === 'closing' && closeTo(height(outer),midpoint),'collapse jumped during reversal');
  seek(outer,.55);
  check(height(outer) < midpoint && height(outer) > baseline,'collapse geometry did not interpolate');
  const collapsedKeys = outer.getAnimations()[0].effect.getKeyframes();
  check(closeTo(parseFloat(collapsedKeys.at(-1).height),baseline),'collapse endpoint does not match closed layout');
  outer.getAnimations()[0].finish();
  await new Promise(resolve => setTimeout(resolve,80));
  check(!outer.open && closeTo(height(outer),baseline) && !outer.dataset.foldMotion,'native close did not settle');
  outer.open = true;
  const nested = $('nested');
  nested.querySelector('summary').click();
  check(nested.open && nested.dataset.foldMotion === 'opening' && !outer.dataset.foldMotion,'nested event leaked');
  const dynamic = document.createElement('details');dynamic.innerHTML='<summary>动态条目</summary><p>动态详情</p>';
  document.body.appendChild(dynamic);dynamic.querySelector('summary').click();
  check(dynamic.open && dynamic.dataset.foldMotion === 'opening','dynamic details missed');
  disclosureMotion.panel(custom,true);seek(custom,.6);
  check(!custom.hidden && height(custom) > 10,'custom expansion');
  disclosureMotion.panel(custom,false);seek(custom,.7);
  check(custom.inert && height(custom) < 90,'custom collapse');
  $('gateway-advanced-view').hidden = false;
  for (const id of ['resilience','outbound','affinity']) {
    const button = $('advanced-' + id + '-trigger');
    const body = $(button.getAttribute('aria-controls'));
    const card = $('card-' + id);
    const idle = height(card);
    check(body.hidden && button.getAttribute('aria-expanded') === 'false', id + ' initial state');
    button.click(); seek(body,.36);
    const rising = height(card);
    check(rising > idle + 30 && body.dataset.foldMotion === 'opening', id + ' opening intermediate frame');
    button.click(); seek(body,0);
    check(closeTo(height(card),rising) && body.inert && body.dataset.foldMotion === 'closing', id + ' interrupted close');
    seek(body,.38);
    check(height(card) > idle + 15 && height(card) < rising - 15, id + ' closing intermediate frame');
    seek(body,1);
    check(closeTo(height(card),idle), id + ' collapse endpoint');
    disclosureMotion.finishAll();
    check(body.hidden && closeTo(height(card),idle) && button.getAttribute('aria-expanded') === 'false', id + ' final collapse');
  }
  motion.matches = true; motion.onchange();
  check(custom.hidden && !custom.dataset.foldMotion && !nested.dataset.foldMotion,'reduce motion interrupted cleanup');
  summary.click(); check(!outer.open && !outer.getAnimations().length,'native reduced motion fallback');
  document.getElementById('result').textContent='PASS';
})().catch(error => { document.getElementById('result').textContent='FAIL ' + error.message; });
</script></body></html>`;
const outcome = browser.dom(fixture, 2000).match(/id="result"[^>]*>([^<]*)<\/pre>/)?.[1];
assert.equal(outcome, 'PASS');
console.log('Disclosures expand, collapse, reverse, move siblings, and settle in reduced motion.');
