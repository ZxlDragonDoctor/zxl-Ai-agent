package com.zxl.zxlaiagent.knowledge.scoped;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/** Separate service authentication plus one-use method/path/query/body-bound delegation. */
public final class WorkerAuthenticationFilter implements Filter {
 public static final String ACTOR_ATTRIBUTE=WorkerAuthenticationFilter.class.getName()+".actor";
 private final ServiceCallAuthorizer tickets;private final AuthorizationService authorization;private final byte[] bearer;
 public WorkerAuthenticationFilter(ServiceCallAuthorizer tickets,AuthorizationService authorization,String token){if(token==null||!token.matches("[A-Za-z0-9_-]{43,256}")||token.chars().distinct().count()<12)throw new IllegalArgumentException("Explicit high-entropy LESSON17_INTERNAL_SERVICE_TOKEN required");this.tickets=tickets;this.authorization=authorization;this.bearer=("Bearer "+token).getBytes(StandardCharsets.UTF_8);}
 public static ActorRef actor(HttpServletRequest request){Object ref=request.getAttribute(ACTOR_ATTRIBUTE);if(!(ref instanceof ActorRef actor))throw new SecurityFailure("INVALID_SERVICE_TICKET",401);return actor;}
 @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain)throws IOException,ServletException{
  var request=(HttpServletRequest)input;var response=(HttpServletResponse)output;
  try{
   var headers=Collections.list(request.getHeaders("Authorization"));if(headers.size()!=1||!MessageDigest.isEqual(bearer,headers.get(0).getBytes(StandardCharsets.UTF_8)))throw new SecurityFailure("SERVICE_UNAUTHENTICATED",401);
   if(request.getHeader("Cookie")!=null)throw new SecurityFailure("SERVICE_UNAUTHENTICATED",401);
   byte[] body=request.getInputStream().readNBytes(2*1024*1024+1);if(body.length>2*1024*1024)throw new IllegalArgumentException("Body limit exceeded");
   String path=request.getRequestURI();String action=WorkerRequestContract.action(request.getMethod(),path);
   var ticketHeaders=Collections.list(request.getHeaders(WorkerRequestContract.TICKET_HEADER));if(ticketHeaders.size()!=1)throw new SecurityFailure("INVALID_SERVICE_TICKET",401);
   ActorRef actor=tickets.consume(ticketHeaders.get(0),WorkerRequestContract.AUDIENCE,action,WorkerRequestContract.hash(request.getMethod(),path,request.getQueryString(),body));
   var wrapped=new HttpServletRequestWrapper(request){@Override public ServletInputStream getInputStream(){var in=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}};}@Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}};
   wrapped.setAttribute(ACTOR_ATTRIBUTE,actor);
   var bytes=new ByteArrayOutputStream();var overflow=new boolean[1];
   var buffered=new HttpServletResponseWrapper(response){
    private final ServletOutputStream stream=new ServletOutputStream(){
     private void reserve(int count)throws IOException{if(count>1024*1024-bytes.size()){overflow[0]=true;throw new IOException("Worker response limit exceeded");}}
     public void write(int b)throws IOException{reserve(1);bytes.write(b);}
     public void write(byte[] data,int offset,int length)throws IOException{reserve(length);bytes.write(data,offset,length);}
     public boolean isReady(){return true;}public void setWriteListener(WriteListener l){throw new UnsupportedOperationException();}
    };
    private PrintWriter writer;
    @Override public ServletOutputStream getOutputStream(){return stream;}
    @Override public PrintWriter getWriter(){if(writer==null)writer=new PrintWriter(new OutputStreamWriter(stream,StandardCharsets.UTF_8));return writer;}
    @Override public void flushBuffer(){if(writer!=null)writer.flush();}
   };
   try {
   chain.doFilter(wrapped,buffered);
   buffered.flushBuffer();
   }catch(IOException failure){if(!overflow[0])throw failure;}
   if(overflow[0]){error(response,502,"WORKER_RESPONSE_TOO_LARGE");return;}
   // Admit the complete, bounded result under authority; never hold a PG lock during socket I/O.
   // Revocation after this admission cannot recall bytes whose network send has already begun.
   byte[] admitted=authorization.gate(actor,action,null,Phase.RESULT_PUBLISH,null,bytes::toByteArray);
   response.getOutputStream().write(admitted);response.getOutputStream().flush();
  }catch(SecurityFailure denied){error(response,denied.status(),denied.code());}catch(IllegalArgumentException invalid){error(response,400,"INVALID_WORKER_REQUEST");}
 }
 private void error(HttpServletResponse response,int status,String code)throws IOException{response.resetBuffer();response.setStatus(status);response.setContentType("application/json");new ObjectMapper().writeValue(response.getOutputStream(),Map.of("code",code,"message",code));}
}
