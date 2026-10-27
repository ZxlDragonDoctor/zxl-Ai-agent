package com.zxl.zxlaiagent.security;
public final class SecurityFailure extends RuntimeException {
 private final String code; private final int status;
 public SecurityFailure(String code,int status){super(code);this.code=code;this.status=status;}
 public String code(){return code;} public int status(){return status;}
}
