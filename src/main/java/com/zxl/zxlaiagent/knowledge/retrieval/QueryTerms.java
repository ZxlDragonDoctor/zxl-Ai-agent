package com.zxl.zxlaiagent.knowledge.retrieval;

import java.util.*;
import java.util.regex.Pattern;

/** Teaching baseline: dictionary + CJK bigrams + alphanumeric error codes; not BM25. */
public final class QueryTerms {
    private static final List<String> DICTIONARY = List.of("账号", "锁定", "密码", "重置", "打印机", "连接", "网络", "防火墙", "认证", "管理员", "工单", "解锁");
    public static List<String> extract(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        var latin = Pattern.compile("[a-z0-9]+(?:[-_][a-z0-9]+)*").matcher(lower);
        while (latin.find()) terms.add(latin.group());
        for (String word : DICTIONARY) if (lower.contains(word)) terms.add(word);
        var chinese = Pattern.compile("[\\p{IsHan}]+").matcher(lower);
        while (chinese.find()) {
            int[] chars = chinese.group().codePoints().toArray();
            for (int i=0; i+1<chars.length; i++) terms.add(new String(chars, i, 2));
        }
        return terms.stream().limit(32).toList();
    }
    public static String supplement(String original) {
        List<String> additions = new ArrayList<>();
        if (original.contains("连不上")) additions.add("连接失败 网络");
        if (original.contains("登不进") || original.contains("进不去")) additions.add("账号 认证 登录");
        if (original.contains("忘了") || original.contains("忘记")) additions.add("密码 重置");
        return additions.isEmpty() ? null : original + " " + String.join(" ", additions);
    }
    private QueryTerms() { }
}
