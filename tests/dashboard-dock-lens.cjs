'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const browser = require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const css = source.slice(0, source.indexOf('</head>')).match(/<style[^>]*>[\s\S]*?<\/style>/g).join('\n');
const navigation = source.match(/<nav[^>]*id="bottom-nav"[\s\S]*?<\/nav>/)[0];
const script = source.slice(source.indexOf('    function moveNavPill('), source.indexOf("    window.addEventListener('resize', () => moveNavPill"));
function run() {
  const result = document.getElementById('result');
  const assert = (ok, message) => { if (!ok) throw Error(message); };
  const nav = document.getElementById('bottom-nav');
  const buttons = [...nav.querySelectorAll('.tab-btn')];
  const pill = nav.querySelector('#nav-pill');
  const selected = () => buttons.findIndex(button => button.getAttribute('aria-current') === 'page');
  const center = el => { const rect = el.getBoundingClientRect(); return rect.x + rect.width / 2; };
  const event = (type, source, target = source) => buttons[source].dispatchEvent(new PointerEvent(type, {
    bubbles: true, pointerId: 7, isPrimary: true, button: 0, clientX: center(buttons[target]), clientY: 200
  }));
  const click = (index, detail) => buttons[index].dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, detail}));
  let calls = 0;
  window.switchTab = (name, button) => {
    calls++;
    buttons.forEach(item => item.classList.toggle('active', item === button));
    moveNavPill(button);
  };
  try {
    assert(nav.hasAttribute('data-lens-ready'), 'dock did not initialize');
    assert(nav.querySelectorAll('.nav-lens-item').length === 6 && pill.getAttribute('aria-hidden') === 'true', 'decorative layer leaks into accessibility');
    assert(getComputedStyle(nav.querySelector('.nav-lens-icon')).maskImage !== 'none', 'icons missing from lens');
    const restWidth = pill.getBoundingClientRect().width;
    event('pointerdown', 0); advance(12);
    assert(nav.hasAttribute('data-lens-lifted') && pill.getBoundingClientRect().width > restWidth * 1.4, 'press failed to enlarge lens');
    event('pointermove', 0, 3); advance(14);
    assert(Math.abs(center(pill) - center(buttons[3])) < 10, 'drag failed to follow pointer');
    assert(calls === 0 && selected() === 0, 'drag preview navigated prematurely');
    event('pointerup', 0, 3); click(0, 1); advance(66);
    assert(calls === 1 && selected() === 3, 'drag did not navigate exactly once');
    assert(!nav.hasAttribute('data-lens-lifted') && Math.abs(center(pill) - center(buttons[3])) < 1, 'lens did not settle');
    event('pointerdown', 3); event('pointermove', 3, 5); advance(8);
    event('pointercancel', 3, 5); click(3, 1); advance(66);
    assert(calls === 1 && selected() === 3 && !nav.hasAttribute('data-lens-lifted'), 'gesture cancellation changed tabs');
    const tap = index => {
      event('pointerdown', index);
      event('pointerup', index);
      click(index, 1);
    };
    const start = center(pill);
    const destination = center(buttons[0]);
    const span = start - destination;
    // A quick tap can release before even one animation frame has run.
    tap(0);
    assert(calls === 2 && selected() === 0, 'tap did not activate its tab immediately');
    assert(Math.abs(center(pill) - start) < 1, 'tap moved the lens before the first frame');
    const samples = [];
    for (let i = 0; i < 36; i++) {
      advance(1);
      samples.push({
        progress: (start - center(pill)) / span,
        lifted: nav.hasAttribute('data-lens-lifted'),
        width: pill.getBoundingClientRect().width
      });
    }
    const inTransit = samples.filter(frame => frame.progress > .22 && frame.progress < .75);
    assert(inTransit.length >= 3, 'tap skipped the intermediate positions');
    assert(inTransit.every(frame => frame.lifted && frame.width > restWidth * 1.2), 'quick tap traveled without a raised glass lens');
    assert(samples.some(frame => frame.progress > .8 && frame.lifted), 'lens lowered before approaching the destination');
    advance(40);
    assert(!nav.hasAttribute('data-lens-lifted') && Math.abs(center(pill) - destination) < 1, 'tapped lens did not land');
    assert(Math.abs(pill.getBoundingClientRect().width - restWidth) < 1, 'tapped lens did not return to resting size');

    tap(5); advance(5);
    const onItsWay = center(pill);
    const callsBeforeRetarget = calls;
    tap(1);
    assert(Math.abs(center(pill) - onItsWay) < 1 && selected() === 1, 'rapid tap reset the lens in flight');
    advance(66);
    assert(calls === callsBeforeRetarget + 1 && Math.abs(center(pill) - center(buttons[1])) < 1 && !nav.hasAttribute('data-lens-lifted'), 'rapid tap did not land once at the latest destination');
    click(4, 0); advance(6);
    assert(selected() === 4 && nav.hasAttribute('data-lens-lifted'), 'keyboard activation did not use the glass travel motion');
    click(1, 0); advance(66);
    assert(selected() === 1 && Math.abs(center(pill) - center(buttons[1])) < 1, 'keyboard click stopped working');

    motion.matches = true; motion.changed();
    tap(2);
    assert(!nav.hasAttribute('data-lens-lifted') && Math.abs(center(pill) - center(buttons[2])) < 1, 'reduced motion tap did not move immediately');
    const callsBeforeDrag = calls;
    event('pointerdown', 2); event('pointermove', 2, 4); advance(10);
    assert(!nav.hasAttribute('data-lens-lifted') && Math.abs(center(pill) - center(buttons[4])) < 1, 'reduced motion still animates');
    event('pointerup', 2, 4); click(2, 1);
    assert(calls === callsBeforeDrag + 1 && selected() === 4, 'reduced motion drag could not navigate');
    result.textContent = 'PASS';
  } catch (err) { result.textContent = 'FAIL ' + err.message; }
}
const setup = `
const motion = {matches:false, addEventListener(type, listener) { this.changed = listener; }};
window.matchMedia = () => motion;
Element.prototype.setPointerCapture = function() {};
let pending = new Map(), identifier = 0, clock = performance.now();
window.requestAnimationFrame = callback => { pending.set(++identifier,callback); return identifier; };
window.cancelAnimationFrame = id => pending.delete(id);
function advance(frames) {
  for (let i = 0; i < frames && pending.size; i++) {
    const queue = [...pending.values()]; pending.clear(); clock += 16;
    queue.forEach(callback => callback(clock));
  }
}
`;
for (const theme of ['light','dark']) {
  const page = `<!doctype html><html class="glass-ui" data-theme="${theme}"><head>${css}<style>#bottom-nav{width:336px}</style></head><body class="liquid-glass">${navigation}<pre id="result">WAIT</pre><script>${setup}${script}(${run.toString()})();</script></body></html>`;
  const outcome = browser.dom(page, 1500).match(/id="result"[^>]*>([^<]*)<\/pre>/)?.[1];
  assert.equal(outcome, 'PASS', theme + ': ' + outcome);
}
console.log('Dock lens press, drag, release, cancel, tap, keyboard, reduced motion: light and dark passed.');
