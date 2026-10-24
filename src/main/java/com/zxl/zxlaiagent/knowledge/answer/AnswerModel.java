package com.zxl.zxlaiagent.knowledge.answer;

import java.time.Duration;
import java.util.*;

/** Separate generation and judging calls are required in real mode; rules are only a disclosed test substitute. */
public interface AnswerModel {
 record Draft(String snapshotId,List<AnswerService.Claim> claims,List<String> missingInformation) { public Draft(String snapshotId,List<AnswerService.Claim> claims){this(snapshotId,claims,List.of());} }
 record Verdict(String support,String reason) { }
 Draft generate(String question,String snapshotId,List<AnswerService.Evidence> evidence,MemoryContext.MemoryView memory,Duration remaining);
 Verdict judge(String question,AnswerService.Claim claim,List<AnswerService.Evidence> evidence,Duration remaining);
 String identity();
 default String judgeIdentity(){return identity();}
 static AnswerModel demo(){return new AnswerModel(){
  public String identity(){return "demo-literal-containment-v1";}
  public Draft generate(String q,String s,List<AnswerService.Evidence> es,MemoryContext.MemoryView m,Duration t){
   var terms=com.zxl.zxlaiagent.knowledge.retrieval.QueryTerms.extract(q);
   List<String> numbers=new ArrayList<>();var matcher=java.util.regex.Pattern.compile("\\d+").matcher(q);while(matcher.find())numbers.add(matcher.group());
   var relevant=es.stream().filter(e->terms.stream().anyMatch(term->e.text().toLowerCase(Locale.ROOT).contains(term))&&numbers.stream().allMatch(e.text()::contains)).limit(3).toList();
   return new Draft(s,relevant.stream().map(e->new AnswerService.Claim(e.text().length()>500?e.text().substring(0,500):e.text(),List.of(e.id()))).toList(),q.contains("年假")&&es.stream().noneMatch(e->e.text().contains("年假"))?List.of("缺少公司年假制度资料，无法回答年假子问题。"):List.of());
  }
  public Verdict judge(String q,AnswerService.Claim c,List<AnswerService.Evidence> es,Duration t){return new Verdict(es.stream().anyMatch(e->e.text().contains(c.text()))?"SUPPORTED":"INSUFFICIENT","仅逐字包含规则；不代表语义判断通过");}
 };}
}
