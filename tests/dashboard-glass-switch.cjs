'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {dom} = require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const css = source.match(/\/\* The native switch remains[\s\S]*?(?=<\/style>)/)[0];
const helper = source.slice(source.indexOf('    function stepGlassSpring('), source.indexOf('    let dockLens;'));
const script = source.slice(source.indexOf('    function setupGlassSwitches('), source.indexOf('    const disclosureMotion'));
const controls = source.match(/<input[^>]*class="glass-switch"[^>]*>/g);
assert.equal(controls.length, 9);
const scenario = `
  Element.prototype.setPointerCapture = () => {};
  let pending = new Map(), identifier = 0, clock = 0;
  Object.defineProperty(performance, 'now', {value: () => clock});
  window.requestAnimationFrame = callback => { pending.set(++identifier, callback); return identifier; };
  window.cancelAnimationFrame = id => pending.delete(id);
  const advance = frames => {
    for (let i = 0; i < frames; i++) {
      clock += 16;
      const callbacks = [...pending.values()];
      pending.clear();
      callbacks.forEach(callback => callback(clock));
    }
  };
  let changes = 0;
  function togglePrivacy() { changes++; }
  function setGroupsEnabled() { changes++; }
  function setBareFallback() { changes++; }
`;
function exercise() {
  const check = (ok, reason) => { if (!ok) throw Error(reason); };
  const input = document.getElementById('privacy-enabled');
  const shell = input.parentElement;
  const thumb = shell.querySelector('.glass-switch-thumb');
  const glass = shell.querySelector('.glass-switch-glass');
  const solid = shell.querySelector('.glass-switch-solid');
  const track = shell.querySelector('.glass-switch-track');
  const trackColor = () => Number(getComputedStyle(track, '::before').opacity);
  const opacity = el => Number(getComputedStyle(el).opacity);
  const center = () => thumb.getBoundingClientRect().left + thumb.offsetWidth / 2 - shell.getBoundingClientRect().left;
  const pointer = (type, x) => input.dispatchEvent(new PointerEvent(type, {bubbles:true,pointerId:7,isPrimary:true,button:0,clientX:x}));
  const click = () => input.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,detail:1}));
  const isResting = (checked) => input.checked === checked && opacity(glass) === 0 && opacity(solid) === 1 &&
    Math.abs(center() - (checked ? 32 : 14)) < .1 && trackColor() === Number(checked);
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  try {
    check(document.querySelectorAll('.glass-switch-shell').length === 9, 'switch coverage');
    check(isResting(false), 'initial closed appearance');
    pointer('pointerdown', 14); advance(32);
    if (!reduce) {
      check(opacity(glass) > .99 && opacity(solid) < .01 && thumb.offsetWidth >= 36, 'press did not hold a raised transparent lens');
    }
    pointer('pointermove', 23); advance(35);
    if (!reduce) check(center() > 21 && center() < 25 && trackColor() > .4 && trackColor() < .6, 'track and lens did not follow the finger');
    pointer('pointermove', 32); advance(90);
    check(!input.checked && changes === 0, 'drag committed before release');
    if (!reduce) {
      check(opacity(glass) > .99 && opacity(solid) < .01 && trackColor() === 1, 'long hold dropped the glass at the endpoint');
      check(thumb.offsetHeight > track.offsetHeight, 'raised lens did not extend above track');
    }
    pointer('pointerup', 32); click(); advance(90);
    check(changes === 1 && isResting(true), 'release did not commit once to white on green');
    pointer('pointerdown', 32); pointer('pointermove', 14); advance(12);
    pointer('pointercancel', 14); click(); advance(90);
    check(changes === 1 && isResting(true), 'cancel changed the setting');
    pointer('pointerdown', 32); pointer('pointerup', 32); click(); advance(4);
    if (!reduce) check(opacity(glass) > .15 && center() > 14 && center() < 32, 'tap skipped raised travel');
    advance(90);
    check(changes === 2 && isResting(false), 'tap did not land white on gray');
    input.checked = true;
    check(isResting(true) && !pending.size, 'external state did not update idle switch');
    input.disabled = true;
    pointer('pointerdown', 32); pointer('pointerup', 32); input.click(); advance(16);
    check(changes === 2 && isResting(true), 'disabled switch animated or changed');
    document.querySelector('#result').textContent = 'PASS';
  } catch (error) {
    document.querySelector('#result').textContent = 'FAIL ' + error.message;
  }
}
for (const [theme, reduce] of [['light', false], ['dark', false], ['light', true]]) {
  const media = reduce ? `window.matchMedia = () => ({matches:true,addEventListener(){}});` : '';
  const html = `<!doctype html><html data-theme="${theme}"><head><style>${css}</style></head><body class="liquid-glass">${controls.join(' ')}<pre id="result">WAIT</pre><script>${media}${scenario}${helper}${script}(${exercise.toString()})();</script></body></html>`;
  assert.equal(dom(html,1000).match(/id="result"[^>]*>([^<]+)/)?.[1], 'PASS', `${theme} reduced=${reduce}`);
}
console.log('Nine glass switches: raised hold, drag preview, release, cancellation, tap, and reduced motion verified.');
