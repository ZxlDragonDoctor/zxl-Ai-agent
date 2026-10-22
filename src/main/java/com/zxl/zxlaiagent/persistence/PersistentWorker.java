package com.zxl.zxlaiagent.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;

/** Two execution slots + eight queued tasks. Database remains the durable backlog. */
public final class PersistentWorker implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(PersistentWorker.class);
    private final PersistentWorkflowRepository repository;
    private final String owner="lesson9-"+UUID.randomUUID();
    private final Set<String> queued=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(8),r -> {Thread t=new Thread(r,"lesson9-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService scanner=Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"lesson9-recovery");t.setDaemon(true);return t;});
    private final boolean enabled;
    public PersistentWorker(PersistentWorkflowRepository repository,boolean enabled) {
        this.repository=repository;this.enabled=enabled;
        if(enabled) scanner.scheduleWithFixedDelay(this::safeScan,0,1,TimeUnit.SECONDS);
    }
    private void safeScan() {try{scan();}catch(RuntimeException e){log.warn("Lesson 9 recovery scan failed; next bounded scan will retry",e);}}
    public int scan() {
        if(!enabled) return 0;
        int accepted=0;
        for(String id:repository.recoverable(10)) {
            if(!queued.add(id))continue;
            try {workers.execute(() -> {
                try {repository.claim(id,owner,5000).ifPresent(repository::complete);}
                catch(RuntimeException e) {log.warn("Lesson 9 execution failed; original lease/deadline retained for recovery, run={}",id,e);}
                finally {queued.remove(id);}
            });accepted++;}
            catch(RejectedExecutionException full){queued.remove(id);break;}
        }
        return accepted;
    }
    @Override public void close() {scanner.shutdownNow();workers.shutdownNow();}
}
