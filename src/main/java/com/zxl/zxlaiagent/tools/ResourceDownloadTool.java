package com.zxl.zxlaiagent.tools;
import cn.hutool.http.HttpRequest;
import com.zxl.zxlaiagent.contstant.FileConstant;
import com.zxl.zxlaiagent.tools.model.ToolData.FileArtifact;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.ToolInputs;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
public class ResourceDownloadTool {
    private final Path directory;
    public ResourceDownloadTool() { this(Path.of(FileConstant.FILE_SAVE_DIR, "download")); }
    ResourceDownloadTool(Path directory) { this.directory = directory; }
    @Tool(description = "Download a resource from a given URL")
    public ToolResult<FileArtifact> downloadResource(
            @ToolParam(description = "URL of the resource to download") String url,
            @ToolParam(description = "Name of the file to save the downloaded resource") String fileName) {
        if (!ToolInputs.httpUrl(url)) return ToolResult.fail("INVALID_URL", "请提供有效的 HTTP 或 HTTPS 地址");
        try {
            Path path = ToolInputs.outputPath(directory, fileName);
            try (var response = HttpRequest.get(url).timeout(20000).execute()) {
                if (!response.isOk()) return ToolResult.fail("DOWNLOAD_HTTP_ERROR", "资源服务返回错误");
                Files.createDirectories(path.getParent());
                try (var input = response.bodyStream(); var output = Files.newOutputStream(path)) { input.transferTo(output); }
            }
            return ToolResult.ok("资源下载成功", new FileArtifact(path.toString(), Files.size(path)));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("INVALID_FILE_NAME", e.getMessage());
        } catch (IOException | cn.hutool.core.io.IORuntimeException | cn.hutool.http.HttpException e) {
            return ToolResult.fail("DOWNLOAD_FAILED", "下载失败，请检查网络和输出目录");
        }
    }
}
