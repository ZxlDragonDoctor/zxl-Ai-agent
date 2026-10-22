package com.zxl.zxlaiagent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.ticket.TicketCommand;
import java.nio.file.*;

/** Child JVM probe for persistence regression; uses only caller-provided temporary H2 database. */
public final class PersistenceProcessProbe {
    public static void main(String[] args) throws Exception {
        var repository=new PersistentWorkflowRepository(new LessonDatabase(args[0],"sa",""));
        String id=args[3];
        if(args[2].equals("write")) {
            id=repository.create(new TicketCommand("process-op","Persisted across JVMs","test fixture"),600_000).id();
            if(!repository.complete(repository.claim(id,"child-writer",60_000).orElseThrow()))throw new IllegalStateException("completion rejected");
        }
        Files.writeString(Path.of(args[1]),new ObjectMapper().writeValueAsString(repository.get(id)));
    }
}
