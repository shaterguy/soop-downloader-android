package com.shaterguy.soopdownloader;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import com.shaterguy.soopdownloader.engine.Engine;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class DownloadService extends Service {
    public static final String PREFS="downloads", CHANNEL="downloads", ENQUEUE="enqueue", CANCEL="cancel";
    private static final int NOTICE=7;
    public static final String CONFIGURE="configure", LIMIT="parallelDownloads";
    private final ExecutorService worker=Executors.newCachedThreadPool();
    private final DownloadQueue queue=new DownloadQueue();
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final Object STORE_LOCK=new Object();
    private volatile boolean destroyed;
    private int latestStart;
    private String lastResult="다운로드할 영상을 기다리고 있습니다.";
    static volatile boolean alive;
    public static int limit(Context c){return Math.max(1,c.getSharedPreferences(PREFS,0).getInt(LIMIT,5));}

    public static String normalize(String text) {
        try { return Engine.normalizeInput(text); }
        catch(IOException e){ throw new IllegalArgumentException(e.getMessage()); }
    }
    @Override public void onCreate() {
        super.onCreate(); alive=true;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"영상 다운로드",NotificationManager.IMPORTANCE_LOW));
        recover(this);
    }
    static void recover(Context c) {
        SharedPreferences p=c.getSharedPreferences(PREFS,0);
        synchronized(STORE_LOCK){
            Set<String> partials=new HashSet<>();
            partials.add(p.getString("pendingUri",""));
            try{JSONObject map=new JSONObject(p.getString("pendingUris","{}"));Iterator<String> keys=map.keys();while(keys.hasNext())partials.add(map.getString(keys.next()));}catch(JSONException ignored){}
            for(String partial:partials)if(!partial.isEmpty()){
                Uri uri=Uri.parse(partial);
                try(android.database.Cursor cursor=c.getContentResolver().query(uri,new String[]{MediaStore.Video.Media.IS_PENDING},null,null,null)){
                    if(cursor!=null&&cursor.moveToFirst()&&cursor.getInt(0)==1)c.getContentResolver().delete(uri,null,null);
                }catch(Exception ignored){}
            }
            SharedPreferences.Editor edit=p.edit().putString("pendingUri","").putString("pendingUris","{}").putString("jobs","[]").putString("activeJobId","").putInt("queued",0).putInt("activeCount",0);
            if(p.getBoolean("busy",false))edit.putString("phase","다운로드가 중단되었습니다. 다시 시도해 주세요.");
            edit.putBoolean("busy",false).commit();
        }
        File cache=new File(c.getCacheDir(),"download");
        File[] files=cache.listFiles(); if(files!=null)for(File f:files)if(f.isFile())f.delete();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        latestStart=startId;
        if(intent==null){if(queue.isEmpty())stopSelf(startId);return START_NOT_STICKY;}
        queue.setLimit(limit(this));
        if(CONFIGURE.equals(intent.getAction())){pump();return START_NOT_STICKY;}
        if(CANCEL.equals(intent.getAction())){
            DownloadQueue.Job job=queue.cancel(intent.getStringExtra("jobId"));
            if(job!=null){job.phase="취소 중";if(!job.running)getSystemService(NotificationManager.class).cancel(job.id,NOTICE+1);}
            pump();return START_NOT_STICKY;
        }
        try {
            String url=normalize(intent.getStringExtra("url"));
            DownloadQueue.Job job=queue.add(url);
            if(job==null){if(intent.getBooleanExtra("shared",false))toast("이미 다운로드 중이거나 대기 중인 영상입니다.");return START_NOT_STICKY;}
            startForeground(NOTICE,summaryNotification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            pump();
            if(intent.getBooleanExtra("shared",false))toast("백그라운드 다운로드를 시작합니다.");
        }catch(Exception e){toast("다운로드를 시작하지 못했습니다.");if(queue.isEmpty())stopSelf(startId);}
        return START_NOT_STICKY;
    }
    private void toast(String text){android.widget.Toast.makeText(this,text,android.widget.Toast.LENGTH_SHORT).show();}
    private SharedPreferences prefs(){return getSharedPreferences(PREFS,0);}
    private void pump(){
        if(destroyed)return;
        queue.setLimit(limit(this));
        for(DownloadQueue.Job job:queue.startReady()){
            prefs().edit().putString("lastUrl",job.url).apply();
            notifyJob(job);
            worker.submit(()->runDownload(job));
        }
        publishState();
        if(queue.isEmpty()){stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(latestStart);}
    }
    private void publishState(){
        JSONArray jobs=new JSONArray();String first="";int percent=0;
        for(DownloadQueue.Job j:queue.snapshot()){
            if(first.isEmpty()&&j.running)first=j.id;
            if(j.running)percent+=j.progress;
            try{jobs.put(new JSONObject().put("id",j.id).put("url",j.url).put("phase",j.phase).put("progress",j.progress).put("running",j.running));}catch(JSONException ignored){}
        }
        int active=queue.activeCount();
        prefs().edit().putString("jobs",jobs.toString()).putString("activeJobId",first).putInt("activeCount",active).putInt("queued",queue.queuedCount())
            .putBoolean("busy",!queue.isEmpty()).putInt("progress",active==0?0:percent/active)
            .putString("phase",queue.isEmpty()?lastResult:active+"개 다운로드 중").apply();
        if(!queue.isEmpty())getSystemService(NotificationManager.class).notify(NOTICE,summaryNotification());
    }
    private void journal(DownloadQueue.Job job,Uri uri){
        synchronized(STORE_LOCK){
            try{JSONObject map=new JSONObject(prefs().getString("pendingUris","{}"));
                if(uri==null)map.remove(job.id);else map.put(job.id,uri.toString());
                if(!prefs().edit().putString("pendingUris",map.toString()).commit())throw new IllegalStateException("저장 기록을 기록하지 못했습니다.");
            }catch(JSONException e){throw new IllegalStateException(e);}
        }
    }
    private void runDownload(DownloadQueue.Job job){
        String url=job.url;
        Uri output=null; File file=null; boolean published=false;
        try {
            File dir=new File(getCacheDir(),"download"); if(!dir.isDirectory()&&!dir.mkdirs()&&!dir.isDirectory())throw new IOException("임시 저장 공간을 만들지 못했습니다.");
            file=new File(dir,UUID.randomUUID()+".mp4");
            Engine.Result result=Engine.download(url,file,new Engine.Listener(){
                public void progress(String phase,int percent){status(job,phase,percent);}
                public boolean isCancelled(){return job.cancelled||Thread.currentThread().isInterrupted();}
            });
            if(job.cancelled)throw new InterruptedIOException("취소되었습니다.");
            if(!file.isFile()||file.length()==0)throw new IOException("저장할 영상이 없습니다.");
            status(job,"내 기기에 저장 중",99);
            String title=result.title==null?"SOOP 영상":result.title;
            String name=title.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]","_").trim();
            if(name.length()>90)name=name.substring(0,90);if(name.isEmpty())name="SOOP";
            name+="_"+System.currentTimeMillis()+".mp4";
            ContentValues v=new ContentValues();v.put(MediaStore.Video.Media.DISPLAY_NAME,name);v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");v.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/SOOP Downloader");v.put(MediaStore.Video.Media.IS_PENDING,1);
            output=getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),v);
            if(output==null)throw new IOException("갤러리 저장 위치를 만들지 못했습니다.");
            journal(job,output);
            try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(output)){
                if(out==null)throw new IOException("저장 공간에 접근하지 못했습니다.");
                byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1){if(job.cancelled)throw new InterruptedIOException("취소되었습니다.");out.write(b,0,n);}
            }
            if(job.cancelled)throw new InterruptedIOException("취소되었습니다.");
            v.clear();v.put(MediaStore.Video.Media.IS_PENDING,0);
            if(getContentResolver().update(output,v,null,null)!=1)throw new IOException("영상 저장을 확정하지 못했습니다.");
            published=true;
            String quality=result.width>0&&result.height>0?result.width+" × "+result.height:"원본 스트림";
            synchronized(STORE_LOCK){
            JSONArray history;
            try{history=new JSONArray(prefs().getString("history","[]"));}catch(JSONException e){history=new JSONArray();}
            JSONObject item=new JSONObject().put("title",title).put("uri",output.toString()).put("quality",quality).put("bytes",file.length()).put("url",url);
            JSONArray updated=new JSONArray().put(item);for(int i=0;i<Math.min(29,history.length());i++)updated.put(history.get(i));
            prefs().edit().putString("history",updated.toString()).commit();
            }
            status(job,"저장 완료 · "+quality,100);
        }catch(Exception e){
            String message=job.cancelled?"다운로드를 취소했습니다.":e.getMessage();
            if(message==null||message.trim().isEmpty())message="다운로드하지 못했습니다. 네트워크와 영상 주소를 확인해 주세요.";
            // Avoid recording CDN tokens, cookies or signed URLs in UI/history.
            message=message.replaceAll("https?://\\S+","[영상 주소]");
            status(job,message,0);
        }finally{
            if(output!=null&&!published)try{getContentResolver().delete(output,null,null);}catch(Exception ignored){}
            try{journal(job,null);}catch(Exception ignored){}
            if(file!=null)file.delete();
            main.post(()->{if(destroyed)return;lastResult=job.phase;queue.finish(job);getSystemService(NotificationManager.class).cancel(job.id,NOTICE+1);pump();});
        }
    }
    private void status(DownloadQueue.Job job,String text,int percent){
        main.post(()->{
            if(destroyed||!queue.contains(job))return;
            job.phase=text;job.progress=Math.max(0,Math.min(100,percent));
            long now=SystemClock.elapsedRealtime();
            if(now-job.lastNotice>750||percent>=99){job.lastNotice=now;notifyJob(job);publishState();}
        });
    }
    private Notification.Builder baseNotification(){
        PendingIntent content=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_download).setContentTitle("SOOP Downloader").setContentIntent(content).setOnlyAlertOnce(true).setOngoing(true);
    }
    private Notification summaryNotification(){return baseNotification().setContentText(queue.activeCount()+"개 다운로드 중 · "+queue.queuedCount()+"개 대기").build();}
    private void notifyJob(DownloadQueue.Job job){
        PendingIntent cancel=PendingIntent.getService(this,1,new Intent(this,DownloadService.class).setAction(CANCEL).setData(Uri.parse("soop-download://cancel/"+job.id)).putExtra("jobId",job.id),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=baseNotification().setContentTitle("SOOP · "+Uri.parse(job.url).getLastPathSegment()).setContentText(job.phase)
            .setStyle(new Notification.BigTextStyle().bigText(job.url+"\n"+job.phase)).setProgress(100,job.progress,job.progress<=0)
            .addAction(new Notification.Action.Builder(null,"이 영상 취소",cancel).build()).build();
        getSystemService(NotificationManager.class).notify(job.id,NOTICE+1,n);
    }
    @Override public void onTimeout(int startId,int fgsType){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){
        destroyed=true;
        for(DownloadQueue.Job j:queue.snapshot())getSystemService(NotificationManager.class).cancel(j.id,NOTICE+1);
        queue.cancelAll();worker.shutdownNow();alive=false;
        prefs().edit().putBoolean("busy",false).putString("jobs","[]").putInt("activeCount",0).putInt("queued",0).putString("activeJobId","").apply();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
