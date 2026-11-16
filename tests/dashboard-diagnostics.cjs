const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
for(const m of html.matchAll(/<script>([\s\S]*?)<\/script>/g))new vm.Script(m[1]);
const elements={'recent-sessions':{innerHTML:''},'recent-sessions-status':{textContent:''}};
const escape=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let queue=[];const context=vm.createContext({document:{getElementById:id=>elements[id]},API_BASE:'',esc:escape,fmtTok:n=>String(n),fetch:()=>new Promise(r=>queue.push(r)),console});
vm.runInContext("Date.prototype.toLocaleTimeString = function(){return '12:00:00'}",context);
vm.runInContext(html.slice(html.indexOf('    function diagnosticText('),html.indexOf('    function loadInsightAll()')),context);
const row={timestamp:0,model:'<script>alert(1)</script>',statusCode:200,usageSource:'unknown',totalTokens:0,durationMs:1,requestId:'11111111-1111-1111-1111-111111111111',attempt:1,terminal:true};
let text=context.renderRecentRequest(row);assert(text.includes('用量未知'));assert(text.includes('未观测'));assert(!text.includes('0 tok'));assert(!text.includes('已确认交付'));assert(!text.includes('<script>'));assert(text.includes('&lt;script&gt;'));
row.diagnostics={schemaVersion:1,target:{provider:'fixture',model:'m'},requestedModel:'group/test',executionState:'unknown_execution',deliveryConfirmed:null,queuedMs:0,patienceWaitMs:2011,failureKind:'protocol',responsePhase:'headers',rulesVersion:null};
text=context.renderRecentRequest(row);for(const word of ['执行结果未知','协议响应无效','0 ms','2011 ms','结算时已发响应头','fixture / m'])assert(text.includes(word),word);
row.diagnostics.executionState='delivered';row.diagnostics.deliveryConfirmed=true;assert(context.renderRecentRequest(row).includes('完整写入已确认'));
row.attempt=0;assert(context.renderRecentRequest(row).includes('不计尝试'));
row.usageSource='partial';assert(context.renderRecentRequest(row).includes('部分已知用量'));
row.durationMs=-1;assert(!context.renderRecentRequest(row).includes('-1 ms'));
// Optional call evidence: explicit units, real dispatched variant, safe rendering and old-row unknowns.
row.callEvidence={schemaVersion:1,path:'/v1/chat/completions',method:'POST',entry:'model-api',sentModel:'gemini-3.8-flash-high',accountRef:'acct-012345678901234567890123',messageCount:2,toolCount:3,systemPresent:true,stream:true,effort:'high',physicalSends:1,source:'upstream',upstreamStatus:429,googleStatus:'RESOURCE_EXHAUSTED',upstreamRetryAfterSeconds:null};
text=context.renderRecentRequest(row);for(const word of ['/v1/chat/completions','gemini-3.8-flash-high','acct-012345678901234567890123','系统消息 有','工具 3','思考 high','RESOURCE_EXHAUSTED','未提供或未记录'])assert(text.includes(word),word);
assert(!text.includes('Eta'), 'entry path cannot establish a client identity');
row.callEvidence={...row.callEvidence,source:'gateway_cooldown',sentModel:null,physicalSends:0,upstreamStatus:null,googleStatus:null,localCooldownRemainingMs:58000,cooldownOriginRequestId:row.requestId,cooldownOriginStatus:429,cooldownOriginGoogleStatus:'RESOURCE_EXHAUSTED'};
text=context.renderRecentRequest(row);assert(text.includes('58000 ms')&&text.includes('本次未发出'));assert(text.includes('未发送或未记录'));assert(!text.includes('58000 秒'));
row.callEvidence.path='<img src=x onerror=alert(1)>';row.callEvidence.sentModel='<script>bad()</script>';text=context.renderRecentRequest(row);assert(!text.includes('<img')&&!text.includes('<script>'));assert(text.includes('&lt;img'));
delete row.callEvidence;assert(context.renderRecentRequest(row).includes('旧记录或该路径未采集'));
(async()=>{
const a=context.loadRecentSessions(),b=context.loadRecentSessions();queue[1]({ok:true,json:async()=>({sessions:[row]})});await b;const saved=elements['recent-sessions'].innerHTML;queue[0]({ok:false,status:503});await a;assert.equal(elements['recent-sessions'].innerHTML,saved);assert.equal(elements['recent-sessions-status'].textContent,'');
const c=context.loadRecentSessions();queue.at(-1)({ok:false,status:500});await c;assert.equal(elements['recent-sessions'].innerHTML,saved);assert.match(elements['recent-sessions-status'].textContent,/保留上次记录/);
console.log('dashboard diagnostics: safe projection, unknown coverage, zero waits and revision guards passed');
})().catch(e=>{console.error(e);process.exitCode=1});
