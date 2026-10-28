package com.zxl.zxlaiagent.knowledge;

import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;
import com.zxl.zxlaiagent.knowledge.scoped.AuthorizedHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** OpenAI-compatible embedding wire protocol, including DashScope's compatible endpoint.
 * No automatic retry or demo fallback: failures leave the old published revision intact. */
public final class HttpKnowledgeEmbedding implements KnowledgeEmbedding {
    private final URI endpoint; private final String key,model,identity; private final int dimensions;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json=new ObjectMapper();
    public HttpKnowledgeEmbedding(URI endpoint,String key,String model,String revision,int dimensions){
        if(key==null||key.isBlank())throw new IllegalArgumentException("Real embedding mode requires LESSON12_EMBEDDING_API_KEY; demo fallback is forbidden");
        if(endpoint==null||endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||!("https".equals(endpoint.getScheme())||("http".equals(endpoint.getScheme())&&"127.0.0.1".equals(endpoint.getHost()))))throw new IllegalArgumentException("Embedding endpoint must be HTTPS (HTTP allowed only for loopback contract tests)");
        if(model==null||!model.matches("[a-zA-Z0-9._-]{1,45}")||revision==null||!revision.matches("[a-zA-Z0-9._-]{1,32}")||dimensions<1||dimensions>4096)throw new IllegalArgumentException("Explicit model, deployment revision and dimensions are required");
        this.endpoint=endpoint;this.key=key;this.model=model;this.dimensions=dimensions;
        this.identity="http:"+model+":"+revision+":"+dimensions+":"+KnowledgeText.hash(endpoint.toString()).substring(0,12);
    }
    @Override public String identity(){return identity;}
    @Override public int dimensions(){return dimensions;}
    @Override public float[] embed(String text){
        return embed(text,Duration.ofSeconds(30));
    }
    @Override public float[] embed(String text,Duration remaining){return embed(text,remaining,OperationBoundary.unrestricted());}
    @Override public float[] embed(String text,Duration remaining,OperationBoundary boundary){
        if(remaining.isNegative()||remaining.isZero()||Thread.currentThread().isInterrupted())throw new IllegalStateException("Embedding cancelled or deadline exceeded");
        try{
            String body=json.writeValueAsString(Map.of("model",model,"input",List.of(text),"dimensions",dimensions,"encoding_format","float"));
            var request=HttpRequest.newBuilder(endpoint).timeout(remaining.compareTo(Duration.ofSeconds(30))<0?remaining:Duration.ofSeconds(30)).header("Authorization","Bearer "+key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=AuthorizedHttp.send(client,request,HttpResponse.BodyHandlers.ofString(),remaining,boundary);
            if(response.statusCode()!=200)throw new IllegalStateException("Embedding provider returned HTTP "+response.statusCode()+"; no fallback was used");
            if(response.body().length()>1024*1024)throw new IllegalStateException("Embedding response exceeds 1 MiB");
            var values=json.readTree(response.body()).path("data").path(0).path("embedding");
            if(!values.isArray()||values.size()!=dimensions)throw new IllegalStateException("Embedding provider dimensions do not match the configured generation");
            float[] vector=new float[dimensions];for(int i=0;i<dimensions;i++){if(!values.get(i).isNumber())throw new IllegalStateException("Embedding response contains non numeric coordinates");vector[i]=(float)values.get(i).asDouble();}
            KnowledgeEmbedding.vectorLiteral(vector,dimensions);return vector;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Embedding interrupted; no fallback was used",e);}catch(java.io.IOException e){throw new IllegalStateException("Embedding HTTP request failed; no fallback was used",e);}
    }
}
