package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.security.*;
import jakarta.servlet.http.HttpServletRequest;

/** MVC entry context only. Async work must capture and explicitly pass actor.ref(). */
public final class AuthenticatedRequest {
    static final String ACTOR = AuthenticatedRequest.class.getName()+".actor";
    static final String DECISION = AuthenticatedRequest.class.getName()+".decision";
    private AuthenticatedRequest() {}
    public static AuthenticatedActor require(HttpServletRequest request) {
        if (request != null && request.getAttribute(ACTOR) instanceof AuthenticatedActor actor) return actor;
        throw new SecurityFailure("UNAUTHENTICATED",401);
    }
}
