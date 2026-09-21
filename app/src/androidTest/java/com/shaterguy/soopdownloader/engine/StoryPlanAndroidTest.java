package com.shaterguy.soopdownloader.engine;

import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;

public final class StoryPlanAndroidTest extends InstrumentationTestCase {
    public void testKeepsOnlyCatchItemsInServerOrder() throws Exception {
        String json="{\"data\":["
            +"{\"story_type\":\"vod\",\"catch_list\":[{\"title\":\"ignore\",\"files\":[{\"file\":\"https://vod.sooplive.com/ignore.mp4\"}]}]},"
            +"{\"story_type\":\"catch\",\"catch_list\":["
            +"{\"title\":\"첫 장면\",\"files\":[{\"file\":\"https://vod.sooplive.com/a.mp4\",\"duration\":1000}]},"
            +"{\"title\":\"둘째 장면\",\"files\":[{\"file\":\"https://vod.sooplive.com/b.mp4\",\"duration\":2000}]}]},"
            +"{\"story_type\":\"catch\",\"catch_list\":[{\"title\":\"셋째 장면\",\"files\":[{\"file\":\"https://vod.sooplive.com/c.mp4\",\"duration\":3000}]}]}]}";
        JSONObject data=StoryPlan.exactStory(new JSONObject(json),"793663");
        JSONArray files=data.getJSONArray("files");
        assertEquals(3,files.length());
        assertEquals("https://vod.sooplive.com/a.mp4",files.getJSONObject(0).getString("file"));
        assertEquals("https://vod.sooplive.com/b.mp4",files.getJSONObject(1).getString("file"));
        assertEquals("https://vod.sooplive.com/c.mp4",files.getJSONObject(2).getString("file"));
        assertEquals(1,files.getJSONObject(0).getInt("file_order"));
        assertEquals(3,files.getJSONObject(2).getInt("file_order"));
        assertEquals("첫 장면 외 2개",data.getString("title"));
    }

    public void testRejectsSchemaChangesAndUnsafeFiles() throws Exception {
        for(String json:new String[]{
            "{\"data\":{}}",
            "{\"data\":[{\"story_type\":\"catch\",\"catch_list\":[{\"files\":[]}]}]}",
            "{\"data\":[{\"story_type\":\"catch\",\"catch_list\":[{\"files\":[{\"file\":\"https://evil.test/a.mp4\"}]}]}]}",
            "{\"data\":[{\"story_type\":\"vod\",\"catch_list\":[]}]}"}){
            try{StoryPlan.exactStory(new JSONObject(json),"793663");fail(json);}catch(java.io.IOException expected){}
        }
    }
}
