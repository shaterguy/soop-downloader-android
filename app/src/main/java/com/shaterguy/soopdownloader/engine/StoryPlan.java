package com.shaterguy.soopdownloader.engine;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

/** Converts a catchstory response into the ordered file contract already handled by Engine. */
final class StoryPlan {
    private StoryPlan() {}
    static JSONObject exactStory(JSONObject response,String id) throws Exception {
        Object raw=response.opt("data");
        if(!(raw instanceof JSONArray)) throw new IOException("캐치스토리 응답 형식이 변경되었습니다.");
        JSONArray stories=(JSONArray)raw;
        JSONArray files=new JSONArray();
        String firstTitle="";
        int order=1;
        for(int i=0;i<stories.length();i++) {
            JSONObject story=stories.optJSONObject(i);
            if(story==null) throw new IOException("캐치스토리 응답 형식이 변경되었습니다.");
            if(!"catch".equals(story.optString("story_type"))) continue;
            JSONArray catches=story.optJSONArray("catch_list");
            if(catches==null) throw new IOException("캐치스토리의 캐치 목록을 확인할 수 없습니다.");
            for(int j=0;j<catches.length();j++) {
                JSONObject clip=catches.optJSONObject(j);
                if(clip==null) throw new IOException("캐치스토리의 캐치 정보가 올바르지 않습니다.");
                JSONArray source=clip.optJSONArray("files");
                if(source==null||source.length()!=1||source.optJSONObject(0)==null)
                    throw new IOException("캐치스토리 영상 파일 형식이 변경되었습니다.");
                JSONObject file=source.getJSONObject(0);
                String url=file.optString("file");
                if(url.isEmpty()) throw new IOException("캐치스토리의 영상 파일이 제공되지 않습니다.");
                MediaPlan.trusted(url);
                JSONObject copy=new JSONObject(file.toString());
                copy.put("file_order",order++);
                files.put(copy);
                if(firstTitle.isEmpty()) firstTitle=clip.optString("title");
            }
        }
        if(files.length()==0) throw new IOException("캐치스토리에 저장할 공개 캐치 영상이 없습니다.");
        JSONObject data=new JSONObject();
        String title=firstTitle.isEmpty()?"SOOP 캐치스토리 "+id:firstTitle;
        if(files.length()>1) title+=" 외 "+(files.length()-1)+"개";
        data.put("title",title);
        data.put("files",files);
        return data;
    }
}
