'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const browser = require('./browser-fixture.cjs');
process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
function part(first, last) {
  const start = source.indexOf(first), end = source.indexOf(last, start + 1);
  assert(start >= 0 && end > start, first);
  return source.slice(start, end);
}
const css = source.slice(0, source.indexOf('</head>')).match(/<style[^>]*>[\s\S]*?<\/style>/g).join('\n');
const tabs = part('    const tabMotion =', '    // Gateway access inventory');
const sheet = part('    let accountSheetMotion', '    function renderAccountModalList(');
const dismiss = part('    function closeAccountModal()', '    async function mutateProviderAccount(');
const copy = part('    const copyFeedback', '    function startEditAuthKey(');
const billing = part('    const providerBillingCache', '    function renderProviderList(');
const markup = part('  <div id="account-manage-modal"', '  <!-- 首启向导');
async function checks() {
  const result = document.getElementById('result');
  const $ = selector => document.querySelector(selector);
  const check = (value, message) => { if (!value) throw Error(message); };
  try {
    const a = $('#tab-overview'), b = $('#tab-providers');
    window.scrollTo({top:140,behavior:'instant'});
    await switchTab('providers');
    check(b.classList.contains('active') && window.scrollY === 0 && a.inert && a.getAttribute('aria-hidden') === 'true', 'navigation entry');
    const incoming = b.getAnimations()[0], retiring = a.getAnimations()[0];
    check(incoming.effect.getTiming().duration === 440 && /translate3d\([1-9]/.test(incoming.effect.getKeyframes()[0].transform), 'direction and speed');
    incoming.pause(); retiring.pause(); incoming.currentTime = 112; retiring.currentTime = 112;
    const beforeReverse = a.getBoundingClientRect().left;
    await switchTab('overview');
    const reversal = a.getAnimations()[0]; reversal.pause(); reversal.currentTime = 0;
    check(Math.abs(a.getBoundingClientRect().left - beforeReverse) < 1.5 && window.scrollY === 140, 'reversal or remembered scroll');
    reduced = true; await switchTab('providers'); await switchTab('overview');
    check(!a.getAnimations().length && !b.inert && !b.hasAttribute('data-tab-departing'), 'motion preference or transition cleanup');
    reduced = false;
    $('#launcher').focus(); manageProviderAccounts('fixture');
    const drawer = $('.account-sheet'), modal = $('#account-manage-modal');
    check(modal.style.display === 'flex' && getComputedStyle(modal).alignItems === 'flex-end', 'drawer placement');
    check(drawer.getAnimations()[0].effect.getTiming().duration === 480, 'drawer pacing');
    $('#menu-trigger').click();
    check(!$('#fixture-menu').hidden && $('#menu-trigger').getAttribute('aria-expanded') === 'true' && document.activeElement === $('#menu-item'), 'anchored action menu');
    closeAccountActions(false);
    check($('#fixture-menu').hidden, 'menu cleanup');
    closeAccountModal();
    check(modal.style.display === 'none' && document.activeElement === $('#launcher'), 'drawer return focus');
    copyText('synthetic', '模型 ID 已复制', $('#copy'));
    check($('#copy').getAttribute('data-copy-feedback') && $('#copy-live-status').textContent === '模型 ID 已复制', 'inline copy acknowledgement');
    check(!document.querySelector('textarea'), 'temporary clipboard field remained');
    const panel = $('#provider-billing-panel');
    providerBillingCache.fixture = billingSnapshot(null,{ok:true,accounts:[{accountId:'a',allowance:{remaining:8}}],models:{}},1);
    renderProviderBilling(panel,'fixture');
    const request = refreshProviderBilling('fixture');
    check(panel.textContent.includes('刷新中') && panel.textContent.includes('剩余 8') && panel.querySelector('button').disabled, 'pending quota destroyed the old value');
    await refreshProviderBilling('fixture');
    check(fetches === 1, 'duplicate billing refresh');
    resolveBilling({ok:false,json:async()=>({})}); await request;
    check(panel.textContent.includes('刷新失败') && panel.textContent.includes('剩余 8') && !panel.querySelector('button').disabled, 'quota failure lost its snapshot');
    result.textContent = 'PASS';
  } catch (error) { result.textContent = 'FAIL ' + error.message; }
}
const page = `<!doctype html><html class="glass-ui"><head>${css}<style>html{scroll-behavior:auto}.tab-pane{height:900px}#launcher{position:fixed;bottom:12px}</style></head><body class="liquid-glass"><main><section class="tab-pane active" id="tab-overview">首页</section><section class="tab-pane" id="tab-providers">供应商</section></main><button id="launcher">管理账号</button><button id="copy">复制 ID</button><div id="copy-live-status"></div><div id="provider-billing-panel"></div>${markup}<pre id="result">WAIT</pre><script>
let reduced=false,currentModalProviderId=null,providerView='detail',providerDetail='fixture',fetches=0,resolveBilling;
const API_BASE=''; window.matchMedia=()=>({get matches(){return reduced}});
function moveNavPill(){} function loadConfig(){return Promise.resolve()} function renderAllModelsTags(){} function showToast(){} function billingAllowanceText(a){return String(a.remaining ?? '')}
function renderAccountModalList(){document.getElementById('acc-modal-list').innerHTML='<button id="menu-trigger">更多操作</button><div id="fixture-menu" class="account-action-popover"><button id="menu-item">备注</button></div>';bindAccountActions(document.getElementById('menu-trigger'),document.getElementById('fixture-menu'))}
document.execCommand=()=>true;
function fetch(){fetches++;return new Promise(resolve=>{resolveBilling=resolve})}
${tabs}\n${sheet}\n${dismiss}\n${copy}\n${billing}\n(${checks.toString()})();</script></body></html>`;
const output = browser.dom(page, 2000).match(/id="result"[^>]*>([^<]*)<\/pre>/)?.[1];
assert.equal(output, 'PASS');
console.log('Tab reversal, restored scroll, drawer, anchored actions, copy, and quota feedback passed.');
