package com.zxl.zxlaiagent.knowledge;

import com.zxl.zxlaiagent.security.Phase;
import com.zxl.zxlaiagent.security.execution.OperationBoundary;

/** Model calls happen before SQL publication. This port never owns a database transaction. */
public interface KnowledgeEmbedding {
    float[] embed(String text);
    default float[] embed(String text, java.time.Duration remaining) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Embedding cancelled");
        if (remaining.isNegative() || remaining.isZero()) throw new IllegalStateException("Embedding deadline exceeded");
        return embed(text);
    }
    default float[] embed(String text,java.time.Duration remaining,OperationBoundary boundary){boundary.check(Phase.MODEL_DISPATCH);return embed(text,remaining);}
    default String identity() { return "deterministic-demo-v1"; }
    default int dimensions() { return 8; }
    static String vectorLiteral(float[] vector) {
        return vectorLiteral(vector,8);
    }
    static String vectorLiteral(float[] vector,int dimensions) {
        if(dimensions<1||dimensions>4096||vector==null || vector.length!=dimensions) throw new IllegalArgumentException("Unexpected embedding dimensions");
        StringBuilder b=new StringBuilder("["); double norm=0;
        for(int i=0;i<vector.length;i++) { if(!Float.isFinite(vector[i])) throw new IllegalArgumentException("Non-finite embedding"); if(i>0)b.append(','); b.append(vector[i]); norm+=vector[i]*vector[i]; }
        if(norm==0) throw new IllegalArgumentException("Zero embedding cannot use cosine distance");
        return b.append(']').toString();
    }
}
