'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
let sent,checks=0;const all={checked:false};
function element(){return {children:[],style:{},innerText:'',innerHTML:'',appendChild(x){this.children.push(x)}};}
const p={id:'openrouter',enabled:true,keys:['SYNTHETIC_NOT_REAL'],models:['openrouter/auto','openrouter/free','anthropic/claude-example','bare'],modelMeta:{}};
const c=vm.createContext({document:{createElement:element,getElementById:id=>id.endsWith('-models-all')?all:null},appConfig:{providers:[p],exposed:{enabled:false,items:[]}},pingModelsOf:prov=>prov.models,zcodeGiftModels:[],executeModelPingTest:ref=>sent=ref});
const a=html.indexOf('    function fmtMultiplier(v)'),b=html.indexOf('    /** 供应商是否',a);vm.runInContext(html.slice(a,b),c);
require('./model-selection-fixture.cjs').loadModelSelection(c,html);
const expected=p.models.map(raw=>'openrouter/'+raw);
for(const scope of ['group','ping']){
  assert.deepEqual(Array.from(c.selectionModelsOf(p,scope)),expected);checks++;
  for(let i=0;i<p.models.length;i++){c.buildModelTag(p.id,p.models[i],{}).onclick();assert.equal(sent,expected[i]);checks++;}
  c.appConfig.exposed={enabled:true,items:[expected[0]]};assert.deepEqual(Array.from(c.selectionModelsOf(p,scope)),[expected[0]]);checks++;
  assert.deepEqual(Array.from(c.selectionModelsOf(p,scope,'openrouter/openrouter/auto')),[expected[0]]);checks++;
  all.checked=true;assert.deepEqual(Array.from(c.selectionModelsOf(p,scope)),expected);checks++;
  all.checked=false;c.appConfig.exposed={enabled:false,items:[]};
}
assert.deepEqual(p.models,['openrouter/auto','openrouter/free','anthropic/claude-example','bare']);checks++;
console.log('dashboard-prefix-ids PASS checks='+checks+'; candidate, card, whitelist and show-all preserve one full identity');
