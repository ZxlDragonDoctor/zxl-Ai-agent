package com.zxl.zxlaiagent.knowledge.evaluation;

import com.zxl.zxlaiagent.knowledge.KnowledgeText;
import java.util.*;

/** Synthetic fixture v1. Labels are editorial judgments, never generated from retrieved rankings. */
public record EvaluationDataset(String version,String snapshotId,String modelIdentity,int dimensions,
        List<Document> documents,Map<String,Chunk> chunks,List<Question> questions) {
    public static final String TENANT="demo-evaluation";
    public static String tenant(String model){return model.startsWith("http:")?TENANT+"-real":TENANT;}
    public record Document(String key,String tenant,String id,String source,String text,boolean active){}
    public record Chunk(String key,String tenant,String documentId,String revisionId,String chunkId,String profileHash,boolean active){}
    public record Question(String queryId,String question,String split,Map<String,Integer> labels,Set<String> forbidden,String reason){}
    public static EvaluationDataset create(String model,int dimensions) {
        String scope=tenant(model);
        var docs=List.of(
            new Document("809",TENANT,"eval-vpn809","vpn809.md","VPN 错误码 809：连接服务器无响应。检查网络，允许 UDP 500 和 UDP 4500，通过防火墙后重试。",true),
            new Document("691",TENANT,"eval-vpn691","vpn691.md","VPN 错误码 691：用户名或密码不正确。检查域账号格式与密码，禁止反复尝试导致锁定。",true),
            new Document("lock",TENANT,"eval-lock","lock.md","账号锁定：连续五次认证失败，等待十五分钟自动解锁；紧急情况提交身份核验工单。",true),
            new Document("reset",TENANT,"eval-reset","reset.md","密码重置：忘记密码请进入自助门户，用手机验证码完成身份核验，设置新密码。",true),
            new Document("mfa",TENANT,"eval-mfa","mfa.md","MFA 多因素认证：手机动态口令失效先校准手机时间，换手机需提交工单重新绑定验证器。",true),
            new Document("print",TENANT,"eval-print","printer.md","打印机离线：先检查电源和网线，清理打印队列；仍异常请提供设备编号提交打印机工单。",true),
            new Document("noise",TENANT,"eval-printer809","printer809.md","打印机资产编号 809：该编号仅是设备资产编号，不是 VPN 错误码；耗材申请请走打印机工单。",true),
            new Document("old",TENANT,"eval-vpn809","vpn809.md","旧版 VPN 809 指南：关闭全部防火墙。此步骤已废弃，禁止采用。",false),
            new Document("other","demo-evaluation-other","eval-vpn809","vpn809.md","其他租户 VPN 809：内部专用网络和密钥配置，仅供该租户使用。",true));
        docs=docs.stream().map(d->new Document(d.key(),d.tenant().equals(TENANT)?scope:d.tenant(),d.id(),d.source(),d.text(),d.active())).toList();
        Map<String,Chunk> chunks=new LinkedHashMap<>();Map<String,String> keys=new HashMap<>();
        for(var doc:docs){var p=KnowledgeText.prepare(doc.tenant(),doc.id(),doc.source(),doc.text(),400,model,dimensions);if(p.chunks().size()!=1)throw new IllegalStateException("Fixture chunk profile changed");var ch=p.chunks().get(0);chunks.put(ch.chunkId(),new Chunk(doc.key(),doc.tenant(),doc.id(),p.revisionId(),ch.chunkId(),p.profileHash(),doc.active()));keys.put(doc.key(),ch.chunkId());}
        List<Question> questions=new ArrayList<>();
        add(questions,keys,"01","VPN 提示 809，先排查哪里？","tuning", "809",null);
        add(questions,keys,"02","vpn报错809咋办","tuning","809",null);
        add(questions,keys,"03","VPN error 809 UDP 哪些端口","holdout","809",null);
        add(questions,keys,"04","连接服务器一直没响应，虚拟专网进不去","holdout","809",null);
        add(questions,keys,"05","691 用户名密码不正确怎么办","tuning","691",null);
        add(questions,keys,"06","vpn账号密码被拒绝啦","holdout","691",null);
        add(questions,keys,"07","VPN 691 能一直重试密码吗","holdout","691","lock");
        add(questions,keys,"08","账户锁住了等多久","tuning","lock",null);
        add(questions,keys,"09","输错五次进不去了","holdout","lock",null);
        add(questions,keys,"10","账号锁定很着急咋找人解开","holdout","lock",null);
        add(questions,keys,"11","密码忘了如何重置","tuning","reset",null);
        add(questions,keys,"12","忘记口令，自助门户怎么验证身份","holdout","reset",null);
        add(questions,keys,"13","MFA 动态码不对","tuning","mfa",null);
        add(questions,keys,"14","多因素认证换手机咋整","holdout","mfa",null);
        add(questions,keys,"15","验证器口令过期要校时间吗","holdout","mfa",null);
        add(questions,keys,"16","打印机离线提交工单要什么信息","tuning","print",null);
        add(questions,keys,"17","打印不了先检查啥","holdout","print",null);
        add(questions,keys,"18","VPN809还有打印机离线都要处理","holdout","809","print");
        add(questions,keys,"19","VPN691锁定了还忘记密码","tuning","lock","reset");
        add(questions,keys,"20","公司年假可以休几天","holdout",null,null);
        add(questions,keys,"21","报销发票抬头是什么","tuning",null,null);
        add(questions,keys,"22","别的租户VPN专用密钥是多少","holdout",null,null);
        String snapshot=KnowledgeText.hash("synthetic-helpdesk-v1","utf16-paragraph-v1:400",model,String.valueOf(dimensions),String.join(",",chunks.keySet()));
        return new EvaluationDataset("synthetic-helpdesk-v1",snapshot,model,dimensions,docs,Collections.unmodifiableMap(chunks),List.copyOf(questions));
    }
    private static void add(List<Question> list,Map<String,String> keys,String id,String text,String split,String primary,String secondary){
        Map<String,Integer> grades=new LinkedHashMap<>();if(primary!=null)grades.put(keys.get(primary),2);if(secondary!=null)grades.put(keys.get(secondary),1);
        grades.put(keys.get("noise"),0);
        list.add(new Question("helpdesk-"+id,text,split,Map.copyOf(grades),Set.of(keys.get("old"),keys.get("other")),primary==null?"资料没有此问题的答案；其他租户与旧版仍禁止出现":"人工标注：直接处理主意图为2，辅助意图为1；打印机809资产编号为0级干扰。"));
    }
    public void validate(String actualSnapshot,String actualModel,Set<String> existingChunks){
        if(!snapshotId.equals(actualSnapshot)||!modelIdentity.equals(actualModel))throw new IllegalArgumentException("Evaluation snapshot/model mismatch");
        Set<String> ids=new HashSet<>();
        for(var q:questions){if(!ids.add(q.queryId()))throw new IllegalArgumentException("Duplicate queryId: "+q.queryId());
            for(var entry:q.labels().entrySet()){if(!chunks.containsKey(entry.getKey())||entry.getValue()<0||entry.getValue()>2)throw new IllegalArgumentException("Invalid qrel");var ch=chunks.get(entry.getKey());if(!ch.active()||!tenant(modelIdentity).equals(ch.tenant())||!existingChunks.contains(entry.getKey()))throw new IllegalArgumentException("Missing/wrong-scope target chunk");}
            if(!chunks.keySet().containsAll(q.forbidden())||!Collections.disjoint(q.labels().keySet(),q.forbidden()))throw new IllegalArgumentException("Invalid forbidden labels");
        }
    }
}
