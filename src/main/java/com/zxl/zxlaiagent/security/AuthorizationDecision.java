package com.zxl.zxlaiagent.security;
public record AuthorizationDecision(String decisionId,String version,boolean allowed,String code) { public void requireAllowed(){if(!allowed)throw new SecurityFailure(code,"UNAUTHENTICATED".equals(code)?401:403);} }
