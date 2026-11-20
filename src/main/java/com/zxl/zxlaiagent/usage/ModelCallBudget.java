package com.zxl.zxlaiagent.usage;

/** Explicit trusted accounting; callers must never obtain policy from model arguments. */
public interface ModelCallBudget {
    Reservation reserve();
    interface Reservation {
        void dispatch();
        /** Null means that complete provider usage was not observed. */
        void finish(Long actualTotal);
    }
}
