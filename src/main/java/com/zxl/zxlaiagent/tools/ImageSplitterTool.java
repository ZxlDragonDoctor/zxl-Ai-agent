package com.zxl.zxlaiagent.tools;
import com.zxl.zxlaiagent.contstant.FileConstant;
import com.zxl.zxlaiagent.tools.model.ToolData.ImageArtifact;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.ToolInputs;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
public class ImageSplitterTool {
    private final Path directory;
    public ImageSplitterTool() { this(Path.of(FileConstant.FILE_SAVE_DIR, "imageSpilter")); }
    ImageSplitterTool(Path directory) { this.directory = directory; }
    @Tool(description = "Divide an image into three horizontal sections and arrange them side by side", returnDirect = true)
    public ToolResult<ImageArtifact> splitAndMergeImage(
            @ToolParam(description = "Input image path") String inputImagePath,
            @ToolParam(description = "Name of the PNG output file") String filename) {
        if (ToolInputs.blank(inputImagePath)) return ToolResult.fail("MISSING_IMAGE_PATH", "请提供输入图片路径");
        try {
            Path output = ToolInputs.outputPath(directory, filename);
            BufferedImage image = ImageIO.read(Path.of(inputImagePath).toFile());
            if (image == null) return ToolResult.fail("INVALID_IMAGE", "输入文件不是支持的图片格式");
            if (image.getHeight() < 3) return ToolResult.fail("IMAGE_TOO_SMALL", "图片高度至少为 3 像素");
            int segmentHeight = image.getHeight() / 3;
            int outputHeight = image.getHeight() - 2 * segmentHeight;
            BufferedImage merged = new BufferedImage(image.getWidth() * 3, outputHeight, BufferedImage.TYPE_INT_ARGB);
            var graphics = merged.createGraphics();
            try {
                for (int i = 0; i < 3; i++) {
                    int start = i * segmentHeight;
                    int height = i == 2 ? image.getHeight() - start : segmentHeight;
                    if (height > 0) graphics.drawImage(image.getSubimage(0, start, image.getWidth(), height),
                            i * image.getWidth(), 0, null);
                }
            } finally { graphics.dispose(); }
            Files.createDirectories(output.getParent());
            if (!ImageIO.write(merged, "png", output.toFile()))
                return ToolResult.fail("IMAGE_WRITE_FAILED", "没有可用的 PNG 编码器");
            return ToolResult.ok("图片处理成功", new ImageArtifact(output.toString(), merged.getWidth(), merged.getHeight()));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("INVALID_IMAGE_ARGUMENT", "请检查输入图片路径和输出文件名");
        } catch (IOException e) {
            return ToolResult.fail("IMAGE_PROCESSING_FAILED", "图片读取或写入失败");
        }
    }
}
