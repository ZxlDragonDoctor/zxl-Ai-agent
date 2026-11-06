package com.zxl.zxlaiagent.multiagent;

import com.zxl.zxlaiagent.multiagent.runtime.*;
import com.zxl.zxlaiagent.multiagent.model.*;
import com.zxl.zxlaiagent.security.ActorRef;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class PrivateRunEventsTest {
    AgentEvent event(){return AgentEvent.of("synthetic",EventType.TASK_STARTED);}
    @Test void rejectsInvalidCapacityAndNullEvents(){
        assertThrows(IllegalArgumentException.class,()->new PrivateRunEvents(0));
        assertThrows(IllegalArgumentException.class,()->new PrivateRunEvents(1025));
        assertDoesNotThrow(()->new PrivateRunEvents(1));assertDoesNotThrow(()->new PrivateRunEvents(1024));
        assertThrows(NullPointerException.class,()->new PrivateRunEvents().accept(null));
    }
    @Test void fullQueueDropsOldestAndSnapshotIsImmutableAndIndependent(){
        var queue=new PrivateRunEvents(2);var first=event();var second=event();var third=event();
        queue.accept(first);queue.accept(second);var before=queue.snapshot();queue.accept(third);
        assertEquals(List.of(first,second),before);assertEquals(List.of(second,third),queue.snapshot());
        assertThrows(UnsupportedOperationException.class,()->before.add(third));
    }
    @Test void defaultCapacityIs256(){
        var queue=new PrivateRunEvents();var first=event();queue.accept(first);
        for(int i=0;i<256;i++)queue.accept(event());
        assertEquals(256,queue.snapshot().size());assertFalse(queue.snapshot().contains(first));
    }
    @Test void trustedContextCannotAcceptAnArbitraryObserver() throws Exception {
        assertTrue(Modifier.isFinal(PrivateRunEvents.class.getModifiers()));
        assertNotNull(TrustedRunContext.class.getConstructor(ActorRef.class,String.class,PrivateRunEvents.class));
        assertThrows(NoSuchMethodException.class,()->TrustedRunContext.class.getConstructor(ActorRef.class,String.class,Consumer.class));
    }
}
