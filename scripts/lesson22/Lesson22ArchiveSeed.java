import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.security.*;
import java.nio.file.*;
import java.util.*;
/** Trusted offline archive fixture; source directory must match the live acceptance stack. */
class Lesson22ArchiveSeed {
 public static void main(String[] args)throws Exception{
  String tenant=args[0];if(!tenant.matches("lesson22-access-[a-z0-9-]+"))throw new IllegalArgumentException();
  var source=Path.of(args[1]).toAbsolutePath();var root=Files.createDirectory(Path.of(args[2]));var backup=Files.createDirectory(root.resolve("backup"));
  var mapper=new ObjectMapper().findAndRegisterModules();String id=UUID.randomUUID().toString(),subject=tenant+"-archive";
  byte[] bytes=mapper.writeValueAsBytes(Map.of("id",id,"title","Synthetic workspace archive","agentType","retired-provider","createdAt","2026-10-01T10:00:00Z","updatedAt","2026-10-01T10:00:00Z","version",1,"messages",List.of(),"runs",List.of()));
  Files.write(source.resolve(id+".json"),bytes,StandardOpenOption.CREATE_NEW);
  String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  var manifest=root.resolve("manifest.json");mapper.writeValue(manifest.toFile(),Map.of("importId",UUID.randomUUID().toString(),"tenantId",tenant,"subjectId",subject,"entries",List.of(Map.of("conversationId",id,"sha256",hash))));
  try(var db=SecurityDatabase.fromEnvironment()){
   new AuthenticationService(db).provision(subject,subject,System.getenv("LESSON22_ACCESS_PASSWORD"),tenant,"employee");
   new LegacyHistoryImporter(db,source,backup,mapper).importHistory(LegacyHistoryManifest.load(manifest,mapper));
  }
  mapper.writeValue(root.resolve("archive.json").toFile(),Map.of("tenantId",tenant,"subjectId",subject,"archiveId",id));
 }
}
