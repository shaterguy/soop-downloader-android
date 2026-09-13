package com.shaterguy.soopdownloader;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class DownloadQueueTest {
    @Test public void defaultFiveThenSixthStartsAfterCompletion(){
        DownloadQueue q=new DownloadQueue();for(int i=0;i<6;i++)q.add("video"+i);
        List<DownloadQueue.Job> first=q.startReady();assertEquals(5,first.size());assertEquals(1,q.queuedCount());
        assertTrue(q.startReady().isEmpty());q.finish(first.get(2));
        assertEquals("video5",q.startReady().get(0).url);assertEquals(5,q.activeCount());
    }
    @Test public void ownerCanChooseMoreThanFive(){
        DownloadQueue q=new DownloadQueue();q.setLimit(10);for(int i=0;i<12;i++)q.add("video"+i);
        assertEquals(10,q.startReady().size());assertEquals(2,q.queuedCount());
    }
    @Test public void raisingLimitStartsWaitingJobsImmediately(){
        DownloadQueue q=new DownloadQueue();q.setLimit(1);for(int i=0;i<8;i++)q.add("video"+i);
        assertEquals(1,q.startReady().size());q.setLimit(7);assertEquals(6,q.startReady().size());assertEquals(7,q.activeCount());
    }
    @Test public void loweringLimitDoesNotCancelRunningWork(){
        DownloadQueue q=new DownloadQueue();for(int i=0;i<8;i++)q.add("video"+i);
        List<DownloadQueue.Job> active=q.startReady();q.setLimit(2);assertTrue(q.startReady().isEmpty());
        for(DownloadQueue.Job j:active)assertFalse(j.cancelled);
        for(int i=0;i<3;i++){q.finish(active.get(i));assertTrue(q.startReady().isEmpty());}
        q.finish(active.get(3));assertEquals(1,q.startReady().size());assertEquals(2,q.activeCount());
    }
    @Test public void cancellingOneKeepsItsSlotUntilWorkerActuallyExits(){
        DownloadQueue q=new DownloadQueue();q.setLimit(2);for(int i=0;i<4;i++)q.add("video"+i);
        List<DownloadQueue.Job> active=q.startReady();DownloadQueue.Job cancelled=active.get(0);
        q.cancel(cancelled.id);assertTrue(cancelled.cancelled);assertFalse(active.get(1).cancelled);
        assertTrue(q.startReady().isEmpty());assertEquals(2,q.queuedCount());
        q.finish(cancelled);DownloadQueue.Job next=q.startReady().get(0);assertNull(q.cancel(cancelled.id));assertFalse(next.cancelled);
    }
    @Test public void queuedCancelAndDuplicateDoNotAffectOtherJobs(){
        DownloadQueue q=new DownloadQueue();q.setLimit(1);q.add("a");q.startReady();DownloadQueue.Job b=q.add("b");q.add("c");
        assertNull(q.add("a"));assertNull(q.add("b"));q.cancel(b.id);assertEquals(1,q.queuedCount());assertEquals(1,q.activeCount());
    }
    @Test public void invalidLimitIsRejected(){
        DownloadQueue q=new DownloadQueue();for(int n:new int[]{0,-1})try{q.setLimit(n);fail();}catch(IllegalArgumentException expected){}
        q.setLimit(Integer.MAX_VALUE);q.add("a");assertEquals(1,q.startReady().size());
    }
}
