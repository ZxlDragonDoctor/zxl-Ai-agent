package com.zxl.zxlaiagent.knowledge;

import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A separate JVM proves database persistence, with no model or full application initialization. */
public final class KnowledgeProcessProbe {
    public static void main(String[] args)throws Exception{
        var db=new KnowledgeDatabase(System.getenv("LESSON12_DB_URL"),System.getenv("LESSON12_DB_USER"),System.getenv("LESSON12_DB_PASSWORD"));
        var service=new KnowledgeService(db,args[0],new DemoKnowledgeEmbedding());
        if("write".equals(args[1]))service.importDocument("vpn","vpn.md","# VPN\nPersistent process evidence");
        Files.writeString(Path.of(args[2]),new ObjectMapper().writeValueAsString(service.detail("vpn")));
        if(service.search("# VPN\nPersistent process evidence",5).hits().size()!=1)throw new IllegalStateException("Persisted vector missing");
    }
}
