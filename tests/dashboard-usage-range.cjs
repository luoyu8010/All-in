'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const browser = require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const css = [...html.matchAll(/<style[^>]*>[\s\S]*?<\/style>/g)].map(match => match[0]).join('');
const card = html.slice(html.indexOf('<div class="card" id="usage-card">'), html.indexOf('<div class="heat-panel" id="usage-heatmap-card">'));
const script = html.slice(html.indexOf('    // ==================== 用量看板'), html.indexOf('    function moveNavPill('));
const escStart = html.indexOf('    function esc(s) {');
const escapeHtmlStart = html.indexOf('    function escapeHtml(s) {');
const closeFunction = String.fromCharCode(10) + '    }';
const escapeSource = [escStart, escapeHtmlStart].map(start => html.slice(start, html.indexOf(closeFunction, start) + closeFunction.length)).join(' ');
const fixture = `<!doctype html><html class="glass-ui"><head>${css}</head><body class="liquid-glass" style="width:340px">${card}</div><div id="usage-heatmap-card">热力图</div><div id="usage-state"></div><div id="usage-recent"></div><pre id="result">WAIT</pre><script>
const API_BASE = '';
const queued = [];
let reduced = false;
window.matchMedia = () => ({matches:reduced});
window.fetch = url => new Promise(resolve => queued.push({url,resolve}));
${escapeSource}
${script}
(async () => {
  const el = id => document.getElementById(id);
  const chart = el('usage-chart');
  const heatmap = el('usage-heatmap-card');
  const check = (value, message) => { if (!value) throw Error(message); };
  function resolve(request, days, empty = false) {
    check(request.url.includes('days=' + days + '&'), 'wrong request range');
    const daily = Array.from({length:days}, (_, idx) => ({day:new Date(Date.UTC(2026,10,18 - days + idx + 1)).toISOString().slice(0,10),tokens:empty ? 0 : 80 + (days-idx)*30,requests:empty ? 0 : 1}));
    request.resolve({ok:true,json:async()=>({ok:true,totals:{tokens:daily.reduce((sum,day)=>sum+day.tokens,0)},rows:empty?0:days,daily})});
  }
  const boot = loadUsage(1); resolve(queued.shift(),1); await boot;
  const today = chart.querySelector('.usage-col i').getBoundingClientRect();
  const expand = loadUsage(30);
  check(el('usage-d30').getAttribute('aria-pressed') === 'true' && !el('usage-range-note').hidden, 'range feedback while waiting');
  resolve(queued.shift(),30); await expand;
  const bars = chart.querySelectorAll('.usage-col i');
  check(bars.length === 30 && chart.scrollWidth === chart.clientWidth, '30 days fit on small chart');
  check(getComputedStyle(bars[0]).backgroundColor === 'rgb(35, 118, 219)', 'light blue');
  document.documentElement.dataset.theme = 'dark';
  check(getComputedStyle(bars[0]).backgroundColor === 'rgb(90, 171, 255)', 'dark blue');
  const bar = bars[bars.length - 1], animation = bar.getAnimations()[0];
  animation.pause(); animation.currentTime = 0;
  const first = bar.getBoundingClientRect();
  check(Math.abs(first.left - today.left) < 1 && Math.abs(first.height - today.height) < 1, 'shared date must start in its old position and apparent height');
  animation.currentTime = 240;
  check(bar.getBoundingClientRect().height < today.height, 'shared date interpolates');
  const stale = loadUsage(7), latest = loadUsage(1);
  resolve(queued.pop(),1); await latest;
  check(chart.querySelectorAll('.usage-retiring-bar').length === 29, 'extra days fade out');
  resolve(queued.pop(),7); await stale;
  check(chart.querySelectorAll('.usage-col').length === 1 && el('usage-d1').getAttribute('aria-pressed') === 'true', 'stale request replaced chart');
  const failure = loadUsage(7);
  queued.shift().resolve({ok:false,status:503}); await failure;
  check(el('usage-range-note').textContent.includes('仍显示「今天」') && chart.querySelectorAll('.usage-col').length === 1, 'failed request mislabels chart');
  reduced = true;
  const empty = loadUsage(7); resolve(queued.shift(),7,true); await empty;
  check(chart.textContent.includes('这段时间还没有调用记录') && !chart.getAnimations({subtree:true}).length, 'empty range and reduced motion');
  check(el('usage-range-note').hidden && heatmap === el('usage-heatmap-card') && heatmap.textContent === '热力图', 'heatmap remained independent');
  const quota = loadUsage(1);
  const quotaText = 'WorkBuddy 模型额度已用尽，预计 11-19 08:30:00 恢复<img src=x>';
  const cooldown = {t:Date.now(), model:'deepseek-v4.1-flash', status:429, terminal:true, upstreamErrorCode:'workbuddy_model_quota_exhausted', err:quotaText};
  queued.shift().resolve({ok:true,json:async()=>({ok:true,rows:2,recent:[cooldown,{...cooldown,terminal:false,err:'unconfirmed quota'}]})});
  await quota;
  const recent = el('usage-recent');
  check(recent.textContent.includes(quotaText) && !recent.querySelector('img'), 'confirmed quota reset appears as escaped text');
  check(!recent.textContent.includes('unconfirmed quota'), 'only confirmed terminal quota detail is rendered');
  reduced = false;
  const track = el('usage-range');
  const pill = track.querySelector('.usage-range-pill');
  const options = Array.from(track.querySelectorAll('.usage-range-option'));
  for (const theme of ['light','dark']) {
    document.documentElement.dataset.theme = theme;
    check(track.querySelectorAll('.usage-range-pill').length === 1, 'one shared slider ' + theme);
    check(options.every(option => {
      const style = getComputedStyle(option);
      return style.backgroundColor === 'rgba(0, 0, 0, 0)' && style.backgroundImage === 'none' && style.borderTopWidth === '0px' && style.boxShadow === 'none';
    }), 'individual options must be visually flat ' + theme);
  }
  document.documentElement.dataset.theme = 'light';
  const center = option => { const rect = option.getBoundingClientRect(); return rect.left + rect.width / 2; };
  function pointer(target, type, clientX) {
    target.dispatchEvent(new PointerEvent(type,{bubbles:true,isPrimary:true,pointerId:17,button:0,clientX,clientY:40}));
  }
  pointer(options[1],'pointerdown',center(options[1]));
  pointer(options[1],'pointermove',(center(options[1])+center(options[2]))/2);
  const preview = new DOMMatrixReadOnly(getComputedStyle(pill).transform).e;
  check(preview > options[1].offsetWidth && preview < 2*options[1].offsetWidth && !queued.length, 'slider follows finger without loading');
  pointer(options[1],'pointercancel',center(options[2]));
  check(!track.hasAttribute('data-dragging') && !queued.length, 'cancel does not switch range');
  pointer(options[1],'pointerdown',center(options[1]));
  pointer(options[1],'pointermove',center(options[2]));
  pointer(options[1],'pointerup',center(options[2]));
  options[1].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,detail:1}));
  check(queued.length === 1 && options[2].getAttribute('aria-pressed') === 'true', 'release commits once');
  resolve(queued.shift(),30);
  await Promise.resolve(); await Promise.resolve();
  el('result').textContent = 'PASS';
})().catch(error=>{document.getElementById('result').textContent = 'FAIL ' + error.message;});
</script></body></html>`;
const result = browser.dom(fixture, 1700).match(/<pre id="result">([^<]*)<\/pre>/)?.[1];
assert.equal(result, 'PASS');
console.log('Usage range geometry, blue bars, stale responses, empty state and reduced motion passed');
