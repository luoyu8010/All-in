'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const {loadModelSelection,readFunction}=require('./model-selection-fixture.cjs');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const defaults=JSON.parse(fs.readFileSync('app/src/main/assets/default_config.json','utf8'));
let assertions=0;function eq(a,b,msg){assertions++;assert.deepEqual(a,b,msg);}function ok(v,msg){assertions++;assert(v,msg);}
class El {
 constructor(tag='div'){this.tagName=tag.toUpperCase();this.className='';this.children=[];this.style={};this.dataset={};this.value='';this.checked=false;this.listeners={};this._html='';this.textContent='';}
 set innerHTML(s){this._html=String(s);this.children=[];}get innerHTML(){return this._html;}
 appendChild(e){e.parentElement=this;this.children.push(e);return e;}replaceChildren(){this.children=[];this._html='';}
 addEventListener(type,fn){this.listeners[type]=fn;}setAttribute(k,v){this[k]=v;}focus(){doc.activeElement=this;}contains(e){return this===e||this.children.some(x=>x.contains(e));}
}
const nodes=new Map();let doc={activeElement:null,addEventListener(){},getElementById:id=>nodes.get(id)||null,createElement:tag=>new El(tag)};
for(const id of ['groups-container','group-model-search','group-model-provider','group-models-all','ping-model-block','ping-model-select','ping-model-status','ping-model-search','ping-models-all','ping-model-pager','ping-model-prev','ping-model-next','exposed-state','exposed-enabled','exposed-list'])nodes.set(id,new El(id==='group-model-provider'?'select':'div'));
let calls=0;const c=vm.createContext({document:doc,API_BASE:'',zcodeGiftModels:[],PROVIDER_CARD_IDS:[],groupDraft:null,groupsSig:'',pingSelectedProvider:null,pingCurrentModels:[],loadRoutingState(){},showToast(){},fetch:async()=>{calls++;throw Error('network forbidden in rendering tests');},buildModelTag:(pid,m)=>{const e=new El('span');e.className='model-tag';e.model=pid+'/'+m;return e;},modelDisplayName:(id,meta)=>meta&&meta.display_name||id,escapeHtml:s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;')});
loadModelSelection(c,html);
const names=['visibleProviders','pingModelsOf','groupsFingerprint','syncGroupDraft','groupEsc','selectableModels','renderGroups','isModelRefLiveSafe','pingVendorEntries','onPingVendorChange','onPingModelFilterChange','changePingModelPage','renderAllModelsTags','updateModelPoolSummary'];
vm.runInContext(names.map(n=>readFunction(html,n)).join('\n'),c);
const raw=Array.from({length:472},(_,i)=>'synthetic-'+String(i).padStart(3,'0'));
const pids=defaults.providers.filter(p=>p.id!=='zhipu_web'&&p.status!=='placeholder').map(p=>p.id);
const results=[];
function refs(fragment,pid){return [...fragment.matchAll(/<option value="([^"]+)"/g)].map(m=>m[1]).filter(m=>m.startsWith(pid+'/'));}
function groupOptions(pid){const card=nodes.get('groups-container').children[0].innerHTML;return refs(card.match(/<select data-action="add"[\s\S]*?<\/select>/)[0],pid);}
for(const pid of pids){
 for(const node of nodes.values()){node.value='';node.checked=false;}
 const provider={id:pid,name:pid,models:raw.slice(),accounts:[{token:'synthetic-not-real',plan:'synthetic-plan'}],modelMeta:{[raw[470]]:{display_name:'Readable Needle'}}};
 c.appConfig={providers:[provider],groups:[{id:'g',name:'group',strategy:'manual',pick:pid+'/'+raw[471],targets:[pid+'/'+raw[471]]}],exposed:{enabled:true,items:[]}};
 const baseline=JSON.stringify(c.appConfig.providers);c.groupDraft=null;c.groupsSig='';vm.runInContext("groupsServerSig=null;groupModelPages.clear();providerCatalogViews.clear();pingModelViewSig='';",c);
 c.renderGroups(true,false);eq(groupOptions(pid).length,0,pid+' hidden route');c.onPingVendorChange(pid,true);eq(refs(nodes.get('ping-model-select').innerHTML,pid).length,0,pid+' hidden ping');
 c.appConfig.exposed.items=raw.slice(0,3).map(m=>pid+'/'+m);c.renderGroups();eq(groupOptions(pid).length,3,pid+' route whitelist');c.onPingVendorChange(pid,true);eq(refs(nodes.get('ping-model-select').innerHTML,pid).length,3,pid+' ping whitelist');
 ok(nodes.get('groups-container').children[0].innerHTML.includes(pid+'/'+raw[471]),pid+' existing target retained');eq(c.groupDraft[0].pick,pid+'/'+raw[471],pid+' manual pick retained');
 nodes.get('group-models-all').checked=true;nodes.get('ping-models-all').checked=true;c.renderGroups(true,true);eq(groupOptions(pid).length,60,pid+' route bounded');c.onPingVendorChange(pid,true);eq(refs(nodes.get('ping-model-select').innerHTML,pid).length,60,pid+' ping bounded');
 const seen=new Set(refs(nodes.get('ping-model-select').innerHTML,pid));for(let page=1;page<8;page++){c.changePingModelPage(1);const list=refs(nodes.get('ping-model-select').innerHTML,pid);ok(list.length<=60,pid+' next page bounded');list.forEach(x=>seen.add(x));}eq(seen.size,472,pid+' every catalog ID reachable');
 nodes.get('group-model-search').value='Readable Needle';c.renderGroups(true,true);eq(groupOptions(pid),[pid+'/'+raw[470]],pid+' route display name search');nodes.get('ping-model-search').value='synthetic-470';c.onPingModelFilterChange();eq(refs(nodes.get('ping-model-select').innerHTML,pid),[pid+'/'+raw[470]],pid+' ping ID search');
 const container=new El();c.renderPagedProviderModels(pid,container,raw,provider.modelMeta);eq(container.children.filter(e=>e.className==='model-tag').length,60,pid+' directory bounded');
 vm.runInContext('providerCatalogViews.get('+JSON.stringify(pid)+').query="Readable Needle"',c);container.innerHTML='';c.renderPagedProviderModels(pid,container,raw,provider.modelMeta);eq(container.children.filter(e=>e.className==='model-tag').map(e=>e.model),[pid+'/'+raw[470]],pid+' directory search');
 eq(JSON.stringify(c.appConfig.providers),baseline,pid+' complete directory and credentials retained');results.push({provider:pid,hiddenRoute:0,hiddenPing:0,selectedRoute:3,selectedPing:3,pageMaximum:60,allReachable:seen.size,catalogIntact:true});
}
eq(calls,0,'filters/pages/render never fetch, infer, sync or write');
// Disabled pools and object key entries must not reappear via show-all.
for(const p of [{id:'p',enabled:false,accounts:[{token:'synthetic'}]}, {id:'p',accounts:[{enabled:false,token:'synthetic'}]}, {id:'p',keys:[{key:'synthetic',enabled:false}]},{id:'p',keys:[{key:'synthetic',off:true}]},{id:'p',status:'placeholder',keys:['synthetic']}])eq(c.providerHasCredential(p),false,'disabled not selectable');
eq(c.providerHasCredential({id:'p',keys:[{key:'synthetic',enabled:true}]}),true,'object key supported');
// Slash-containing IDs stay exact; do not confuse upstream path components with providers.
c.appConfig={providers:[{id:'cline',keys:['synthetic'],models:['vendor/model','cline-free/model']}],groups:[],exposed:{enabled:true,items:['cline/vendor/model']}};nodes.get('ping-models-all').checked=false;
eq(Array.from(c.selectionModelsOf(c.appConfig.providers[0],'ping')),['cline/vendor/model'],'slash ID exact matching');
// Editing the large whitelist keeps hidden selections and caps actual chips.
const exposedStart=html.indexOf('    let exposedSaving = false;'),exposedEnd=html.indexOf('    // ==================== 主题',exposedStart);
vm.runInContext(html.slice(exposedStart,exposedEnd),c);
vm.runInContext('exposedData={providers:[{id:"cline",name:"Cline",live:true,models:'+JSON.stringify(raw)+'}],groups:[]};exposedOpen.add("p:cline");exposedSel=new Set(["cline/synthetic-471"]);',c);
c.renderExposed();function descendants(n){return [n,...n.children.flatMap(descendants)];}
eq(descendants(nodes.get('exposed-list')).filter(n=>n.className.split(' ').includes('exp-chip')).length,60,'whitelist actual chips bounded');
vm.runInContext('exposedViews.set("p:cline",{query:"synthetic-471",page:0});',c);c.renderExposed();const chips=descendants(nodes.get('exposed-list')).filter(n=>n.className.split(' ').includes('exp-chip'));
eq(chips.length,1,'whitelist search reaches last model');ok(chips[0].className.includes(' on'),'hidden selection retained');
// Access rendering pages all matching IDs but copy retains the full match set.
const access=vm.createContext({document:doc,Map,Set,Date,API_BASE:'',copyText:s=>access.copied=s,AbortController,setTimeout,clearTimeout});
for(const id of ['access-model-list','access-model-pager','access-model-search','access-provider-filter','access-model-summary','access-copy-all','access-model-count','access-provider-count','access-group-count'])nodes.set(id,new El());
vm.runInContext(html.slice(html.indexOf('    // Gateway access inventory:'),html.indexOf('    // End gateway access inventory.')),access);
vm.runInContext('gatewayAccessReady=true;gatewayAccessRows='+JSON.stringify(raw.map(m=>({id:'cline/'+m,name:m,provider:'cline',providerName:'Cline',kind:'model'})))+';',access);
access.renderGatewayModels();const rowCount=()=>descendants(nodes.get('access-model-list')).filter(n=>n.className==='access-model-row').length;eq(rowCount(),60,'access actual rows bounded');
const first=nodes.get('access-model-list').children;vm.runInContext('gatewayAccessPage=1;',access);access.renderGatewayModels();eq(rowCount(),60,'access page two bounded');ok(nodes.get('access-model-list').children!==first,'access page actually redrawn');access.copyGatewayModelIds();eq(access.copied.split('\n').length,472,'copy all retains all matching IDs');
nodes.get('access-model-search').value='synthetic-471';access.renderGatewayModels();eq(rowCount(),1,'access search last ID');
console.log(JSON.stringify({passed:true,providers:pids.length,assertions,syntheticModelsPerProvider:472,results,realUpstreamRequests:0,realDeviceChanges:0},null,2));
