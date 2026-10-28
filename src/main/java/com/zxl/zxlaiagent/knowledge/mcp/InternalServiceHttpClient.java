package com.zxl.zxlaiagent.knowledge.mcp;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** SDK 0.7.0 has no request-header hook. Delegate its actual HttpClient to add service auth on every exchange. */
final class InternalServiceHttpClient extends HttpClient {
 private final HttpClient delegate;private final String bearer;private final URI origin;
 private InternalServiceHttpClient(HttpClient delegate,String token,URI origin){this.delegate=delegate;this.bearer="Bearer "+token;this.origin=origin;}
 static void requireToken(String token){if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("LESSON17_INTERNAL_SERVICE_TOKEN must be a 32..256 character base64url secret");}
 static boolean authorized(String value,String token){return value!=null&&value.length()<=263&&MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),value.getBytes(StandardCharsets.UTF_8));}
 private HttpRequest request(HttpRequest request){
  URI uri=request.uri();if(!Objects.equals(uri.getScheme(),origin.getScheme())||!Objects.equals(uri.getHost(),origin.getHost())||uri.getPort()!=origin.getPort())throw new IllegalStateException("MCP_PROTOCOL_FAILURE");
  return HttpRequest.newBuilder(request,(name,value)->!name.equalsIgnoreCase("Authorization")).header("Authorization",bearer).build();
 }
 static HttpClient.Builder builder(String token,URI endpoint){
  requireToken(token);return new AuthBuilder(HttpClient.newBuilder().followRedirects(Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(2)).proxy(new ProxySelector(){
      public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
      public void connectFailed(URI uri,SocketAddress address,IOException failure){}
    }),token,endpoint);
 }
 private record AuthBuilder(HttpClient.Builder delegate,String token,URI origin) implements HttpClient.Builder {
  public HttpClient.Builder cookieHandler(CookieHandler v){delegate.cookieHandler(v);return this;}
  public HttpClient.Builder connectTimeout(Duration v){delegate.connectTimeout(v);return this;}
  public HttpClient.Builder sslContext(SSLContext v){delegate.sslContext(v);return this;}
  public HttpClient.Builder sslParameters(SSLParameters v){delegate.sslParameters(v);return this;}
  public HttpClient.Builder executor(Executor v){delegate.executor(v);return this;}
  public HttpClient.Builder followRedirects(Redirect v){if(v!=Redirect.NEVER)throw new IllegalArgumentException("Redirects forbidden");return this;}
  public HttpClient.Builder version(Version v){delegate.version(v);return this;}
  public HttpClient.Builder priority(int v){delegate.priority(v);return this;}
  public HttpClient.Builder proxy(ProxySelector v){delegate.proxy(v);return this;}
  public HttpClient.Builder authenticator(Authenticator v){delegate.authenticator(v);return this;}
  public HttpClient build(){return new InternalServiceHttpClient(delegate.build(),token,origin);}
  @Override public String toString(){return "AuthenticatedMcpHttpClientBuilder";}
 }
 @Override public <T>HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> h)throws IOException,InterruptedException{return delegate.send(request(r),h);}
 @Override public <T>CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){return delegate.sendAsync(request(r),h);}
 @Override public <T>CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){return delegate.sendAsync(request(r),h,p);}
 @Override public Optional<CookieHandler> cookieHandler(){return delegate.cookieHandler();}
 @Override public Optional<Duration> connectTimeout(){return delegate.connectTimeout();}
 @Override public Redirect followRedirects(){return Redirect.NEVER;}
 @Override public Optional<ProxySelector> proxy(){return delegate.proxy();}
 @Override public SSLContext sslContext(){return delegate.sslContext();}
 @Override public SSLParameters sslParameters(){return delegate.sslParameters();}
 @Override public Optional<Authenticator> authenticator(){return delegate.authenticator();}
 @Override public Version version(){return delegate.version();}
 @Override public Optional<Executor> executor(){return delegate.executor();}
 @Override public String toString(){return "AuthenticatedMcpHttpClient";}
}
