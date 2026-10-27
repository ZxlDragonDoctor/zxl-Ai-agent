package com.zxl.zxlaiagent.security;
public record ActorRef(String subjectId,String tenantId,String sessionId,long authenticationVersion) {}
