package com.zxl.zxlaiagent.security;
public record AuthenticatedActor(String subjectId,String tenantId,String sessionId,long authenticationVersion,String role,long authorizationVersion) {
 public ActorRef ref(){return new ActorRef(subjectId,tenantId,sessionId,authenticationVersion);}
}
