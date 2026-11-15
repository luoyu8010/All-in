'use strict';
const fs=require('fs'),path=require('path'),assert=require('assert');
const {readFunction}=require('./model-selection-fixture.cjs'),browser=require('./browser-fixture.cjs');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
process.env.ALLIN_TEST_TMP=process.env.ALLIN_TEST_TMP||path.resolve('build/model-selection-browser');
const css=html.match(/<style>([\s\S]*?)<\/style>/)[1];
const helper=html.slice(html.indexOf('    const groupModelPages = new Map();'),html.lastIndexOf('    /**',html.indexOf('    function groupEsc(')));
const models=html.slice(html.indexOf('    // 把倍率数字'),html.indexOf('    function updateProviderKeys('));
const exposed=html.slice(html.indexOf('    let exposedSaving = false;'),html.indexOf('    // ==================== 主题',html.indexOf('    let exposedSaving = false;')));
const wanted=['visibleProviders','pingModelsOf','groupsFingerprint','syncGroupDraft','groupEsc','selectableModels','renderGroups','bindGroupEvents','isModelRefLiveSafe','pingVendorEntries','renderPingProviders','onPingVendorChange','onPingModelFilterChange','changePingModelPage','onGroupModelFilterChange'];
const functions=wanted.map(n=>readFunction(html,n)).join('\n');
const a=html.indexOf('<div class="model-list-tools"><input id="group-model-search"');
const groupTools=html.slice(a,html.indexOf('<div id="groups-container">',a));
const b=html.indexOf('<div class="model-list-tools"><label><input id="ping-models-all"');
const pingTools=html.slice(b,html.indexOf('            <div style="display:flex;justify-content',b));
const results=[];
for(const theme of ['light','dark'])for(const width of [360,390]){
 const page=`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><html data-theme="${theme}"><style>${css}body{padding:16px;margin:0;width:${width}px;box-sizing:border-box}.card{display:block!important;margin:14px 0;width:100%;box-sizing:border-box}.model-list-tools{max-width:100%}</style><body>
 <div class="card"><h2>路由模型选择</h2>${groupTools}<div id="groups-container"></div></div>
 <div class="card"><h2>模型测试选择</h2><select id="ping-vendor-select" onchange="onPingVendorChange(this.value)"></select><span id="ping-provider-hint"></span>${pingTools}<select id="ping-model-select"></select><p id="ping-model-status"></p></div></div>
 <div class="card"><h2>Cline 模型目录</h2><p id="model-pool-summary-cline"></p><div id="models-tags-cline"></div></div>
 <div class="card"><h2>模型白名单</h2><input id="exposed-enabled" type="checkbox" checked><div id="exposed-state"></div><div id="exposed-list"></div></div><pre id="result">WAIT</pre>
 <script>
 let appConfig,groupDraft=null,groupsSig='',pingSelectedProvider=null,pingProvidersSig='',pingCurrentModels=[],pingModelPage=0,pingModelViewSig='';const API_BASE='',zcodeGiftModels=[],PROVIDER_CARD_IDS=['cline'],providerBillingCache={};let inference=0,writes=0;
 function loadRoutingState(){}function showToast(){}function executeModelPingTest(){inference++;throw Error('unexpected inference')}function saveGroups(){writes++;throw Error('unexpected write')}function escapeHtml(x){return String(x).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;')}function esc(x){return escapeHtml(x)}async function fetch(){throw Error('unexpected network')}
 ${helper}${models}${functions}${exposed}
 const raw=Array.from({length:472},(_,i)=>'synthetic-'+String(i).padStart(3,'0'));
 appConfig={providers:[{id:'cline',name:'Cline',models:raw,keys:['synthetic-not-real'],modelMeta:{}}],groups:[{id:'g',name:'保留已有目标',strategy:'manual',pick:'cline/synthetic-471',targets:['cline/synthetic-471']}],exposed:{enabled:true,items:[]}};
 const original=JSON.stringify(appConfig.providers);function check(v,m){if(!v)throw Error(m)}function route(){return document.querySelectorAll('select[data-action=add] option[value^="cline/"]').length}function ping(){return document.querySelectorAll('#ping-model-select option[value^="cline/"]').length}
 try{renderGroups(true);bindGroupEvents();renderPingProviders(true);check(route()===0&&ping()===0,'hidden supplier still shown');check(groupDraft[0].targets[0]==='cline/synthetic-471'&&groupDraft[0].pick==='cline/synthetic-471','existing targets lost');
 appConfig.exposed.items=raw.slice(0,3).map(m=>'cline/'+m);renderGroups();renderPingProviders(true);check(route()===3&&ping()===3,'selected-only count');document.querySelector('#group-models-all').click();document.querySelector('#ping-models-all').click();check(route()===60&&ping()===60,'unbounded choice list');
 document.querySelector('[data-action=model-next]').click();check(route()===60&&document.querySelector('select[data-action=add] option[value="cline/synthetic-060"]'),'route page did not change');changePingModelPage(1);check(document.querySelector('#ping-model-select option[value="cline/synthetic-060"]'),'ping page did not change');
 const search=document.querySelector('#group-model-search');search.value='synthetic-470';search.dispatchEvent(new Event('input'));check(route()===1,'route search last ID');const psearch=document.querySelector('#ping-model-search');psearch.value='synthetic-470';psearch.dispatchEvent(new Event('input'));check(ping()===1,'ping search last ID');
 renderAllModelsTags();check(document.querySelectorAll('#models-tags-cline .model-tag').length===60,'catalog unbounded');let cs=document.querySelector('#models-tags-cline input');cs.focus();cs.value='synthetic-470';cs.dispatchEvent(new Event('input'));check(document.querySelectorAll('#models-tags-cline .model-tag').length===1,'catalog search');check(document.activeElement===document.querySelector('#models-tags-cline input'),'typing focus lost');
 exposedData={providers:[{id:'cline',name:'Cline',live:true,models:raw}],groups:[]};exposedOpen.add('p:cline');exposedSel=new Set(['cline/synthetic-471']);renderExposed();check(document.querySelectorAll('.exp-chip').length===60,'whitelist unbounded');check(JSON.stringify(appConfig.providers)===original,'directory modified');check(inference===0&&writes===0,'view triggered inference/write');check(document.body.scrollWidth<=${width}+1,'horizontal overflow');check([...document.querySelectorAll('.model-list-tools,.model-list-tools input,.model-list-tools select')].every(e=>e.getBoundingClientRect().right<=${width}+1),'controls exceed requested content width');
 document.querySelector('#result').textContent=JSON.stringify({passed:true,theme:'${theme}',width:${width},actualContentWidth:document.body.getBoundingClientRect().width,headlessViewport:innerWidth,routeMatches:route(),pingMatches:ping(),catalogMatches:1,whitelistPage:60,inference,writes});}catch(e){document.querySelector('#result').textContent='FAIL '+e.message}
 </script></body></html>`;
 const screenshot=path.resolve('../evidence/model-selection-20261115-mwhr/selection-'+theme+'-'+width+'.png');
 const out=browser.dom(page,4000,{windowSize:[width,1500],screenshot});const match=out.match(/<pre id="result">([\s\S]*?)<\/pre>/);assert(match&&match[1].includes('"passed":true'),match&&match[1]||out.slice(-500));results.push(JSON.parse(match[1]));
}
console.log(JSON.stringify({realChromiumDOM:true,layouts:results},null,2));
