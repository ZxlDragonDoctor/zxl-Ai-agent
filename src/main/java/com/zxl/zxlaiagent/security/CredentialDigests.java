package com.zxl.zxlaiagent.security;
import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
final class CredentialDigests {
 private static final SecureRandom RANDOM=new SecureRandom();
 static String random(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
 static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 static boolean matches(String raw,String hash){return raw!=null&&MessageDigest.isEqual(hash(raw).getBytes(StandardCharsets.US_ASCII),hash.getBytes(StandardCharsets.US_ASCII));}
}
