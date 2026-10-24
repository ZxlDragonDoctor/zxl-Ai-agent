package com.zxl.zxlaiagent.knowledge.evaluation;

import java.util.*;

/** Macro metrics: no-answer queries have undefined recall/nDCG and are reported separately. */
public final class EvaluationMetrics {
    private EvaluationMetrics() {}
    public record Score(Double recall,double mrr,Double ndcg,boolean noAnswer,boolean empty,int forbiddenCount) {}
    public static Score score(Map<String,Integer> labels,Set<String> forbidden,List<String> ranking,int k) {
        if(k<1||k>5||labels.values().stream().anyMatch(g->g<0||g>2))throw new IllegalArgumentException("Invalid evaluation grade/K");
        var top=ranking.stream().distinct().limit(k).toList();
        long total=labels.values().stream().filter(g->g>0).count();int found=0;double reciprocal=0,dcg=0;
        for(int i=0;i<top.size();i++){int grade=labels.getOrDefault(top.get(i),0);if(grade>0){found++;if(reciprocal==0)reciprocal=1.0/(i+1);}dcg+=(Math.pow(2,grade)-1)/log2(i+2);}
        var ideal=labels.values().stream().filter(g->g>0).sorted(Comparator.reverseOrder()).limit(k).toList();double idcg=0;
        for(int i=0;i<ideal.size();i++)idcg+=(Math.pow(2,ideal.get(i))-1)/log2(i+2);
        return new Score(total==0?null:(double)found/total,reciprocal,idcg==0?null:dcg/idcg,total==0,top.isEmpty(),(int)top.stream().filter(forbidden::contains).count());
    }
    private static double log2(double value){return Math.log(value)/Math.log(2);}
}
