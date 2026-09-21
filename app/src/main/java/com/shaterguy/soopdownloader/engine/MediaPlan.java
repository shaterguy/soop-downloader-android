package com.shaterguy.soopdownloader.engine;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Pure parsing rules kept independent of Android for deterministic tests. */
public final class MediaPlan {
    private MediaPlan() {}
    public static final class Input {
        public final String id, url; public final boolean catchVideo, catchStory;
        Input(String id, boolean c, boolean s) { this.id=id; catchVideo=c; catchStory=s; url="https://vod.sooplive.com/player/"+id+(s?"/catchstory":c?"/catch":""); }
    }
    public static Input parseInput(String text) throws IOException {
        if (text==null || text.length()>16384) throw new IOException("SOOP 영상 링크 하나를 입력해 주세요.");
        Matcher urls=Pattern.compile("https?://[^\\s<>\"']+",Pattern.CASE_INSENSITIVE).matcher(text);
        Input found=null;
        while(urls.find()) {
            String raw=urls.group().replaceAll("[)\\]},.]+$", "");
            URI uri;
            try { uri=URI.create(raw); } catch(IllegalArgumentException e) { continue; }
            if(!"vod.sooplive.com".equalsIgnoreCase(uri.getHost())) continue;
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443))
                throw new IOException("HTTPS SOOP 영상 링크만 지원합니다.");
            Matcher path=Pattern.compile("^/(?:player|PLAYER/STATION)/(\\d+)(/(?:catch|catchstory))?/?$").matcher(uri.getPath());
            if(!path.matches()) throw new IOException("캐치스토리·캐치 또는 일반 다시보기 링크를 입력해 주세요.");
            String suffix=path.group(2);
            Input next=new Input(path.group(1),"/catch".equals(suffix),"/catchstory".equals(suffix));
            if(found!=null && !found.url.equals(next.url)) throw new IOException("한 번에 영상 링크 하나만 입력해 주세요.");
            found=next;
        }
        if(found==null) throw new IOException("지원하는 SOOP 캐치스토리·캐치·다시보기 링크가 없습니다.");
        return found;
    }
    static URI trusted(String text) throws IOException {
        try {
            URI u=URI.create(text); String h=u.getHost();
            if(!"https".equalsIgnoreCase(u.getScheme()) || h==null || u.getUserInfo()!=null || (u.getPort()!=-1 && u.getPort()!=443)) throw new IllegalArgumentException();
            h=h.toLowerCase(Locale.ROOT);
            if(!(h.equals("sooplive.com")||h.endsWith(".sooplive.com")||h.equals("sooplive.co.kr")||h.endsWith(".sooplive.co.kr"))) throw new IllegalArgumentException();
            return u;
        } catch(IllegalArgumentException e) { throw new IOException("영상 서버 주소를 확인할 수 없습니다."); }
    }
    public static final class Variant {
        final String url, audioGroup; final int width,height; final double fps; final long bitrate;
        boolean adaptive, externalAudio; int preference;
        public Variant(String url,int width,int height,double fps,long bitrate) { this(url,width,height,fps,bitrate,""); }
        Variant(String url,int width,int height,double fps,long bitrate,String audio) { this.url=url;this.width=width;this.height=height;this.fps=fps;this.bitrate=bitrate;audioGroup=audio; }
    }
    public static Variant best(List<Variant> list) throws IOException {
        if(list.isEmpty()) throw new IOException("제공되는 영상 화질이 없습니다.");
        return Collections.max(list,Comparator.comparingLong((Variant v)->(long)v.width*v.height).thenComparingDouble(v->v.fps).thenComparingLong(v->v.bitrate).thenComparingInt(v->v.preference));
    }
    static Map<String,String> attributes(String line) {
        Map<String,String> a=new HashMap<>(); Matcher m=Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))").matcher(line);
        while(m.find()) a.put(m.group(1),m.group(2)!=null?m.group(2):m.group(3)); return a;
    }
    static int[] resolution(String s) { try { String[] p=s.toLowerCase(Locale.ROOT).split("x"); return new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1])}; }catch(Exception e){return new int[]{0,0};} }
    static long number(String s) { try { return Long.parseLong(s); }catch(Exception e){return 0;} }
    static double decimal(String s) { try { return Double.parseDouble(s); }catch(Exception e){return 0;} }
    public static List<Variant> variants(String body,String base) throws IOException {
        List<Variant> result=new ArrayList<>(); Map<String,String> current=null;
        for(String row:body.split("\\r?\\n")) {
            row=row.trim();
            if(row.startsWith("#EXT-X-STREAM-INF:")) current=attributes(row);
            else if(!row.isEmpty()&&!row.startsWith("#")&&current!=null) {
                int[] size=resolution(current.getOrDefault("RESOLUTION",""));
                Variant v=new Variant(trusted(URI.create(base).resolve(row).toString()).toString(),size[0],size[1],decimal(current.get("FRAME-RATE")),number(current.getOrDefault("AVERAGE-BANDWIDTH",current.get("BANDWIDTH"))),current.getOrDefault("AUDIO",""));
                v.preference="original".equalsIgnoreCase(current.get("NAME"))?1:0;
                for(String media:body.split("\\r?\\n")) if(media.startsWith("#EXT-X-MEDIA:")) {
                    Map<String,String> a=attributes(media);
                    if(!v.audioGroup.isEmpty()&&v.audioGroup.equals(a.get("GROUP-ID"))&&"AUDIO".equals(a.get("TYPE"))&&a.containsKey("URI")) v.externalAudio=true;
                }
                result.add(v);current=null;
            }
        }
        return result;
    }
    interface ManifestLoader { String load(String url) throws Exception; }
    static final class Selection {
        final Variant variant; final String manifest;
        Selection(Variant variant,String manifest){this.variant=variant;this.manifest=manifest;}
    }
    static Selection select(List<Variant> supplied,ManifestLoader loader) throws Exception {
        List<Variant> candidates=new ArrayList<>();Map<String,String> cache=new HashMap<>();
        // Adaptive endpoints carry unknown variants: inspect their metadata, never their lower media leaves.
        for(Variant v:supplied) {
            if(v.adaptive) {
                String body=loader.load(v.url);cache.put(v.url,body);List<Variant> children=variants(body,v.url);
                if(children.isEmpty()) candidates.add(v);else candidates.addAll(children);
            } else candidates.add(v);
        }
        for(int depth=0;depth<6;depth++) {
            Variant selected=best(candidates);
            if(selected.externalAudio)throw new IOException("별도 오디오 트랙이 있는 최고 화질은 현재 지원하지 않습니다.");
            if(!URI.create(selected.url).getPath().toLowerCase(Locale.ROOT).contains(".m3u8"))return new Selection(selected,null);
            String body=cache.get(selected.url);if(body==null){body=loader.load(selected.url);cache.put(selected.url,body);}
            List<Variant> children=variants(body,selected.url);
            if(children.isEmpty()){playlist(body,selected.url);return new Selection(selected,body);}
            candidates.remove(selected);candidates.addAll(children);
        }
        throw new IOException("영상 재생 목록이 너무 깊게 연결되어 있습니다.");
    }
    static long alignedTimestamp(long timestamp,long partOrigin,long outputOffset) throws IOException {
        if(timestamp<partOrigin)throw new IOException("영상 타임스탬프가 구간 시작보다 앞섭니다.");
        return outputOffset+timestamp-partOrigin;
    }
    static final class Segment {
        final String url; final long offset,length; Segment(String u,long o,long n) {url=u;offset=o;length=n;}
    }
    static final class Playlist {
        final List<Segment> segments=new ArrayList<>(); double seconds; boolean fragmented;
    }
    static Playlist playlist(String body,String base) throws IOException {
        if(!body.trim().startsWith("#EXTM3U")) throw new IOException("올바른 영상 재생 목록이 아닙니다.");
        if(!body.contains("#EXT-X-ENDLIST")) throw new IOException("진행 중인 라이브 스트림은 저장할 수 없습니다.");
        Playlist p=new Playlist(); String range=null,previousUrl=null,mapUrl=null; long previousEnd=0;
        for(String line:body.split("\\r?\\n")) {
            line=line.trim();
            if(line.startsWith("#EXT-X-KEY:")&&!"NONE".equals(attributes(line).get("METHOD"))) throw new IOException("암호화된 영상은 현재 지원하지 않습니다.");
            if(line.equals("#EXT-X-DISCONTINUITY")) throw new IOException("타임라인이 변경되는 영상 형식은 현재 지원하지 않습니다.");
            if(line.startsWith("#EXT-X-MAP:")) {
                Map<String,String> a=attributes(line); String u=trusted(URI.create(base).resolve(a.getOrDefault("URI","")).toString()).toString();
                if(mapUrl!=null&&!mapUrl.equals(u)) throw new IOException("초기화 정보가 변경되는 영상은 현재 지원하지 않습니다.");
                if(mapUrl==null) {long[] r=parseRange(a.get("BYTERANGE"),0);p.segments.add(new Segment(u,r[0],r[1]));mapUrl=u;p.fragmented=true;}
            } else if(line.startsWith("#EXT-X-BYTERANGE:")) range=line.substring(17);
            else if(line.startsWith("#EXTINF:")) p.seconds+=decimal(line.substring(8).split(",")[0]);
            else if(!line.isEmpty()&&!line.startsWith("#")) {
                String u=trusted(URI.create(base).resolve(line).toString()).toString();
                if(range!=null&&!range.contains("@")&&!u.equals(previousUrl)) throw new IOException("잘못된 영상 구간 범위입니다.");
                long[] r=parseRange(range,previousEnd);p.segments.add(new Segment(u,r[0],r[1]));previousUrl=u;previousEnd=r[0]+Math.max(0,r[1]);range=null;
            }
        }
        if(p.segments.size()<(p.fragmented?2:1)) throw new IOException("영상 구간이 없습니다.");
        return p;
    }
    private static long[] parseRange(String range,long previous) throws IOException {
        if(range==null)return new long[]{0,-1};
        try {String[] r=range.split("@");long n=Long.parseLong(r[0]),o=r.length==2?Long.parseLong(r[1]):previous;if(n<=0||o<0||Long.MAX_VALUE-o<n)throw new IllegalArgumentException();return new long[]{o,n};}catch(Exception e){throw new IOException("잘못된 영상 구간 범위입니다.");}
    }
}
