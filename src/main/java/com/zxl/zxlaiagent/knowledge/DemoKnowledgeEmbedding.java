package com.zxl.zxlaiagent.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Offline lifecycle teaching fixture. Hash coordinates are NOT semantic embeddings. */
public final class DemoKnowledgeEmbedding implements KnowledgeEmbedding {
    @Override public float[] embed(String text) {
        try { byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            float[] result=new float[8]; for(int i=0;i<8;i++)result[i]=(Byte.toUnsignedInt(hash[i])+1)/256f; return result;
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
