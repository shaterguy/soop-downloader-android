package com.shaterguy.soopdownloader.engine;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Resolves the exact VOD, ranks supplied formats, and saves losslessly as MP4. */
public final class Engine {
    public interface Listener { void progress(String phase,int percent); boolean isCancelled(); }
    public static final class Result {
        public final String title; public final int width,height; public final long bytes,duration;
        Result(String t,int w,int h,long b,long d) {title=t;width=w;height=h;bytes=b;duration=d;}
    }
    private static final String UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private final Listener listener; private final String referer;
    private Engine(Listener listener,String referer){this.listener=listener;this.referer=referer;}
    public static Result download(String input,File target,Listener listener) throws Exception {
        MediaPlan.Input in=MediaPlan.parseInput(input);return new Engine(listener,in.url).run(in,target);
    }
    public static String normalizeInput(String input) throws IOException { return MediaPlan.parseInput(input).url; }
    private void check() throws InterruptedIOException { if(listener.isCancelled()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("다운로드를 취소했습니다."); }
    private static final class Part {
        final List<MediaPlan.Variant> variants=new ArrayList<>(); long duration;
    }
    private Result run(MediaPlan.Input in,File target) throws Exception {
        check();listener.progress("제공 화질 확인 중",-1);
        JSONObject response=new JSONObject(text("https://api.m.sooplive.com/station/video/a/"+(in.catchVideo?"catchview":"view"),
            "nTitleNo="+in.id+"&nApiLevel=10"+(in.catchVideo?"&nTargetTitleNo="+in.id+"&nPageNo=1&nLimit=10":"")));
        JSONObject data=exactVideo(response,in);
        String adult=data.optString("adult_status","pass");
        if(!adult.isEmpty()&&!"pass".equals(adult)) throw new IOException("이 영상은 SOOP 로그인 또는 본인 확인이 필요합니다.");
        if(!data.optString("sub_upload_type").isEmpty()||data.optBoolean("is_paid")||data.optBoolean("is_ppv")) throw new IOException("이 영상은 별도 시청 권한이 필요합니다.");
        JSONArray files=data.optJSONArray("files");
        if(files==null||files.length()==0) throw new IOException("영상 파일이 없거나 시청 권한이 필요합니다.");
        SortedMap<Integer,Part> parts=new TreeMap<>();
        for(int i=0;i<files.length();i++) {
            JSONObject f=files.getJSONObject(i);
            if("Y".equals(f.optString("hide"))) throw new IOException("일부 구간의 시청 권한을 확인할 수 없습니다.");
            String u=f.optString("file");if(u.isEmpty())throw new IOException("일부 영상 구간이 제공되지 않습니다.");
            MediaPlan.trusted(u);
            int order=f.optInt("file_order",i+1);Part part=parts.computeIfAbsent(order,k->new Part());
            addCandidate(part,f,data);
            for(String key:new String[]{"quality_info","multi_codec_quality_info"}) {
                JSONArray candidates=f.optJSONArray(key);
                if(candidates!=null)for(int q=0;q<candidates.length();q++)addCandidate(part,candidates.getJSONObject(q),data);
            }
            part.duration=Math.max(part.duration,f.optLong("duration",0));
        }
        File parent=target.getAbsoluteFile().getParentFile();if(parent==null||(!parent.exists()&&!parent.mkdirs()))throw new IOException("저장 공간을 열 수 없습니다.");
        if(target.exists())throw new IOException("동일한 임시 파일이 이미 있습니다.");
        List<File> temporary=new ArrayList<>();List<File> downloaded=new ArrayList<>(); long expected=0;boolean complete=false;
        try {
            int n=0;
            for(Part part:parts.values()) {
                check();MediaPlan.Selection selected=MediaPlan.select(part.variants,url->text(url,null));
                MediaPlan.Variant best=selected.variant;
                String detail=best.width>0?best.width+" × "+best.height:"원본";
                listener.progress("최고 화질 "+detail+" · "+(++n)+"/"+parts.size()+" 구간",0);
                File raw=File.createTempFile("soop-part-",".media",parent);temporary.add(raw);
                String manifest=selected.manifest;
                if(manifest!=null) {
                    MediaPlan.Playlist playlist=MediaPlan.playlist(manifest,best.url);int index=0;
                    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(raw))) {
                        for(MediaPlan.Segment s:playlist.segments) {check();copy(s.url,out,s.offset,s.length);listener.progress("최고 화질 "+detail+" · "+n+"/"+parts.size()+" 구간",++index*90/playlist.segments.size());}
                    }
                    expected+=(long)(playlist.seconds*1000);
                } else {
                    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(raw))){copy(best.url,out,0,-1);}expected+=part.duration;
                }
                downloaded.add(raw);
            }
            check();listener.progress("원본 화질로 MP4 저장 중",95);
            Result media=remux(downloaded,target,data.optString("title","SOOP_"+in.id),expected);
            check();complete=true;return media;
        } finally {for(File f:temporary)f.delete();if(!complete)target.delete();}
    }
    private static void addCandidate(Part part,JSONObject f,JSONObject data) throws IOException {
        String u=f.optString("file");if(u.isEmpty())return;MediaPlan.trusted(u);
        for(MediaPlan.Variant existing:part.variants)if(existing.url.equals(u))return;
        int[] size=MediaPlan.resolution(f.optString("resolution",data.optString("file_resolution")));
        String rate=f.optString("bitrate",data.optString("file_bps","0")).toLowerCase(Locale.ROOT);
        long bitrate=(long)(MediaPlan.decimal(rate.replace("k",""))*1000);
        MediaPlan.Variant candidate=new MediaPlan.Variant(u,size[0],size[1],f.optDouble("fps",0),bitrate);
        candidate.adaptive="adaptive".equals(f.optString("name"))||URI.create(u).getPath().contains(".smil/");
        candidate.preference="original".equals(f.optString("name"))?1:0;
        part.variants.add(candidate);
    }
    static JSONObject exactVideo(JSONObject response,MediaPlan.Input in) throws Exception {
        if(response.optInt("result",0)!=1) throw new IOException("영상을 조회할 수 없습니다. 삭제 또는 시청 제한 여부를 확인해 주세요.");
        Object raw=response.opt("data");
        if(in.catchVideo) {
            if(raw instanceof JSONArray) {JSONArray list=(JSONArray)raw;for(int i=0;i<list.length();i++){JSONObject v=list.optJSONObject(i);if(v!=null&&in.id.equals(v.optString("title_no")))return v;}}
            throw new IOException("요청한 캐치를 찾을 수 없습니다. 다른 추천 영상은 저장하지 않습니다.");
        }
        if(!(raw instanceof JSONObject))throw new IOException("영상 응답 형식이 변경되었습니다.");
        JSONObject data=(JSONObject)raw;
        if(data.optInt("code",0)<0)throw new IOException("삭제되었거나 비공개인 영상입니다.");
        if(!in.id.equals(data.optString("title_no")))throw new IOException("요청한 영상과 서버 응답이 일치하지 않습니다.");
        return data;
    }
    private HttpURLConnection open(String url,String post,long offset,long length) throws Exception {
        URI uri=MediaPlan.trusted(url);
        for(int redirects=0;redirects<6;redirects++) {
            check();HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
            c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent",UA);c.setRequestProperty("Referer",referer);c.setRequestProperty("Origin","https://vod.sooplive.com");c.setRequestProperty("Accept-Encoding","identity");
            if(length>=0)c.setRequestProperty("Range","bytes="+offset+"-"+(offset+length-1));
            if(post!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");try(OutputStream o=c.getOutputStream()){o.write(post.getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();
            if(status>=300&&status<400){String location=c.getHeaderField("Location");c.disconnect();if(location==null)throw new IOException("잘못된 서버 이동 응답입니다.");uri=MediaPlan.trusted(uri.resolve(location).toString());continue;}
            if(status<200||status>=300){c.disconnect();throw new IOException("영상 서버 응답 오류 (HTTP "+status+"). 낮은 화질로 변경하지 않았습니다.");}
            if(length>=0&&status!=206){c.disconnect();throw new IOException("영상 서버가 구간 다운로드를 지원하지 않습니다.");}
            return c;
        }
        throw new IOException("영상 서버 이동 횟수를 초과했습니다.");
    }
    private String text(String url,String post) throws Exception {
        HttpURLConnection c=open(url,post,0,-1);
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){check();if(out.size()+n>8*1024*1024)throw new IOException("영상 정보 응답이 너무 큽니다.");out.write(buffer,0,n);}return out.toString("UTF-8");
        } finally {c.disconnect();}
    }
    private void copy(String url,OutputStream out,long offset,long length) throws Exception {
        HttpURLConnection c=open(url,null,offset,length);
        long received=0,declared=c.getContentLengthLong();
        try(InputStream in=new BufferedInputStream(c.getInputStream())) {
            byte[] buffer=new byte[131072];int n;while((n=in.read(buffer))!=-1){check();out.write(buffer,0,n);received+=n;if(length>=0&&received>length)throw new IOException("영상 구간 크기가 올바르지 않습니다.");}
            if(received==0||(length>=0&&received!=length)||(declared>=0&&received!=declared))throw new IOException("영상 다운로드가 중간에 끊겼습니다. 다시 시도해 주세요.");
        } finally {c.disconnect();}
    }
    private Result remux(List<File> sources,File target,String title,long expected) throws Exception {
        MediaMuxer mux=null;boolean started=false;List<MediaFormat> formats=new ArrayList<>();List<Integer> outputs=new ArrayList<>();
        int width=0,height=0;long offset=0,total=0;ByteBuffer buffer=ByteBuffer.allocateDirect(16*1024*1024);
        try {
            mux=new MediaMuxer(target.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for(File source:sources) {
                MediaExtractor ex=new MediaExtractor();
                try {
                    ex.setDataSource(source.getAbsolutePath());List<Integer> inputs=new ArrayList<>();
                    for(int i=0;i<ex.getTrackCount();i++) {
                        MediaFormat f=ex.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);
                        if(mime!=null&&(mime.startsWith("video/")||mime.startsWith("audio/"))) {
                            if(f.containsKey("crypto-key"))throw new IOException("암호화된 영상은 현재 지원하지 않습니다.");
                            inputs.add(i);
                            if(!started){formats.add(f);outputs.add(mux.addTrack(f));if(mime.startsWith("video/")){width=f.getInteger(MediaFormat.KEY_WIDTH);height=f.getInteger(MediaFormat.KEY_HEIGHT);if(f.containsKey(MediaFormat.KEY_ROTATION))mux.setOrientationHint(f.getInteger(MediaFormat.KEY_ROTATION));}}
                        }
                    }
                    if(inputs.isEmpty()||width==0)throw new IOException("다운로드한 파일에서 영상 트랙을 찾지 못했습니다.");
                    if(!started){mux.start();started=true;}
                    if(inputs.size()!=formats.size())throw new IOException("구간마다 오디오·영상 구성이 달라 합칠 수 없습니다.");
                    long partOrigin=Long.MAX_VALUE;
                    for(int track:inputs) {
                        ex.selectTrack(track);ex.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        if(ex.getSampleSize()<0)throw new IOException("비어 있는 영상 트랙입니다.");
                        partOrigin=Math.min(partOrigin,ex.getSampleTime());ex.unselectTrack(track);
                    }
                    long partEnd=0;
                    for(int t=0;t<inputs.size();t++) {
                        MediaFormat f=ex.getTrackFormat(inputs.get(t));if(!compatible(formats.get(t),f))throw new IOException("구간마다 영상 코덱 또는 화질이 달라 합칠 수 없습니다.");
                        ex.selectTrack(inputs.get(t));ex.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC);long first=ex.getSampleTime(),last=0;int count=0;
                        while(true) {
                            check();long sample=ex.getSampleSize();if(sample<0)break;if(sample>buffer.capacity())throw new IOException("영상 프레임 크기가 지원 범위를 초과했습니다.");
                            buffer.clear();int size=ex.readSampleData(buffer,0);if(size<0)break;long time=ex.getSampleTime();
                            if((ex.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IOException("암호화된 영상은 현재 지원하지 않습니다.");
                            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();info.set(0,size,MediaPlan.alignedTimestamp(time,partOrigin,offset),(ex.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
                            mux.writeSampleData(outputs.get(t),buffer,info);last=Math.max(last,time-partOrigin);count++;ex.advance();
                        }
                        ex.unselectTrack(inputs.get(t));if(count==0)throw new IOException("비어 있는 영상 트랙입니다.");
                        long duration=f.containsKey(MediaFormat.KEY_DURATION)?Math.max(0,first-partOrigin)+f.getLong(MediaFormat.KEY_DURATION):last+33333;
                        // Fragmented sources may report zero duration; last sample is authoritative in that case.
                        partEnd=Math.max(partEnd,Math.max(duration,last+1000));
                    }
                    offset+=partEnd;total=offset/1000;
                } finally {ex.release();}
            }
            check();if(expected>0&&total<expected*0.95)throw new IOException("영상 길이가 원본보다 짧아 저장을 중단했습니다.");
            mux.stop();started=false;mux.release();mux=null;
            if(target.length()<1024)throw new IOException("완성된 영상 파일이 올바르지 않습니다.");
            return new Result(title,width,height,target.length(),total);
        } finally {if(mux!=null){try {if(started)mux.stop();}catch(Exception ignored){}mux.release();}}
    }
    private static boolean compatible(MediaFormat a,MediaFormat b) {
        for(String k:new String[]{MediaFormat.KEY_MIME,MediaFormat.KEY_WIDTH,MediaFormat.KEY_HEIGHT,MediaFormat.KEY_SAMPLE_RATE,MediaFormat.KEY_CHANNEL_COUNT}) {
            if(a.containsKey(k)!=b.containsKey(k))return false;
            if(a.containsKey(k)) {
                if(MediaFormat.KEY_MIME.equals(k)){if(!Objects.equals(a.getString(k),b.getString(k)))return false;}
                else if(a.getInteger(k)!=b.getInteger(k))return false;
            }
        }
        for(String k:new String[]{"csd-0","csd-1","csd-2"})if(a.containsKey(k)!=b.containsKey(k)||(a.containsKey(k)&&!a.getByteBuffer(k).equals(b.getByteBuffer(k))))return false;
        return true;
    }
}
