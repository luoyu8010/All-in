package com.gaeul.allin;

import android.app.job.JobService;
import android.app.job.JobParameters;

/** Inexact 9-hour network job. It only caches results; never opens a popup from background. */
public final class AppUpdateJobService extends JobService {
    private Thread worker;
    private volatile long generation;
    @Override public boolean onStartJob(JobParameters params){long ticket=++generation;worker=new Thread(()->{AppUpdateManager.core(getApplicationContext()).check(false);if(ticket==generation){jobFinished(params,false);AppUpdateManager.schedule(getApplicationContext(),true);}},"AllIn-UpdateJob");worker.start();return true;}
    @Override public boolean onStopJob(JobParameters params){generation++;Thread t=worker;if(t!=null)t.interrupt();worker=null;return true;}
    @Override public void onDestroy(){generation++;Thread t=worker;if(t!=null)t.interrupt();worker=null;super.onDestroy();}
}
