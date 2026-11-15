package com.gaeul.allin;

import android.app.Instrumentation;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.gaeul.allin.json.*;
import java.io.*;
import java.util.concurrent.*;

/** Uses the actual packaged dashboard in Android WebView; no native bridge or network access. */
final class Audit52WebView {
    static String run(Instrumentation owner)throws Exception {
        String html;
        try(InputStream in=owner.getTargetContext().getAssets().open("dashboard.html");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);html=out.toString("UTF-8");
        }
        final String page=html;final CountDownLatch ready=new CountDownLatch(1),done=new CountDownLatch(1);final String[] result={null};final WebView[] view={null};
        owner.runOnMainSync(()->{WebView w=new WebView(owner.getTargetContext());view[0]=w;w.getSettings().setJavaScriptEnabled(true);w.getSettings().setBlockNetworkLoads(true);w.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView v,String u){ready.countDown();}});w.loadDataWithBaseURL("https://audit52.invalid/",page,"text/html","UTF-8",null);});
        try {
            if(!ready.await(20,TimeUnit.SECONDS))throw new IOException("WebView page not ready");
            String js="(()=>{const p={id:'openrouter',enabled:true,keys:['SYNTHETIC_NOT_REAL'],accounts:[],models:['openrouter/auto','openrouter/free','anthropic/claude-example','bare']};appConfig={providers:[p],groups:[],exposed:{enabled:false,items:[]}};const all=selectionModelsOf(p,'group');appConfig.exposed={enabled:true,items:['openrouter/openrouter/auto']};const selected=selectionModelsOf(p,'group');return JSON.stringify({all,selected,expected:'openrouter/openrouter/auto',selectionBug:!selected.includes('openrouter/openrouter/auto'),userAgent:navigator.userAgent});})()";
            owner.runOnMainSync(()->view[0].evaluateJavascript(js,value->{result[0]=value;done.countDown();}));
            if(!done.await(10,TimeUnit.SECONDS))throw new IOException("WebView result missing");
            return new JSONArray("["+result[0]+"]").getString(0);
        }finally{owner.runOnMainSync(()->{view[0].stopLoading();view[0].destroy();});}
    }
}
