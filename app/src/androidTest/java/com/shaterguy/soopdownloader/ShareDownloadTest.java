package com.shaterguy.soopdownloader;

import android.Manifest;
import android.app.Activity;
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
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.io.OutputStream;

/** Live acceptance gate: no fixture substitution or skip on network failure. */
public final class ShareDownloadTest extends InstrumentationTestCase {
    private static final String EXAMPLE="https://vod.sooplive.com/player/206449237/catch";

    public void testSharedCatchPublishesPlayableVideoOnce() throws Exception {
        Context context=getInstrumentation().getTargetContext();
        SharedPreferences prefs=context.getSharedPreferences(DownloadService.PREFS,0);
        assertTrue("Fresh emulator preferences",prefs.edit().clear().commit());
        if(Build.VERSION.SDK_INT>=33)
            getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        Intent share=new Intent(Intent.ACTION_SEND).setClass(context,MainActivity.class)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT,"SOOP 영상 공유\n"+EXAMPLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=null;
        Uri saved=null;
        try {
            activity=getInstrumentation().startActivitySync(share);
            assertNotNull("Share recipient launches",activity);
            // A second delivery while the first is active must be coalesced.
            final MainActivity recipient=(MainActivity)activity;
            getInstrumentation().runOnMainSync(()->recipient.onNewIntent(new Intent(share)));
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
}
