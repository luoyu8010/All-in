'use strict';
const fs=require('fs'),assert=require('assert');
const {readFunction}=require('./model-selection-fixture.cjs'),browser=require('./browser-fixture.cjs');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const code=readFunction(html,'renderAccountRouting')+'\n'+readFunction(html,'saveAccountRouting');
const page=String.raw`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><div id="settings"></div><pre id="result">WAIT</pre><script>
const API_BASE='',accountRoutingExpanded=new Set(),ACCOUNT_ROUTING_MODES={rotate:'轮流使用',usage:'用量均衡',pace:'到期优先'},providerWrites=new Set(),providerUnknown=new Set(),toasts=[],calls=[];
let providerWriteRevision=0,fail=0,appConfig={providers:[{id:'workbuddy',accountRouting:'rotate'},{id:'openai',accountRouting:'pace'}]};let server=JSON.parse(JSON.stringify(appConfig));
function providerToken(){return 'state-one'}function withProviderToken(o,t){return {...o,expectedStateToken:t}}function acknowledgeProviderToken(){}function showToast(s){toasts.push(s)}function applyProviderView(){}async function loadConfig(){appConfig=JSON.parse(JSON.stringify(server))}
async function fetch(url,options){calls.push({url,options});if(!options)return {ok:true,json:async()=>({ok:true,providerId:'workbuddy',mode:server.providers[0].accountRouting,basis:'recent_tokens',model:'synthetic-model'})};
 const body=JSON.parse(options.body);if(fail===1)return {ok:false,status:500,json:async()=>({ok:false})};if(fail===2)throw Error('synthetic disconnect');if(fail===3)return {ok:false,status:409,json:async()=>({ok:false})};server.providers.find(p=>p.id===body.providerId).accountRouting=body.mode;return {ok:true,status:200,json:async()=>({ok:true,providerId:body.providerId,mode:body.mode,stateToken:'state-two'})};}
${code}
let n=0;function check(v,m){n++;if(!v)throw Error(m)}
(async()=>{
 const root=document.getElementById('settings');renderAccountRouting(root,appConfig.providers[0]);
 const details=root.querySelector('details');check(details.querySelectorAll('button').length===3,'three modes');check(details.textContent.includes('不能保证积分严格等额'),'precision boundary missing');
 details.open=true;await details.ontoggle();check(details.textContent.includes('已回退'),'fallback basis not visible');
 const rerender=document.createElement('div');renderAccountRouting(rerender,appConfig.providers[0]);check(rerender.querySelector('details').open,'polling closed the routing settings');
 await saveAccountRouting('workbuddy','usage');check(server.providers[0].accountRouting==='usage'&&appConfig.providers[0].accountRouting==='usage','real save did not stick');check(server.providers[1].accountRouting==='pace','other supplier changed');
 const request=JSON.parse(calls.find(x=>x.options).options.body);check(request.expectedStateToken==='state-one'&&request.providerId==='workbuddy','CAS/provider scope missing');
 fail=1;await saveAccountRouting('workbuddy','pace');check(appConfig.providers[0].accountRouting==='usage','failed save published');
 fail=3;await saveAccountRouting('workbuddy','pace');check(appConfig.providers[0].accountRouting==='usage'&&toasts.at(-1).includes('状态已变化'),'conflict not handled');
 fail=2;await saveAccountRouting('workbuddy','pace');check(providerUnknown.has('workbuddy'),'unknown write not fenced');
 const before=calls.length;await saveAccountRouting('workbuddy','rotate');check(calls.length===before,'uncertain account settings overwritten');
 providerUnknown.clear();await saveAccountRouting('workbuddy','<img>');check(calls.length===before,'unknown strategy accepted');
 for(const id of ['workbuddy','workbuddy-ai','zcode','trae-cn','catpaw','deepseek_web','raccoon','loomy','deepseek','siliconflow','openai','anthropic','openrouter','tencent','qoder_cn','cline','codex','antigravity','github-copilot','minimax-code']){const box=document.createElement('div');renderAccountRouting(box,{id});check(box.querySelectorAll('button').length===3,'missing '+id);}
 document.getElementById('result').textContent='PASS '+n;
})().catch(e=>document.getElementById('result').textContent='FAIL '+e.message);
</script>`;
const out=browser.dom(page,4000);assert(/<pre id="result">PASS \d+<\/pre>/.test(out),out.match(/<pre id="result">.*?<\/pre>/)?.[0]);console.log('Account routing DOM: three modes, 20 suppliers, CAS, conflict, write failure, fallback and uncertain-result fence PASS');
