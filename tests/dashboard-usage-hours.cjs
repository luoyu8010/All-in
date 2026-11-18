'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const browser = require('./browser-fixture.cjs');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const css = [...source.matchAll(/<style(?:\s[^>]*)?>([\s\S]*?)<\/style>/g)].map(match => match[1]).join('\n');
const render = source.slice(source.indexOf('    function renderUsageHours('), source.indexOf('    let usageRevision = 0;'));
process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const frame = (theme, width) => '<!doctype html><html class="glass-ui" data-theme="'+theme+'"><style>'+css+'</style><body class="liquid-glass"><section class="container" style="width:'+width+'px;margin:0 auto"><div class="card"><div id="usage-hours"></div></div></section><pre id="result">WAIT</pre><script>'+render+`
function fmtFull(value) { return value.toLocaleString('en-US'); }
try {
  const check = (ok, message) => { if (!ok) throw Error(message); };
  const chart = document.getElementById('usage-hours');
  const get = selector => chart.querySelector(selector);
  check(document.querySelector('.container').getBoundingClientRect().width === ${width}, 'wrong content width');
  renderUsageHours([{hour: 0, tokens: 0, requests: 0}], 0, 7);
  check(chart.textContent === '所选时段暂无调用' && !get('.usage-hour-axis'), 'invalid empty state');
  renderUsageHours([], 2, 7);
  check(chart.textContent === '暂无可显示的时段数据', 'row without hour mistaken for empty ledger');
  const buckets = [{hour: 6, tokens: 0, requests: 1, tokensRecorded: true}, {hour: 10, tokens: 8500, requests: 3}, {hour: 12, tokens: 0, requests: 2, hasActivity: true}, {hour: 18, tokens: 15, requests: 0}, {hour: 23, tokens: 1, requests: 1}];
  renderUsageHours(buckets, 7, 7);
  const plot = get('.usage-hours-plot'), axis = [...chart.querySelectorAll('.usage-hour-axis span')];
  const hours = [...plot.children];
  check(hours.length === 24 && axis.map(tick => tick.textContent).join(',') === '00,06,12,18,23', 'wrong columns or tick labels');
  for (let i = 0; i < axis.length; i++) {
    const tick = axis[i].getBoundingClientRect();
    const cell = hours[[0,6,12,18,23][i]].getBoundingClientRect();
    check(tick.top === axis[0].getBoundingClientRect().top && tick.height < 19, 'tick wrapped');
    if (i > 0 && i < 4) check(Math.abs(tick.left + tick.width / 2 - cell.left - cell.width / 2) <= 1, 'tick alignment');
  }
  check(axis[0].getBoundingClientRect().left >= plot.getBoundingClientRect().left && axis[4].getBoundingClientRect().right <= plot.getBoundingClientRect().right + 1, 'endpoint outside plot');
  const zero = hours[6].firstChild, max = hours[10].firstChild, missing = hours[12].firstChild;
  check(zero.offsetHeight === 4 && max.offsetHeight === 66 && missing.offsetHeight === 4, 'wrong bar heights');
  check(getComputedStyle(zero).backgroundColor === 'rgb(130, 144, 164)' && getComputedStyle(missing).borderStyle === 'dashed', 'zero and missing look the same');
  hours[12].click(); check(get('.usage-hour-detail').textContent === '12:00 · 2 次请求 · Token 用量未记录', 'missing usage fabricated');
  hours[6].click(); check(get('.usage-hour-detail').textContent === '06:00 · 1 次请求 · 0 Token', 'real zero lost');
  hours[10].click(); check(get('.usage-hour-detail').textContent === '10:00 · 3 次请求 · 8,500 Token', 'incorrect click detail');
  hours[10].dispatchEvent(new KeyboardEvent('keydown', {key: 'End', bubbles: true}));
  check(document.activeElement === hours[23] && hours[23].getAttribute('aria-pressed') === 'true', 'keyboard selection');
  renderUsageHours(buckets, 7, 7); check(get('.usage-hour-detail').textContent.startsWith('23:00') && document.activeElement === get('[aria-pressed="true"]'), 'selection/focus lost on refresh');
  renderUsageHours(buckets, 7, 30); check(!get('[aria-pressed="true"]'), 'old selection carried across date range');
  renderUsageHours([{hour: 12, tokens: 0, requests: 2, hasActivity: true}], 2, 7);
  check(get('.usage-hour-detail').textContent.includes('暂无可绘制'), 'missing-only chart glosses over missing data');
  renderUsageHours([], 0, 7); check(!get('.usage-hours-plot') && !get('.usage-hour-detail'), 'stale data in empty state');
  document.getElementById('result').textContent = 'PASS';
} catch (err) { document.getElementById('result').textContent = 'FAIL ' + err.message; }
</script>`;
for (const theme of ['light', 'dark']) for (const width of [320, 360, 390]) {
  const dom = browser.dom(frame(theme, width), 2000);
  const result = dom.match(/id="result"[^>]*>([^<]*)<\/pre>/)?.[1];
  assert.equal(result, 'PASS', `${theme} ${width}px: ${result}`);
}
console.log('Usage chart passed in light/dark at 320, 360 and 390 CSS px content width.');
