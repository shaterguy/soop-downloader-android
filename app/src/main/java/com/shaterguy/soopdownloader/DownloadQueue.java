package com.shaterguy.soopdownloader;

import java.util.*;

/** All admission and completion operations run on the service main thread. */
final class DownloadQueue {
    static final class Job {
        final String id=UUID.randomUUID().toString(), url;
        volatile boolean cancelled;
        boolean running;
        String phase="대기 중";
        int progress;
        long lastNotice;
        Job(String url){this.url=url;}
    }
    private final LinkedHashMap<String,Job> jobs=new LinkedHashMap<>();
    private int limit=5;
    void setLimit(int value){if(value<1)throw new IllegalArgumentException("1 이상의 정수를 입력해 주세요.");limit=value;}
    Job add(String url){for(Job j:jobs.values())if(j.url.equals(url))return null;Job j=new Job(url);jobs.put(j.id,j);return j;}
    List<Job> startReady(){
        List<Job> started=new ArrayList<>();int count=activeCount();
        for(Job j:jobs.values())if(!j.running && count<limit){j.running=true;j.phase="최고 화질 확인 중";started.add(j);count++;}
        return started;
    }
    Job cancel(String id){Job j=jobs.get(id);if(j!=null){j.cancelled=true;if(!j.running)jobs.remove(id);}return j;}
    void finish(Job j){jobs.remove(j.id);}
    boolean contains(Job j){return jobs.get(j.id)==j;}
    List<Job> snapshot(){return new ArrayList<>(jobs.values());}
    int activeCount(){int n=0;for(Job j:jobs.values())if(j.running)n++;return n;}
    int queuedCount(){return jobs.size()-activeCount();}
    boolean isEmpty(){return jobs.isEmpty();}
    void cancelAll(){for(Job j:jobs.values())j.cancelled=true;jobs.clear();}
}
