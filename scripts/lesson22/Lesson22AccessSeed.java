import com.zxl.zxlaiagent.security.*;
/** Offline fixture only. Password is never placed on the command line or printed. */
class Lesson22AccessSeed {
 public static void main(String[] args){
  if(args.length!=1||!args[0].matches("lesson22-access-[a-z0-9-]+"))throw new IllegalArgumentException("Dedicated fixture required");
  try(var db=SecurityDatabase.fromEnvironment()){
   var auth=new AuthenticationService(db);String t=args[0],p=System.getenv("LESSON22_ACCESS_PASSWORD");
   for(String who:new String[]{"owner","other","revoked","disabled"})auth.provision(t+"-"+who,t+"-"+who,p,t,"employee");
   auth.provision(t+"-maintainer",t+"-maintainer",p,t,"knowledge-maintainer");
   auth.provision(t+"-foreign",t+"-foreign",p,t+"-foreign","employee");
  }
 }
}
