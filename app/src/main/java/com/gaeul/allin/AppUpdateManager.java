package com.gaeul.allin;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import com.gaeul.allin.json.JSONObject;
import java.io.IOException;

/** Independent update preferences; never touches config.json or account credentials. */
final class AppUpdateManager {
    static final String PREFS="allin_app_updates";
    static final int JOB_ID=0x414C5501;
    private static AppUpdateCore instance;
    interface Callback {void done(JSONObject result);}
    static AppUpdateCore core(Context context){
        synchronized(AppUpdateManager.class){
            if(instance==null){Context app=context.getApplicationContext();if(app==null)app=context;final SharedPreferences prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);int code=0;String name="";try{android.content.pm.PackageInfo p=app.getPackageManager().getPackageInfo(app.getPackageName(),0);code=p.versionCode;name=p.versionName;}catch(Exception ignored){}
                instance=new AppUpdateCore(new AppUpdateCore.Store(){public JSONObject read(){try{return AppUpdateCore.object(prefs.getString("state","{}"));}catch(Exception invalid){return new JSONObject();}}public void write(JSONObject state)throws IOException{if(!prefs.edit().putString("state",state.toString()).commit())throw new IOException("update preferences not saved");}},new AppUpdateTransport(),System::currentTimeMillis,code,name);
            }
            return instance;
        }
    }
    static void check(Context context,boolean manual,Callback callback){final AppUpdateCore core=core(context);new Thread(()->{JSONObject result=core.check(manual);if(!result.optBoolean("busy")&&!result.optBoolean("cached")&&result.optString("error").isEmpty())schedule(context,true);if(callback!=null)callback.done(result);},"AllIn-UpdateCheck").start();}
    static boolean openBrowser(Context context){try{android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(AppUpdateCore.DOWNLOAD));if(!(context instanceof android.app.Activity))intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(intent);return true;}catch(Exception unavailable){return false;}}
    static long nextDelay(JSONObject state,long now) {
        if(state.optBoolean("busy"))return 30000;
        long last=state.optLong("lastChecked",0);
        if(last>now)return 1000;
        long next=last>0?last+AppUpdateCore.INTERVAL:now+(state.optString("error").isEmpty()?AppUpdateCore.INTERVAL:AppUpdateCore.ERROR_BACKOFF);
        long retry=state.optLong("retryAfter",0);if(retry>now&&retry-now<=AppUpdateCore.MAX_BACKOFF)next=Math.max(next,retry);
        return Math.max(1000,Math.min(AppUpdateCore.MAX_BACKOFF,next-now));
    }
    static void schedule(Context context){schedule(context,false);}
    static void schedule(Context context,boolean reset){
        try{JobScheduler jobs=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);if(jobs==null)return;ComponentName component=new ComponentName(context,AppUpdateJobService.class);JobInfo existing=jobs.getPendingJob(JOB_ID);JSONObject state=core(context).snapshot(false);
            if(!reset&&existing!=null&&!existing.isPeriodic()&&component.equals(existing.getService()))return;
            jobs.schedule(new JobInfo.Builder(JOB_ID,component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(nextDelay(state,System.currentTimeMillis())).build());
        }catch(Exception ignored){/* Opening the app still supplements system-delayed checks. */}
    }
}
