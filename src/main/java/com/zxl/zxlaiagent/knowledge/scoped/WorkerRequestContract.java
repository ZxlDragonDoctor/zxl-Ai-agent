package com.zxl.zxlaiagent.knowledge.scoped;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.zxl.zxlaiagent.security.ServiceCallAuthorizer;

/** Shared by the fixed gateway and worker. Identity never comes from a business parameter. */
public final class WorkerRequestContract {
 public static final String AUDIENCE="knowledge-worker", TICKET_HEADER="X-Service-Ticket", BASE="/ai/learning/knowledge";
 private WorkerRequestContract(){}
 public static String canonicalTarget(String path,String rawQuery){
  if(path==null||!path.startsWith("/")||path.contains("?")||path.contains("#")||path.contains("..")||path.contains("%")||path.contains("\\"))throw new IllegalArgumentException("Invalid worker path");
  if(rawQuery==null||rawQuery.isEmpty())return path;
  var sorted=new TreeMap<String,String>();
  for(String pair:rawQuery.split("&",-1)){var parts=pair.split("=",2);String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8),value=parts.length==1?"":URLDecoder.decode(parts[1],StandardCharsets.UTF_8);if(!Set.of("q","limit","maxChars","version").contains(key)||sorted.putIfAbsent(key,value)!=null)throw new IllegalArgumentException("Unsupported or duplicate query field");}
  return path+"?"+sorted.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
 }
 private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20").replace("%7E","~");}
 public static String hash(String method,String path,String rawQuery,byte[] body){return ServiceCallAuthorizer.requestHash(method,canonicalTarget(path,rawQuery),body);}
 public static String action(String method,String path){
  String suffix=path.startsWith(BASE)?path.substring(BASE.length()):"";
  if(("POST".equals(method)&&"/documents".equals(suffix))||("DELETE".equals(method)&&suffix.matches("/documents/[A-Za-z0-9_-]{1,120}")))return "knowledge.manage";
  boolean read="GET".equals(method)&&("/status".equals(suffix)||"/documents".equals(suffix)||"/search".equals(suffix)||suffix.matches("/documents/[A-Za-z0-9_-]{1,120}")||"/memories".equals(suffix)||"/memories/recall".equals(suffix));
  boolean write="POST".equals(method)&&Set.of("/retrieval","/answers","/memories/proposals").contains(suffix)||"POST".equals(method)&&suffix.matches("/memories/[A-Za-z0-9_-]{1,80}/(confirm|reject)")||Set.of("PATCH","DELETE").contains(method)&&suffix.matches("/memories/[A-Za-z0-9_-]{1,80}");
  if(read||write)return "knowledge.read";
  throw new IllegalArgumentException("Unsupported worker route");
 }
}
