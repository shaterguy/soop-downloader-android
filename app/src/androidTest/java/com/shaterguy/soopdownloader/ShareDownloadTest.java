package com.shaterguy.soopdownloader;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.service.notification.StatusBarNotification;
import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.test.InstrumentationTestCase;
import com.shaterguy.soopdownloader.engine.Engine;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.io.File;
import java.io.OutputStream;

/** Live acceptance gate: no fixture substitution or skip on network failure. */
public final class ShareDownloadTest extends InstrumentationTestCase {
    private static final String EXAMPLE="https://vod.sooplive.com/player/206449237/catch";

    public void testBackgroundShareAndCancelOnlyActiveVideo() throws Exception {
        Context context=getInstrumentation().getTargetContext();
        SharedPreferences prefs=context.getSharedPreferences(DownloadService.PREFS,0);
        assertTrue("Fresh emulator preferences",prefs.edit().clear().putInt(DownloadService.LIMIT,1).commit());
        if(Build.VERSION.SDK_INT>=33)
            getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        Intent share=new Intent(Intent.ACTION_SEND).setClass(context,ShareActivity.class)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT,"SOOP 영상 공유\n"+EXAMPLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=null;
        Uri saved=null;
        try {
            // Cold share must start the foreground service without opening MainActivity.
            Instrumentation.ActivityMonitor mainMonitor=getInstrumentation().addMonitor(MainActivity.class.getName(),null,false);
            context.startActivity(new Intent(share).putExtra(Intent.EXTRA_TEXT,"https://vod.sooplive.com/player/206420475"));
            long ready=SystemClock.elapsedRealtime()+10000;
            while(prefs.getString("activeJobId","").isEmpty() && SystemClock.elapsedRealtime()<ready)SystemClock.sleep(50);
            String cancelledJob=prefs.getString("activeJobId","");
            assertFalse("First job started",cancelledJob.isEmpty());
            getInstrumentation().waitForIdleSync();
            NotificationManager notifications=context.getSystemService(NotificationManager.class);
            android.app.PendingIntent oldCancel=null;
            for(StatusBarNotification n:notifications.getActiveNotifications()) {
                if(n.getNotification().actions!=null && n.getNotification().actions.length>0)
                    oldCancel=n.getNotification().actions[0].actionIntent;
            }
            assertNotNull("Per-video notification cancel action",oldCancel);
            // Resolve the same SEND intent that the system share sheet delivers.
            Intent implicit=new Intent(Intent.ACTION_SEND).setPackage(context.getPackageName()).setType("text/plain");
            assertEquals(ShareActivity.class.getName(),context.getPackageManager().resolveActivity(implicit,0).activityInfo.name);
            context.startActivity(share);
            ready=SystemClock.elapsedRealtime()+10000;
            while(prefs.getInt("queued",0)!=1 && SystemClock.elapsedRealtime()<ready)SystemClock.sleep(50);
            assertEquals("Second video queued by background share",1,prefs.getInt("queued",0));
            assertEquals("Shared link must not open MainActivity",0,mainMonitor.getHits());
            // Repeated share must not duplicate the queued item.
            context.startActivity(new Intent(share));
            SystemClock.sleep(500);
            assertEquals(1,prefs.getInt("queued",0));
            // UI cancel targets exactly the ID rendered by the screen.
            context.startService(new Intent(context,DownloadService.class).setAction(DownloadService.CANCEL).putExtra("jobId",cancelledJob));
            ready=SystemClock.elapsedRealtime()+60000;
            while(cancelledJob.equals(prefs.getString("activeJobId","")) && SystemClock.elapsedRealtime()<ready)SystemClock.sleep(100);
            assertEquals("Queued video starts after current cancellation",EXAMPLE,prefs.getString("lastUrl",""));
            assertFalse("New video has a distinct cancellation identity",cancelledJob.equals(prefs.getString("activeJobId","")));
            // The previous notification must not cancel the new active video.
            oldCancel.send();
            assertEquals("No download screen launched by sharing",0,mainMonitor.getHits());
            getInstrumentation().removeMonitor(mainMonitor);
            long deadline=SystemClock.elapsedRealtime()+180000;
            JSONArray history=new JSONArray();
            while(SystemClock.elapsedRealtime()<deadline) {
                history=new JSONArray(prefs.getString("history","[]"));
                if(history.length()>0 && !prefs.getBoolean("busy",false) && !DownloadService.alive)break;
                SystemClock.sleep(250);
            }
            assertEquals("Live share failed: "+prefs.getString("phase","no status"),1,history.length());
            assertFalse("Download must finish",prefs.getBoolean("busy",false));
            assertFalse("Foreground service must stop after its queue drains",DownloadService.alive);
            JSONObject item=history.getJSONObject(0);
            assertEquals(EXAMPLE,item.getString("url"));
            assertTrue("Nonempty saved bytes",item.getLong("bytes")>1024);
            saved=Uri.parse(item.getString("uri"));
            try(Cursor c=context.getContentResolver().query(saved,new String[]{MediaStore.Video.Media.IS_PENDING,MediaStore.Video.Media.SIZE,MediaStore.Video.Media.MIME_TYPE},null,null,null)) {
                assertNotNull(c);assertTrue(c.moveToFirst());
                assertEquals("Published atomically",0,c.getInt(0));
                assertEquals("Complete copy",item.getLong("bytes"),c.getLong(1));
                assertEquals("video/mp4",c.getString(2));
            }
            MediaExtractor extractor=new MediaExtractor();
            try {
                extractor.setDataSource(context,saved,null);
                boolean video=false,audio=false;
                for(int i=0;i<extractor.getTrackCount();i++) {
                    MediaFormat f=extractor.getTrackFormat(i);
                    String mime=f.getString(MediaFormat.KEY_MIME);
                    if(mime!=null&&mime.startsWith("video/")) {
                        video=true;
                        assertEquals("Observed original video width",640,f.getInteger(MediaFormat.KEY_WIDTH));
                        assertEquals("Observed original video height",1080,f.getInteger(MediaFormat.KEY_HEIGHT));
                        assertTrue("Full catch duration",f.getLong(MediaFormat.KEY_DURATION)>45000000L);
                        extractor.selectTrack(i);
                        assertTrue("Readable video sample",extractor.readSampleData(ByteBuffer.allocate(4*1024*1024),0)>0);
                        extractor.unselectTrack(i);
                    }
                    if(mime!=null&&mime.startsWith("audio/"))audio=true;
                }
                assertTrue("Video track",video);assertTrue("Audio track",audio);
            } finally {extractor.release();}
            // Reproduce process-death journal timing after MediaStore publication.
            assertTrue(prefs.edit().putString("pendingUri",saved.toString()).commit());
            DownloadService.recover(context);
            try(Cursor c=context.getContentResolver().query(saved,new String[]{MediaStore.Video.Media._ID},null,null,null)) {
                assertNotNull(c);assertTrue("Recovery must retain published video",c.moveToFirst());
            }
            getInstrumentation().waitForIdleSync();
            // Open the screen explicitly only after background acceptance checks.
            activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // Leave the completed app visible while capturing the real emulator UI.
            SystemClock.sleep(1000);
            Bitmap screenshot=getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull("UI screenshot",screenshot);
            // Public test media survives connectedAndroidTest uninstalling the target APK.
            ContentValues imageValues=new ContentValues();
            imageValues.put(MediaStore.Images.Media.DISPLAY_NAME,"soop-ui.png");
            imageValues.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
            imageValues.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/SOOP-Test");
            imageValues.put(MediaStore.Images.Media.IS_PENDING,1);
            Uri image=context.getContentResolver().insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),imageValues);
            assertNotNull("Screenshot MediaStore row",image);
            boolean imagePublished=false;
            try {
                try(OutputStream stream=context.getContentResolver().openOutputStream(image)) {
                    assertNotNull("Screenshot output",stream);
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,stream));
                }
                imageValues.clear();imageValues.put(MediaStore.Images.Media.IS_PENDING,0);
                assertEquals(1,context.getContentResolver().update(image,imageValues,null,null));
                imagePublished=true;
            } finally {
                screenshot.recycle();
                if(!imagePublished)context.getContentResolver().delete(image,null,null);
            }
        } finally {
            if(activity!=null){final Activity a=activity;getInstrumentation().runOnMainSync(a::finish);}
            context.stopService(new Intent(context,DownloadService.class));
            if(saved!=null)context.getContentResolver().delete(saved,null,null);
        }
    }
    public void testTwoDownloadsRunConcurrentlyWithSettingAboveFive() throws Exception {
        Context c=getInstrumentation().getTargetContext();SharedPreferences p=c.getSharedPreferences(DownloadService.PREFS,0);
        assertFalse(DownloadService.alive);assertTrue(p.edit().clear().putInt(DownloadService.LIMIT,7).commit());
        if(Build.VERSION.SDK_INT>=33)getInstrumentation().getUiAutomation().grantRuntimePermission(c.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        String first="https://vod.sooplive.com/player/206420475";
        try {
            c.startActivity(new Intent(c,ShareActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long until=SystemClock.elapsedRealtime()+10000;
            while(p.getInt("activeCount",0)!=1&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
            String firstId=p.getString("activeJobId","");assertFalse(firstId.isEmpty());
            c.startActivity(new Intent(c,ShareActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,EXAMPLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            until=SystemClock.elapsedRealtime()+10000;
            while(p.getInt("activeCount",0)!=2&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(25);
            assertEquals("Two distinct videos active simultaneously",2,p.getInt("activeCount",0));
            assertEquals(0,p.getInt("queued",0));assertEquals(7,DownloadService.limit(c));
            // Lowering does not cancel either running job.
            p.edit().putInt(DownloadService.LIMIT,1).commit();
            c.startService(new Intent(c,DownloadService.class).setAction(DownloadService.CONFIGURE));
            getInstrumentation().waitForIdleSync();
            assertEquals("Lowering the limit retains active work",2,p.getInt("activeCount",0));
            c.startService(new Intent(c,DownloadService.class).setAction(DownloadService.CANCEL).putExtra("jobId",firstId));
            until=SystemClock.elapsedRealtime()+180000;
            while(DownloadService.alive&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
            assertFalse(DownloadService.alive);JSONArray h=new JSONArray(p.getString("history","[]"));
            assertEquals("One cancellation must not cancel the other concurrent job",1,h.length());
            assertEquals(EXAMPLE,h.getJSONObject(0).getString("url"));
            assertEquals("Per-job publication journals drained","{}",p.getString("pendingUris","{}"));
            assertEquals("Setting survives service destruction",1,DownloadService.limit(c));
        } finally {
            c.stopService(new Intent(c,DownloadService.class));
            JSONArray h=new JSONArray(p.getString("history","[]"));
            for(int n=0;n<h.length();n++)c.getContentResolver().delete(Uri.parse(h.getJSONObject(n).getString("uri")),null,null);
        }
    }

    public void testCatchStoryUserUrlDownloadsAsSingleFile() throws Exception {
        Context context=getInstrumentation().getTargetContext();
        String shared="https://vod.sooplive.com/player/793663/catchstory?o=1&o=3";
        assertEquals("https://vod.sooplive.com/player/793663/catchstory",Engine.normalizeInput(shared));
        File target=new File(context.getCacheDir(),"catchstory-"+System.nanoTime()+".mp4");
        MediaExtractor extractor=new MediaExtractor();
        try {
            Engine.Result result=Engine.download(shared,target,new Engine.Listener(){
                public void progress(String phase,int percent){}
                public boolean isCancelled(){return false;}
            });
            assertTrue("Catchstory output exists",target.isFile());
            assertTrue("Catchstory output is nonempty",target.length()>1024);
            assertEquals("Engine result bytes",target.length(),result.bytes);
            assertTrue("Catchstory duration",result.duration>0);
            extractor.setDataSource(target.getAbsolutePath());
            boolean video=false,audio=false;
            for(int i=0;i<extractor.getTrackCount();i++) {
                MediaFormat f=extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("video/"))video=true;
                if(mime!=null&&mime.startsWith("audio/"))audio=true;
            }
            assertTrue("Catchstory video track",video);
            assertTrue("Catchstory audio track",audio);
        } finally {
            extractor.release();
            target.delete();
        }
    }

}

