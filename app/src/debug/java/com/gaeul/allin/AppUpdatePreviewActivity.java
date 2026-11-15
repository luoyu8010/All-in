package com.gaeul.allin;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.JavascriptInterface;
import com.gaeul.allin.json.*;
import java.io.*;

/** Debug-only isolated visual acceptance host. Never accesses real accounts or GitHub metadata. */
public final class AppUpdatePreviewActivity extends Activity {
    private WebView web;
    private AppUpdateCore core;
    private final class Bridge {
        @JavascriptInterface public void checkAppUpdate(boolean manual){publish(core.check(manual));}
        @JavascriptInterface public void dismissAppUpdate(String key){publish(core.dismiss(key));}
        @JavascriptInterface public boolean openAppUpdatePage(){runOnUiThread(()->{if(!AppUpdateManager.openBrowser(AppUpdatePreviewActivity.this))web.evaluateJavascript("onAppUpdateOpenError()",null);});return true;}
    }
    private void publish(JSONObject result){runOnUiThread(()->web.evaluateJavascript("onAppUpdateState("+result.toString()+");document.getElementById('app-update-description').textContent='隔离演示：此新版为测试夹具，不是线上发行。';",null));}
    private String asset(String name)throws Exception{try(InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    @Override public void onCreate(Bundle state){super.onCreate(state);if(!getPackageName().endsWith(".repairtest")){finish();return;}requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        try{
            String mode=getIntent().getStringExtra("mode"),theme=getIntent().getStringExtra("theme");boolean dark="dark".equals(theme);getWindow().setStatusBarColor(dark?0xff000000:0xfff4f4f7);getWindow().setNavigationBarColor(dark?0xff000000:0xfff4f4f7);
            final JSONObject[] memory={new JSONObject()};JSONObject release=new JSONObject().put("id",1).put("draft",false).put("prerelease",false).put("tag_name","current".equals(mode)?"v1.2.44-test":"v1.2.45-test").put("body","检查更新更清晰\n后台自动检查与手动检查\n账号与现有设置保持不变").put("assets",new JSONArray());release.put("html_url","https://github.com/xunianooo/All-in/releases/tag/"+release.getString("tag_name"));
            core=new AppUpdateCore(new AppUpdateCore.Store(){public JSONObject read(){return memory[0];}public void write(JSONObject s){memory[0]=new JSONObject(s.toString());}},(url,limit)->new AppUpdateCore.Reply("error".equals(mode)?429:200,release.toString(),60000),System::currentTimeMillis,50,"1.2.44-test");
            String src=asset("dashboard.html"),styles=src.substring(src.indexOf("<style>"),src.indexOf("</style>")+8);int css=src.indexOf("<style>\n.app-update-card");styles+=src.substring(css,src.indexOf("</style>",css)+8);int card=src.indexOf("<section class=\"card app-update-card\""),modal=src.indexOf("<div id=\"app-update-modal\"");String cardHtml=src.substring(card,src.indexOf("</section>",card)+10),modalHtml=src.substring(modal,src.indexOf("<script>",modal));int js=src.indexOf("    // App update UI:");String code=src.substring(js,src.indexOf("</script>",js));
            String html="<!doctype html><html lang='zh-CN' data-theme='"+(dark?"dark":"light")+"'><head><meta name='viewport' content='width=device-width,initial-scale=1'>"+styles+"</head><body style='padding:24px'><p style='font-size:12px;color:var(--text-muted)'>更新功能隔离演示 · 非线上新版</p><section id='tab-overview' class='active'><div id='overview-main'>"+cardHtml+"</div></section>"+modalHtml+"<script>function showToast(s){document.getElementById('app-update-status').textContent=s;}"+code+"</script></body></html>";
            web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.addJavascriptInterface(new Bridge(),"AllInNative");web.setWebViewClient(new android.webkit.WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,String url){return true;}});setContentView(web);web.loadDataWithBaseURL("file:///android_asset/",html,"text/html","UTF-8",null);
        }catch(Exception failure){android.widget.TextView text=new android.widget.TextView(this);text.setText("隔离预览加载失败");setContentView(text);throw new RuntimeException(failure);}
    }
    @Override protected void onDestroy(){if(web!=null){web.destroy();web=null;}super.onDestroy();}
}
