package com.zxl.zxlaiagent.tools;
import com.zxl.zxlaiagent.contstant.FileConstant;
import com.zxl.zxlaiagent.tools.model.ToolData.FileArtifact;
import com.zxl.zxlaiagent.tools.model.ToolData.FileContent;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.ToolInputs;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
public class FileOperationTool {
    private final Path directory;
    public FileOperationTool() { this(Path.of(FileConstant.FILE_SAVE_DIR, "file")); }
    FileOperationTool(Path directory) { this.directory = directory; }
    @Tool(description = "Read content from a file in the tool output directory")
    public ToolResult<FileContent> readFile(@ToolParam(description = "Name of the file to read") String fileName) {
        try {
            Path path = ToolInputs.outputPath(directory, fileName);
            if (!Files.isRegularFile(path)) return ToolResult.fail("FILE_NOT_FOUND", "文件不存在");
            return ToolResult.ok("文件读取成功", new FileContent(path.toString(), Files.readString(path)));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("INVALID_FILE_NAME", e.getMessage());
        } catch (IOException e) {
            return ToolResult.fail("FILE_READ_FAILED", "文件读取失败，请检查文件和访问权限");
        }
    }
    @Tool(description = "Write content to a file in the tool output directory")
    public ToolResult<FileArtifact> writeFile(
            @ToolParam(description = "Name of the file to write") String fileName,
            @ToolParam(description = "Content to write to the file") String content) {
        if (content == null) return ToolResult.fail("MISSING_CONTENT", "请提供文件内容");
        try {
            Path path = ToolInputs.outputPath(directory, fileName);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            return ToolResult.ok("文件写入成功", new FileArtifact(path.toString(), Files.size(path)));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("INVALID_FILE_NAME", e.getMessage());
        } catch (IOException e) {
            return ToolResult.fail("FILE_WRITE_FAILED", "文件写入失败，请检查目录和访问权限");
        }
    }
}
