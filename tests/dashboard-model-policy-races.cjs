'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
let pending=[],invalidations=0;
const state={innerText:''},enabled={checked:true};
const c=vm.createContext({document:{getElementById:id=>id==='exposed-state'?state:id==='exposed-enabled'?enabled:null},API_BASE:'',visibleProviders:x=>x||[],showToast(){},appConfig:{exposed:{enabled:false,items:[]}},loadConfig(){},renderGroups(){},renderPingProviders(){},gatewayAccessPolicyChanged(){invalidations++},fetch:(url,options)=>new Promise((resolve,reject)=>pending.push({options,resolve,reject}))});
vm.runInContext(html.slice(html.indexOf('    let exposedSaving = false;'),html.indexOf('    // ==================== 主题')),c);
const run=s=>vm.runInContext(s,c), reply=data=>({json:async()=>data});
(async()=>{
 const old=c.loadExposed();c.toggleExposed('cline/new');pending.shift().resolve(reply({ok:true,enabled:false,items:[],providers:[],groups:[]}));await old;assert(run('exposedSel.has("cline/new")'),'late GET must not erase draft');
 const first=c.saveExposed();assert.equal(pending.length,1);await c.saveExposed();assert.equal(pending.length,1,'no overlapping policy writes');c.toggleExposed('cline/latest');const sent=JSON.parse(pending[0].options.body);assert.deepEqual(sent.items,['cline/new']);pending.shift().resolve(reply({ok:true,count:1}));await first;assert.deepEqual(c.appConfig.exposed.items,['cline/new'],'only acknowledged policy takes effect');assert(run('exposedSel.has("cline/latest")'),'in-flight edits retained');assert.equal(invalidations,1);
 const second=c.saveExposed();pending.shift().resolve(reply({ok:true,count:2}));await second;assert(c.appConfig.exposed.items.includes('cline/latest'));const snapshot=JSON.stringify(c.appConfig.exposed);c.toggleExposed('cline/failed');const failed=c.saveExposed();pending.shift().reject(Error('lost response'));await failed;assert.equal(JSON.stringify(c.appConfig.exposed),snapshot,'failed save cannot apply draft');assert.equal(run('exposedSaving'),false,'save failure releases guard');
 // Reuse the actual authoritative inventory fixture, without executing its suite.
 const fixture=fs.readFileSync('tests/dashboard-gateway-access.cjs','utf8');const prefix=fixture.slice(0,fixture.indexOf('(async()=>{'));
 const page=new Function('require',prefix+'\nreturn page;')(require)();const p=page;
 const model=(id,provider)=>({id,name:id,provider,providerName:provider,kind:'model'});
 p.queue.push({ok:true,models:[model('cline/old','cline')]});await p.c.refreshGatewayAccess(false);
 let release;p.queue.push(()=>new Promise(r=>release=r));p.run('gatewayAccessLast=0');const request=p.c.refreshGatewayAccess(false);await Promise.resolve();p.queue.push({ok:true,models:[model('openai/current','openai')]});p.c.gatewayAccessPolicyChanged();assert.equal(p.run('gatewayAccessRows.length'),0,'policy change immediately clears copyable old IDs');release({ok:true,json:async()=>({ok:true,models:[model('cline/stale','cline')]})});await request;for(let i=0;i<10;i++)await Promise.resolve();assert.equal(p.run('gatewayAccessRows[0].id'),'openai/current','old request cannot repopulate hidden models');assert(p.calls.every(x=>x.url==='/api/gateway-access'),'policy refresh is read-only, never upstream sync');
 console.log('model policy races PASS: stale reads, overlapping saves, draft retention, failed saves and pending inventory invalidation');
})().catch(e=>{console.error(e);process.exitCode=1});
