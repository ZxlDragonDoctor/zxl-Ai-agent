package com.zxl.zxlaiagent.knowledge.scoped;
import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import java.util.concurrent.*;
/** The gate encloses the actual HTTP transport start only. Waiting and body reads never hold epoch locks. */
public final class AuthorizedHttp {
 private AuthorizedHttp(){}
 public static <T> HttpResponse<T> send(HttpClient client,HttpRequest request,HttpResponse.BodyHandler<T> handler,Duration remaining,OperationBoundary boundary)throws InterruptedException,IOException {
  CompletableFuture<HttpResponse<T>> pending=boundary.start(Phase.MODEL_DISPATCH,()->client.sendAsync(request,handler));
  try{return pending.get(remaining.toNanos(),TimeUnit.NANOSECONDS);}catch(ExecutionException|TimeoutException failure){throw new IOException("Authorized transport failed",failure);}finally{if(!pending.isDone())pending.cancel(true);}
 }
}
