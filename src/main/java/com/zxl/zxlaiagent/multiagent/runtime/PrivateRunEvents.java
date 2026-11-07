package com.zxl.zxlaiagent.multiagent.runtime;

import com.zxl.zxlaiagent.multiagent.model.AgentEvent;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 每个运行独占的有界内存事件历史；不是持久化重放日志。读取与返回仍须由入口实时授权。 */
public final class PrivateRunEvents implements Consumer<AgentEvent> {
    private final int capacity;
    private final ArrayDeque<AgentEvent> events;

    public PrivateRunEvents(){this(256);}
    public PrivateRunEvents(int capacity){
        if(capacity<1||capacity>1024)throw new IllegalArgumentException("Private event capacity must be 1..1024");
        this.capacity=capacity;
        this.events=new ArrayDeque<>(capacity);
    }
    /** 仅操作本地队列，不调用观察者、不发网络请求；容量满时丢弃最旧记录。 */
    @Override public synchronized void accept(AgentEvent event){
        Objects.requireNonNull(event,"Event required");
        if(events.size()==capacity)events.removeFirst();
        events.addLast(event);
    }
    /** Prepare under an authority gate. Pending entries occupy no visible-history slot. */
    public Prepared prepare(AgentEvent event){return new Prepared(Objects.requireNonNull(event,"Event required"));}
    public final class Prepared {
        private final AgentEvent event;
        private boolean resolved;
        private Prepared(AgentEvent event){this.event=event;}
        /** Release visibility only after the authority transaction successfully returns. No callbacks. */
        public void commit(){synchronized(PrivateRunEvents.this){if(resolved)return;accept(event);resolved=true;}}
        public void discard(){synchronized(PrivateRunEvents.this){resolved=true;}}
    }
    /** 返回独立、不可增删的列表；事件对象沿用现有 AgentEvent 契约。 */
    public synchronized List<AgentEvent> snapshot(){return List.copyOf(events);}
}
