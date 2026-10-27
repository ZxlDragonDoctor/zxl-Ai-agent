package com.zxl.zxlaiagent.security;
/** New JVM probe. Credential is read through stdin, never command-line arguments or logs. */
public final class SecurityRestartProbe {
 public static void main(String[] args)throws Exception{String token=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8)).readLine();var auth=new AuthenticationService(SecurityDatabase.fromEnvironment());System.out.println("ACTOR="+auth.authenticate(token).subjectId());}
}
