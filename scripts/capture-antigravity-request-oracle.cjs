// Pure request oracle from an explicitly supplied, hash-pinned 0.2.1 source. No auth, HTTP or plugin activation.
const fs=require('node:fs'),vm=require('node:vm'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const input=process.argv[2],output=process.argv[3];if(!input||!output)throw Error('usage: node capture-antigravity-request-oracle.cjs pinned-index.mjs fixture.json');
const bytes=fs.readFileSync(input),sha=crypto.createHash('sha256').update(bytes).digest('hex');
assert.equal(sha,'10cd2ca45fa194a4270d189ec958aa27e961abe4f0d086d4af66a40435a0d4d9');
let code=bytes.toString('utf8').replace(/^import .* from .*\r?\n/gm,'').replace(/export default /g,'').replace(/export async function /g,'async function ').replace(/export const _internal =/,'globalThis.oracle =');
const denied=()=>{throw Error('Network/auth forbidden in request oracle');};
const context=vm.createContext({crypto,http:{createServer:denied},fetch:denied,process:{platform:'linux',arch:'x64'},URL,URLSearchParams,structuredClone,TextEncoder,TextDecoder,AbortSignal,console:{log:()=>{}},setTimeout:denied,clearTimeout:()=>{}});
vm.runInContext(code,context,{timeout:2000});const upstream=context.oracle;
const schemas=[
 ['simple',{type:'object',properties:{path:{type:'string',description:'synthetic path'}},required:['path']}],
 ['local-ref',{$defs:{Position:{type:'object',properties:{x:{type:'integer'},y:{type:'integer'}},required:['x','y']}},type:'object',properties:{point:{$ref:'#/$defs/Position'}}}],
 ['escaped-ref',{$defs:{'a/b~c':{type:'string',pattern:'^x+$'}},type:'object',properties:{name:{$ref:'#/$defs/a~1b~0c'}}}],
 ['union',{type:'object',properties:{value:{anyOf:[{type:'null'},{type:'string'}],description:'optional'}}}],
 ['root-oneof',{oneOf:[{type:'object',properties:{a:{type:'string'}},required:['a']},{type:'object',properties:{b:{type:'number'}}}]}],
 ['shape-union',{type:'object',properties:{kind:{type:'string'}},oneOf:[{properties:{a:{type:'string'},common:{type:'boolean'}},required:['common','a']},{properties:{b:{type:'number'},common:{type:'boolean'}},required:['common','b']}],required:['kind']}],
 ['allof',{allOf:[{type:'object',properties:{path:{type:'string'}},required:['path']},{type:'object',properties:{count:{type:'integer',minimum:1}},required:['count']}]}],
 ['bounded-array',{type:'object',properties:{items:{type:'array',items:{type:'string'},minItems:1,maxItems:20}},additionalProperties:false}],
 ['type-preference',{type:['string','null','array'],items:{type:'integer'}}],
 ['string-enum',{type:'string',enum:['','  ','yes','no'],const:'yes'}],
 ['number-enum',{type:'integer',enum:[1,2,3],minimum:1,maximum:3}],
 ['constraints',{type:'object',properties:{code:{type:'string',minLength:2,maxLength:12,pattern:'^[a-z]+$'},n:{type:'number',exclusiveMinimum:2,maximum:10}},required:['code','not_there',42],propertyOrdering:['code','n']}],
 ['boolean-properties',{type:'object',properties:{yes:true,no:false},required:['yes','no']}],
 ['false-items',{type:'array',items:false}],
 ['uniform-tuple',{type:'array',items:[{type:'string'},{type:'string'}]}],
 ['mixed-tuple',{type:'array',items:[{type:'string'},{type:'integer'}]}],
 ['prefix-items',{type:'array',prefixItems:[{type:'string'},{type:'string'}]}],
 ['self-ref',{type:'object',properties:{next:{$ref:'#'}}}],
 ['mutual-ref',{$defs:{A:{type:'object',properties:{b:{$ref:'#/$defs/B'}}},B:{type:'object',properties:{a:{$ref:'#/$defs/A'}}}},type:'object',properties:{root:{$ref:'#/$defs/A'}}}],
 ['missing-ref',{type:'object',properties:{x:{$ref:'#/$defs/missing'}}}],
 ['ref-array-path',{definitions:{choices:[{type:'string'}]},type:'object',properties:{x:{$ref:'#/definitions/choices/0'}}}],
 ['type-array-prefers-object',{type:['null','string','object'],properties:{x:{type:'string'}}}],
 ['allof-nullable',{type:'object',properties:{x:{allOf:[{type:'string'},{minLength:1}]},y:{oneOf:[{type:'null'},{type:'number'}]}}}],
 ['no-format',{type:'object',properties:{x:{type:'string',format:'uri',default:'ignored'}},$schema:'https://example.invalid/schema'}]
].map(([name,input])=>({name,input,expected:upstream.plainSchema(structuredClone(input))}));
const thinking=[];
for(const [model,at] of [['gemini-3.8-flash-high','high'],['gemini-3.8-flash-low','low'],['gemini-3.8-flash-medium','medium'],['gemini-3.8-flash',''],['gemini-3.1-pro-low','low'],['gemini-4-hydrogen-xhigh','xhigh'],['gemini-2.5-flash',''],['claude-sonnet-4-6',''],['gpt-oss-120b','']]){
 for(const effort of ['none','minimal','low','medium','high','xhigh']){
  const request={contents:[{role:'user',parts:[{text:'synthetic request'}]}],generationConfig:{thinkingConfig:{includeThoughts:true,thinkingBudget:4096,thinkingLevel:'HIGH'},maxOutputTokens:8192}};
  const expected=upstream.shape(request,model,at,effort);thinking.push({model,at,effort,input:request,expected});
 }
}
const request={contents:[{role:'user',parts:[{text:'synthetic request'}]}],systemInstruction:{role:'user',parts:[{text:'Use the declared tools only when needed.'}]},tools:[{functionDeclarations:Array.from({length:103},(_,i)=>({name:'synthetic_tool_'+i,description:'Synthetic declaration '+i,parametersJsonSchema:schemas[i%schemas.length].input}))}],toolConfig:{functionCallingConfig:{mode:'AUTO'}},generationConfig:{thinkingConfig:{includeThoughts:true,thinkingLevel:'high'},maxOutputTokens:16384}};
const batch={input:request,model:'gemini-3.8-flash-high',at:'high',effort:'high',expected:upstream.shape(request,'gemini-3.8-flash-high','high','high')};
assert.equal(batch.expected.tools[0].functionDeclarations.length,103);
fs.writeFileSync(output,JSON.stringify({sourceCommit:'34e32a1f443e656d255b1cc23363436559accacd',sourceSha256:sha,schemas,thinking,batch,networkCalls:0},null,2)+'\n');
console.log(JSON.stringify({schemaCases:schemas.length,thinkingCases:thinking.length,batchToolCount:103,networkCalls:0}));
