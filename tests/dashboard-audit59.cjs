const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const root=path.resolve(__dirname,'..');
const html=fs.readFileSync(path.join(root,'app/src/main/assets/dashboard.html'),'utf8');
function slice(a,b){const start=html.indexOf(a),end=html.indexOf(b,start);assert(start>=0&&end>start);return html.slice(start,end);}
const auth=slice('    let AUTH_KEY = (function () {','    // 管理凭证只接受');
function authCase(url,failStorage=false){
 const location=new URL(url),store={},historyCalls=[];
 const c={URLSearchParams,location,window:{history:{replaceState(_state,_title,next){historyCalls.push(next);const u=new URL(next,location);location.href=u.href;}}},sessionStorage:{setItem(k,v){if(failStorage)throw new Error('unavailable');store[k]=v;},getItem(k){return store[k]||null;}}};
 vm.createContext(c);vm.runInContext(auth,c);
 return {url:location,historyCalls,key:vm.runInContext('AUTH_KEY',c),store};
}
function authChecks(){
 for(const url of ['http://127.0.0.1:3425/?key=fixture-query-secret&tab=usage#manage=fixture-manage',
                  'http://127.0.0.1:3425/?tab=usage#key=fixture-fragment-secret']){
  for(const failStorage of [false,true]){
   const r=authCase(url,failStorage);
   assert(!r.url.searchParams.has('key'),'Query key removed even when sessionStorage is unavailable');
   assert(!r.url.hash.startsWith('#key='),'Legacy key fragment removed');
   assert.equal(r.url.searchParams.get('tab'),'usage','Other query parameters preserved');
   if(url.includes('#manage='))assert.equal(r.url.hash,'#manage=fixture-manage','Management fragment still available to its own parser');
   assert(r.key.startsWith('fixture-'),'Existing inference key is still usable');
  }
 }
 const empty=authCase('http://127.0.0.1:3425/?key=&tab=usage');assert(!empty.url.searchParams.has('key'));
 const plain=authCase('http://127.0.0.1:3425/?tab=usage#normal');assert.equal(plain.historyCalls.length,0,'Unrelated URLs untouched');
}
function usageChecks(){
 const esc=slice('    function esc(s)','    function escHtml(s)');
 const usage=slice('    // ==================== 用量看板','    function moveNavPill(');
 const bad='"><img src=x onerror="window.__xss++">';
 const data={ok:true,totals:{requests:1,tokens:1,input:1,output:0},daily:[{day:'2026-'+bad+'-17',tokens:1,requests:bad}],hourly:[{hour:bad,tokens:1,requests:bad}],byModel:[],byProvider:[],sessions:[]};
 const page='<!doctype html><html><body><div id="usage-chart"></div><div id="usage-axis"></div><div id="usage-hours"></div><pre id="result">WAIT</pre><script>window.__xss=0;const API_BASE="";function loadHeatmap(){};'+esc+usage+
  '\nfetch=async()=>({ok:true,json:async()=>('+JSON.stringify(data)+')});(async()=>{try{await loadUsage();const chart=document.getElementById("usage-chart"),axis=document.getElementById("usage-axis"),hours=document.getElementById("usage-hours");if(document.querySelectorAll("img,svg").length)throw Error("untrusted markup became DOM");if(!chart.querySelector(".usage-col").title.includes("<img"))throw Error("safe tooltip text lost");if(!hours.querySelector("i").title.includes("<img"))throw Error("safe hour text lost");if(!axis.textContent.includes("<img"))throw Error("safe axis text lost");if(window.__xss)throw Error("script executed");document.getElementById("result").textContent="PASS";}catch(e){document.getElementById("result").textContent="FAIL "+e.message;}})();</script></body></html>';
 const dom=require('./browser-fixture.cjs').dom(page,2000);
 const outcome=(dom.match(/id="result"[^>]*>([^<]*)<\/pre>/)||[])[1];
 assert.equal(outcome,'PASS',outcome||'fixture script did not complete');
}
let ownTemporary;
if(!process.env.ALLIN_TEST_TMP){fs.mkdirSync(path.join(root,'build'),{recursive:true});ownTemporary=fs.mkdtempSync(path.join(root,'build','audit59-browser-'));process.env.ALLIN_TEST_TMP=ownTemporary;}
try{
 let failures=0;for(const [name,run] of [['credential URL',authChecks],['actual usage DOM',usageChecks]])try{run();console.log('PASS '+name);}catch(e){failures++;console.error('FAIL '+name+': '+e.message);}
 assert.equal(failures,0,'Audit59 panel failures');console.log('dashboard audit59 PASS');
}finally{if(ownTemporary)fs.rmSync(ownTemporary,{recursive:true,force:true,maxRetries:20,retryDelay:100});}
