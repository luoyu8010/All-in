const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const start=html.indexOf('    function cacheHitText('),end=html.indexOf('    let usageRevision',start);
assert(start>=0&&end>start);const context=vm.createContext({});vm.runInContext(html.slice(start,end),context);
for(const [input,expected] of [[null,'—'],[{},'—'],[{hitRate:null},'—'],[{hitRate:0,hitRateExact:0},'0%'],[{hitRate:0,hitRateExact:0.01},'<0.1%'],[{hitRate:80,hitRateExact:80},'80%'],[{hitRate:0.1,hitRateExact:0.06},'0.1%'],[{hitRate:0},'0%']])assert.equal(context.cacheHitText(input),expected);
assert(html.includes("set('uk-hit', cacheHitText(t), hitSub)"));assert(html.includes("parts.push('命中率 ' + cacheHitText(g))"));
assert(html.includes("? '缓存读未确认'"));console.log('dashboard cache usage: 11 unknown/zero/positive display and wiring checks passed');
