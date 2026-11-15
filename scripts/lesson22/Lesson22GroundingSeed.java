import com.zxl.zxlaiagent.security.*;
class Lesson22GroundingSeed {
  public static void main(String[] args) {
    try(var db = SecurityDatabase.fromEnvironment()) {
      var auth = new AuthenticationService(db);
      String tenant=args[0];
      auth.provision(tenant+"-employee",tenant+"-employee",System.getenv("LESSON22_PASSWORD"),tenant,"employee");
      auth.provision(tenant+"-maintainer",tenant+"-maintainer",System.getenv("LESSON22_MAINTAINER_PASSWORD"),tenant,"knowledge-maintainer");
    }
  }
}
