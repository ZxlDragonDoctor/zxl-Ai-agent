package com.zxl.zxlaiagent.tools.support;
import java.net.URI;
import java.nio.file.Path;
public final class ToolInputs {
    private ToolInputs() {}
    public static boolean blank(String value) { return value == null || value.isBlank(); }
    public static boolean httpUrl(String value) {
        if (blank(value)) return false;
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) { return false; }
    }
    public static Path outputPath(Path directory, String fileName) {
        if (blank(fileName)) throw new IllegalArgumentException("请提供文件名");
        Path base = directory.toAbsolutePath().normalize();
        Path relative = Path.of(fileName);
        Path result = base.resolve(relative).normalize();
        if (relative.isAbsolute() || !result.startsWith(base) || result.equals(base))
            throw new IllegalArgumentException("文件名必须位于工具输出目录中");
        return result;
    }
}
