package com.zxl.zxlaiagent.tools.model;
public final class ToolData {
    private ToolData() {}
    public record FileContent(String path, String content) {}
    public record FileArtifact(String path, long sizeBytes) {}
    public record SearchItem(String title, String url, String snippet) {}
    public record WebPage(String url, String title, String html) {}
    public record CommandOutput(int exitCode, String output, boolean truncated) {}
    public record ImageArtifact(String path, int width, int height) {}
    public record Termination(boolean terminated) {}
}
