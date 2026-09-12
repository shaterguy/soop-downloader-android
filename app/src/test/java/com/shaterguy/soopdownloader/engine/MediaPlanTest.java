package com.shaterguy.soopdownloader.engine;

import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class MediaPlanTest {
    @Test public void acceptsSoopShareTextAndStripsTracking() throws Exception {
        MediaPlan.Input input=MediaPlan.parseInput("우정잉\nhttps://vod.sooplive.com/player/206449237/catch?from=share\nSOOP에서 보기");
        assertEquals("206449237",input.id);assertTrue(input.catchVideo);assertEquals("https://vod.sooplive.com/player/206449237/catch",input.url);
    }
    @Test public void rejectsLookalikeAndUnsupportedStory() throws Exception {
        for(String s:new String[]{"https://vod.sooplive.com.evil.test/player/1","https://vod.sooplive.com@evil.test/player/1","https://vod.sooplive.com/player/1/catchstory","https://play.sooplive.com/user/123","http://vod.sooplive.com/player/1"}) {
            try{MediaPlan.parseInput(s);fail(s);}catch(IOException expected){}
        }
    }
    @Test public void rejectsMultipleDifferentVideos() throws Exception {
        try {MediaPlan.parseInput("https://vod.sooplive.com/player/1 https://vod.sooplive.com/player/2");fail();}catch(IOException expected){}
    }
    @Test public void ranksResolutionThenFramesThenBitrate() throws Exception {
        MediaPlan.Variant high=new MediaPlan.Variant("hi",1920,1080,60,8000000);
        assertSame(high,MediaPlan.best(Arrays.asList(new MediaPlan.Variant("low",1280,720,120,20000000),new MediaPlan.Variant("mid",1920,1080,30,12000000),high,new MediaPlan.Variant("less",1920,1080,60,4000000))));
    }
    @Test public void parsesMasterWithQuotedCodecCommasAndRelativeUrls() throws Exception {
        List<MediaPlan.Variant> v=MediaPlan.variants("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360,CODECS=\"avc1.4d,mp4a\"\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,FRAME-RATE=60\nhigh.m3u8\n","https://vod-normal-kr-cdn-z01.sooplive.com/p/master.m3u8");
        assertEquals(2,v.size());assertTrue(MediaPlan.best(v).url.endsWith("/p/high.m3u8"));
    }
    @Test public void parsesCapturedFragmentedPlaylist() throws Exception {
        MediaPlan.Playlist p=MediaPlan.playlist("#EXTM3U\n#EXT-X-MAP:URI=\"init.m4s?rp=o00\"\n#EXTINF:7.849023,\nseg-0.m4s?rp=o00\n#EXTINF:8.333333,\nseg-1.m4s?rp=o00\n#EXT-X-ENDLIST\n","https://vod-normal-kr-cdn-z01.sooplive.com/p/manifest.m3u8?rp=o00");
        assertTrue(p.fragmented);assertEquals(3,p.segments.size());assertEquals(16.182356,p.seconds,0.000001);assertTrue(p.segments.get(0).url.endsWith("/p/init.m4s?rp=o00"));
    }
    @Test public void refusesEncryptedAndUnfinishedPlaylists() throws Exception {
        for(String body:new String[]{"#EXTM3U\n#EXTINF:3,\na.ts\n","#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\na.ts\n#EXT-X-ENDLIST"}) {
            try{MediaPlan.playlist(body,"https://vod.sooplive.com/a.m3u8");fail();}catch(IOException expected){}
        }
    }
    @Test public void choosesOriginalFromLiveGeneralManifest() throws Exception {
        try(java.io.InputStream in=getClass().getResourceAsStream("/engine/general-master.m3u8")) {
            assertNotNull(in);String body=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            MediaPlan.Variant best=MediaPlan.best(MediaPlan.variants(body,"https://vod-archive-global-cdn-z02.sooplive.com/spkt/vod/20260906/073/296926073/REGL_DD96BE5A_296926073_1.smil/manifest.m3u8?rp=p02"));
            assertEquals(1920,best.width);assertEquals(1080,best.height);assertEquals(8000000,best.bitrate);assertFalse(best.url.contains("_4000k"));
        }
    }
    @Test public void onlyFetchesHighestLeafAndNeverFallsBackOnFailure() throws Exception {
        MediaPlan.Variant low=new MediaPlan.Variant("https://vod.sooplive.com/low.m3u8",640,360,30,1000);
        MediaPlan.Variant high=new MediaPlan.Variant("https://vod.sooplive.com/high.m3u8",1920,1080,60,8000);
        java.util.ArrayList<String> fetched=new java.util.ArrayList<>();
        MediaPlan.Selection selected=MediaPlan.select(Arrays.asList(low,high),url->{fetched.add(url);return "#EXTM3U\n#EXTINF:3,\na.ts\n#EXT-X-ENDLIST";});
        assertSame(high,selected.variant);assertEquals(java.util.Collections.singletonList(high.url),fetched);
        fetched.clear();try{MediaPlan.select(Arrays.asList(low,high),url->{fetched.add(url);throw new IOException("server unavailable");});fail();}catch(IOException expected){}
        assertEquals(java.util.Collections.singletonList(high.url),fetched);
    }
    @Test public void preservesTrackStartOffsetAndOriginalTiePreference() throws Exception {
        assertEquals(2000000,MediaPlan.alignedTimestamp(1000000,1000000,2000000));
        assertEquals(2080000,MediaPlan.alignedTimestamp(1080000,1000000,2000000));
        MediaPlan.Variant original=new MediaPlan.Variant("original",1920,1080,60,8000);original.preference=1;
        assertSame(original,MediaPlan.best(Arrays.asList(new MediaPlan.Variant("alternative-codec",1920,1080,60,8000),original)));
    }
    @Test public void preservesByteRangesAndRejectsUnsafeHosts() throws Exception {
        MediaPlan.Playlist p=MediaPlan.playlist("#EXTM3U\n#EXT-X-BYTERANGE:10@5\na.ts\n#EXT-X-BYTERANGE:7\na.ts\n#EXT-X-ENDLIST","https://vod.sooplive.com/a.m3u8");
        assertEquals(5,p.segments.get(0).offset);assertEquals(15,p.segments.get(1).offset);assertEquals(7,p.segments.get(1).length);
        try{MediaPlan.playlist("#EXTM3U\nhttp://127.0.0.1/a.ts\n#EXT-X-ENDLIST","https://vod.sooplive.com/a.m3u8");fail();}catch(IOException expected){}
    }
}
