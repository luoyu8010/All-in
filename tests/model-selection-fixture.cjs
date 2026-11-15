'use strict';
const vm=require('vm');
function loadModelSelection(context, html) {
 html=html.split(String.fromCharCode(13)).join('');
 const start=html.indexOf('    const groupModelPages = new Map();');
 const end=html.indexOf('    /**\n     * 路由组卡片',start);
 if(start<0||end<0)throw Error('shared model selection block missing');
 vm.runInContext(html.slice(start,end),context);
 const a=html.indexOf('    function providerHasCredential('),b=html.indexOf('    function renderAllModelsTags()',a);
 vm.runInContext(html.slice(a,b),context);
 vm.runInContext("var pingModelPage=0;var pingModelViewSig='';",context);
}
module.exports={loadModelSelection};
const fs=require('fs'),path=require('path');
const helper=fs.readFileSync(path.join(__dirname,'../scripts/beta-dashboard-xss.cjs'),'utf8');
const readFunction=new Function(helper.slice(helper.indexOf('function skipString('),helper.indexOf('// groupEsc 是'))+'\nreturn readFunction;')();
module.exports.readFunction=readFunction;
