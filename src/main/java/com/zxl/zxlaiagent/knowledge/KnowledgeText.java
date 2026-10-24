package com.zxl.zxlaiagent.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Retain Markdown exactly. Every span addresses the immutable raw Java UTF-16 snapshot. */
public final class KnowledgeText {
    public record Chunk(String chunkId,int ordinal,String text,int startUtf16,int endUtf16,int startLine,int endLine,String titlePath) {}
    public record Prepared(String revisionId,String rawHash,String normalizedHash,String profileHash,String rawText,List<Chunk> chunks) {}
    public static Prepared prepare(String tenant,String id,String source,String raw,int max) {
        return prepare(tenant,id,source,raw,max,"deterministic-demo-v1",8);
    }
    public static Prepared prepare(String tenant,String id,String source,String raw,int max,String model,int dimensions) {
        if(tenant==null||!tenant.matches("[a-zA-Z0-9_-]{1,64}")||id==null||!id.matches("[a-zA-Z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid document identity");
        if(source==null||source.length()>120||source.contains("/")||source.contains("\\")||!(source.endsWith(".md")||source.endsWith(".txt")))throw new IllegalArgumentException("Only named Markdown/text content is accepted");
        if(raw==null||raw.isBlank()||raw.length()>100000||raw.getBytes(StandardCharsets.UTF_8).length>300000)throw new IllegalArgumentException("Content must be nonempty and within 100000 UTF-16 units / 300000 bytes");
        if(max<16||max>2000)throw new IllegalArgumentException("Invalid chunk limit");
        for(int i=0;i<raw.length();i++) {char c=raw.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=raw.length()||!Character.isLowSurrogate(raw.charAt(i)))throw new IllegalArgumentException("Invalid Unicode");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Invalid Unicode");}
        String profile=hash("raw-markdown-v1","utf16-paragraph-v1",String.valueOf(max),model,String.valueOf(dimensions),"cosine");
        String rawHash=hash(raw),normalHash=hash(raw.replace("\r\n","\n").replace('\r','\n'));
        // Raw hash participates because offsets belong to this exact snapshot (CRLF changes are a new revision).
        String revision=hash(tenant,id,rawHash,profile); List<Chunk> chunks=new ArrayList<>();
        for(var section:sections(raw)){
            int start=section.start();
            while(start<section.end()) {
                int end=Math.min(section.end(),start+max);
                if(end<raw.length()&&Character.isLowSurrogate(raw.charAt(end)))end--;
                if(end<section.end()){int newline=raw.lastIndexOf('\n',end-1);if(newline>start+max/3)end=newline+1;}
                String text=raw.substring(start,end);int ordinal=chunks.size();
                if(!text.isBlank())chunks.add(new Chunk(hash(tenant,id,revision,String.valueOf(ordinal),text),ordinal,text,start,end,line(raw,start),line(raw,Math.max(start,end-1)),section.path()));
                start=end;
            }
        }
        return new Prepared(revision,rawHash,normalHash,profile,raw,List.copyOf(chunks));
    }
    private record Section(int start,int end,String path){}
    private static List<Section> sections(String raw){
        var sections=new ArrayList<Section>();String[] headings=new String[6];String path="";int sectionStart=0;
        char fence=0;int fenceLength=0;int position=0;
        var headingPattern=java.util.regex.Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$");
        var fencePattern=java.util.regex.Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
        while(position<raw.length()){
            int end=position;while(end<raw.length()&&raw.charAt(end)!='\r'&&raw.charAt(end)!='\n')end++;
            String line=raw.substring(position,end);var fenceMatch=fencePattern.matcher(line);
            if(fenceMatch.matches()){
                char marker=fenceMatch.group(1).charAt(0);int length=fenceMatch.group(1).length();
                if(fence==0){fence=marker;fenceLength=length;}
                else if(marker==fence&&length>=fenceLength&&fenceMatch.group(2).isBlank()){fence=0;fenceLength=0;}
            }else if(fence==0){var heading=headingPattern.matcher(line);if(heading.matches()){
                if(position>sectionStart)sections.add(new Section(sectionStart,position,path));
                int level=heading.group(1).length()-1;headings[level]=heading.group(2);for(int i=level+1;i<6;i++)headings[i]=null;
                path=String.join(" / ",Arrays.stream(headings).filter(Objects::nonNull).toList());sectionStart=position;
            }}
            if(end<raw.length()&&raw.charAt(end)=='\r'&&end+1<raw.length()&&raw.charAt(end+1)=='\n')end++;
            position=Math.min(raw.length(),end+1);
        }
        if(sectionStart<raw.length())sections.add(new Section(sectionStart,raw.length(),path));return sections;
    }
    private static int line(String raw,int offset){int n=1;for(int i=0;i<offset;i++){char ch=raw.charAt(i);if(ch=='\n'||(ch=='\r'&&(i+1>=raw.length()||raw.charAt(i+1)!='\n')))n++;}return n;}
    public static String hash(String... parts){try{var md=MessageDigest.getInstance("SHA-256");for(String part:parts){byte[] bytes=part.getBytes(StandardCharsets.UTF_8);md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());md.update(bytes);}return HexFormat.of().formatHex(md.digest());}catch(Exception e){throw new IllegalStateException(e);}}
}
