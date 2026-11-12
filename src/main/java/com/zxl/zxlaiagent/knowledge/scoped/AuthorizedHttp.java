package com.zxl.zxlaiagent.knowledge.scoped;
import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.time.Duration;
import java.io.IOException;
import java.util.concurrent.*;
/** The gate encloses the actual HTTP transport start only. Waiting and body reads never hold epoch locks. */
public final class AuthorizedHttp {
 private AuthorizedHttp(){}
 public static HttpResponse.BodyHandler<byte[]> boundedBody(int limit) {
  if (limit < 1) throw new IllegalArgumentException("Positive HTTP body limit required");
  return ignored -> new HttpResponse.BodySubscriber<>() {
   private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
   private Flow.Subscription subscription;
   private int received;

   @Override public CompletionStage<byte[]> getBody() { return body.getBody(); }
   @Override public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    body.onSubscribe(subscription);
   }
   @Override public void onNext(List<ByteBuffer> buffers) {
    for (ByteBuffer buffer : buffers) {
     if (buffer.remaining() > limit - received) {
      body.onError(new IOException("HTTP response exceeds byte limit"));
      subscription.cancel();
      return;
     }
     received += buffer.remaining();
    }
    body.onNext(buffers);
   }
   @Override public void onError(Throwable failure) { body.onError(failure); }
   @Override public void onComplete() { body.onComplete(); }
  };
 }
 public static <T> HttpResponse<T> send(HttpClient client,HttpRequest request,HttpResponse.BodyHandler<T> handler,Duration remaining,OperationBoundary boundary)throws InterruptedException,IOException {
  CompletableFuture<HttpResponse<T>> pending=boundary.start(Phase.MODEL_DISPATCH,()->client.sendAsync(request,handler));
  try{return pending.get(remaining.toNanos(),TimeUnit.NANOSECONDS);}catch(ExecutionException|TimeoutException failure){throw new IOException("Authorized transport failed",failure);}finally{if(!pending.isDone())pending.cancel(true);}
 }
}
