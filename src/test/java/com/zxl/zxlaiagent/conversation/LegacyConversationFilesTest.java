package com.zxl.zxlaiagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.conversation.model.Conversation;
import com.zxl.zxlaiagent.conversation.repository.LegacyConversationFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyConversationFilesTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    byte[] create(UUID id)throws Exception{return mapper.writeValueAsBytes(new Conversation(id,"synthetic",Instant.now(),Instant.now(),0,List.of(),List.of(),"manus"));}
    @Test void verifiesSameBytesAndNeverRewritesSource()throws Exception{
        var id=UUID.randomUUID();var bytes=create(id);var path=directory.resolve(id+".json");Files.write(path,bytes);
        var snapshot=new LegacyConversationFiles(directory,mapper).read(id,LegacyConversationFiles.sha256(bytes));
        assertEquals(id,snapshot.conversation().id());assertArrayEquals(bytes,snapshot.bytes());snapshot.bytes()[0]=0;assertArrayEquals(bytes,Files.readAllBytes(path));assertArrayEquals(bytes,snapshot.bytes());
        Files.writeString(path,"changed");assertThrows(IllegalStateException.class,()->new LegacyConversationFiles(directory,mapper).read(id,snapshot.sha256()));
    }
    @Test void rejectsWrongIdDigestAndStrictJson()throws Exception{
        var id=UUID.randomUUID();var path=directory.resolve(id+".json");var helper=new LegacyConversationFiles(directory,mapper);
        var different=create(UUID.randomUUID());Files.write(path,different);assertThrows(IllegalStateException.class,()->helper.read(id,LegacyConversationFiles.sha256(different)));
        var normal=create(id);Files.write(path,normal);assertThrows(IllegalArgumentException.class,()->helper.read(id,"INVALID"));
        for(var raw:List.of(new String(normal,java.nio.charset.StandardCharsets.UTF_8)+" {}",new String(normal,java.nio.charset.StandardCharsets.UTF_8).replaceFirst("\\{","{\"id\":\""+id+"\","))){
            byte[] invalid=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(path,invalid);assertThrows(IllegalStateException.class,()->helper.read(id,LegacyConversationFiles.sha256(invalid)));
        }
    }
    @Test void rejectsOversizedInputBeforeParsing()throws Exception{
        var id=UUID.randomUUID();Files.write(directory.resolve(id+".json"),new byte[8*1024*1024+1]);
        assertThrows(IllegalStateException.class,()->new LegacyConversationFiles(directory,mapper).read(id,"0".repeat(64)));
    }
}
