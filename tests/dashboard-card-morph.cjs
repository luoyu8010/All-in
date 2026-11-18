'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const browser = require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
function slice(first, end) { return html.slice(html.indexOf(first), html.indexOf(end, html.indexOf(first))); }
const script = slice('    const cardMorph =', '    function checkinText(') + slice('    function showProviderView(', '    // Android system back');
const css = html.slice(0, html.indexOf('</head>')).match(/<style[^>]*>[\s\S]*?<\/style>/g).join('');
function verify() {
  const get = id => document.getElementById(id);
  const check = (condition, comment) => { if (!condition) throw Error(comment); };
  const near = (a, b) => Math.abs(a - b) < 2;
  function checkMorph(from, to, caption) {
    const layer = document.querySelector('.card-morph-layer');
    check(layer && layer.getAttribute('aria-hidden') === 'true' && getComputedStyle(layer).pointerEvents === 'none', caption + ' inert layer');
    check(!layer.querySelector('input,button,a'), caption + ' overlay copied controls');
    check(layer.querySelector('.card-morph-icon'), caption + ' missing travelling icon');
    const surface = layer.querySelector('.card-morph-surface');
    const animation = surface.getAnimations()[0];
    animation.pause();
    animation.currentTime = 0;
    const first = surface.getBoundingClientRect();
    check(near(first.top, from.top) && near(first.height, from.height), caption + ' lost origin');
    const duration = animation.effect.getTiming().duration;
    check(duration >= 420 && duration <= 500, caption + ' pacing');
    const keys = animation.effect.getKeyframes();
    check(keys.every(key => !('width' in key) && !('height' in key) && !('borderRadius' in key)), caption + ' layout/paint animated');
    animation.currentTime = duration * .35;
    const middle = surface.getBoundingClientRect();
    check(Math.abs(middle.height - from.height) > 3 && Math.abs(middle.height - to.height) > 2, caption + ' did not interpolate');
    animation.currentTime = duration - 1;
    const last = surface.getBoundingClientRect();
    check(near(last.top, to.top) && near(last.height, to.height), caption + ' did not land on target');
    cardMorph.cancel();
    check(!document.querySelector('.card-morph-layer'), caption + ' overlay cleanup');
  }
  try {
    const entry = get('checkin-home-entry');
    const hero = get('daily');
    scrollTo({top: 180, behavior:'instant'});
    let origin = entry.getBoundingClientRect();
    const scroll = scrollY;
    showCheckinCenter(true);
    check(checkinVisible() && document.activeElement === get('checkin-heading') && loads === 1, 'check-in open behavior');
    check(cardMorph.settling(hero) instanceof Promise, 'check-in write did not have a settle point');
    checkMorph(origin, hero.getBoundingClientRect(), 'check-in open');
    check(!cardMorph.settling(hero), 'settle point leaked');
    const run = document.getElementById('checkin-run-all');
    const luminance = color => {
      const rgb = color.match(/\d+/g).slice(0, 3).map(channel => Number(channel) / 255);
      const linear = rgb.map(channel => channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4);
      return linear[0] * .2126 + linear[1] * .7152 + linear[2] * .0722;
    };
    for (const theme of ['light', 'dark']) {
      document.documentElement.dataset.theme = theme;
      for (const disabled of [true, false]) {
        run.disabled = disabled;
        const {color, backgroundColor, opacity} = getComputedStyle(run);
        const values = [luminance(color), luminance(backgroundColor)].sort((a, b) => a - b);
        check(opacity === '1' && (values[1] + .05) / (values[0] + .05) >= 4.5, theme + ' check-in label contrast ' + JSON.stringify({disabled,color,backgroundColor,opacity}));
      }
    }
    origin = hero.getBoundingClientRect();
    showCheckinCenter(false, true);
    check(!checkinVisible() && scrollY === scroll && document.activeElement === entry.querySelector('button'), 'check-in scroll/focus');
    checkMorph(origin, entry.getBoundingClientRect(), 'check-in back');
    const source = get('supplier');
    const destination = document.querySelector('.provider-detail-heading');
    origin = source.getBoundingClientRect();
    showProviderView('detail', 'example', source);
    check(document.activeElement === destination.querySelector('h3'), 'supplier heading focus');
    checkMorph(origin, destination.getBoundingClientRect(), 'supplier open');
    origin = destination.getBoundingClientRect();
    showProviderView('list');
    check(scrollY === scroll && document.activeElement === source.querySelector('button'), 'supplier scroll/focus');
    checkMorph(origin, source.getBoundingClientRect(), 'supplier back');
    reduced = true;
    showProviderView('detail', 'example', source);
    check(providerView === 'detail' && !document.querySelector('.card-morph-layer'), 'reduced-motion fallback');
    get('result').textContent = 'PASS';
  } catch (error) { get('result').textContent = 'FAIL ' + error.message; }
}
const page = `<!doctype html><html class="glass-ui"><head>${css}<style>html{scroll-behavior:auto}section{margin:0 12px}#checkin-home-entry,#supplier{margin-top:20px;height:64px}#daily,.provider-detail-heading{height:130px}body{min-height:1800px}</style></head><body class="liquid-glass">
<div style="height:210px"></div><section id="overview-main-view"><div class="card" id="checkin-home-entry"><span data-card-morph-icon>◯</span><button>签到</button></div></section><section id="checkin-center-view" hidden><div id="daily" class="checkin-hero"><span data-card-morph-icon>◯</span><button class="btn checkin-primary" id="checkin-run-all" disabled>Test</button><h2 tabindex="-1" id="checkin-heading">签到</h2></div></section>
<section id="provider-list-view"><div class="provider-compact" id="supplier"><button class="provider-compact-main" data-provider-focus="row:example"><span data-card-morph-icon>◯</span>供应商</button></div></section><section id="provider-detail-info" hidden><div class="provider-detail-heading"><span data-card-morph-icon>◯</span><h3 tabindex="-1">详情</h3></div></section><pre id="result">WAIT</pre>
<script>let loads=0, reduced=false,providerView='list',providerDetail='',providerListScroll=0;
window.matchMedia=()=>({get matches(){return reduced}});
function checkinLoad(){loads++}
function flushCommercialRender(){}
function renderProviderList(){}
function applyProviderView(){document.getElementById('provider-list-view').hidden=providerView!=='list';document.getElementById('provider-detail-info').hidden=providerView==='list'}
${script}(${verify.toString()})();</script></body></html>`;
const output = browser.dom(page, 2000).match(/id="result"[^>]*>([^<]*)<\/pre>/)?.[1];
assert.equal(output, 'PASS');
console.log('Check-in and supplier card geometry, focus, scroll, cancellation and reduced motion passed.');
