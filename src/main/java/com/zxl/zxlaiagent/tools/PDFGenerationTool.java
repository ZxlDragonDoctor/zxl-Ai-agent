package com.zxl.zxlaiagent.tools;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.zxl.zxlaiagent.contstant.FileConstant;
import com.zxl.zxlaiagent.tools.model.ToolData.FileArtifact;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.ToolInputs;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
public class PDFGenerationTool {
    private final Path directory;
    public PDFGenerationTool() { this(Path.of(FileConstant.FILE_SAVE_DIR, "pdf")); }
    PDFGenerationTool(Path directory) { this.directory = directory; }
    @Tool(description = "Generate a PDF file with given content", returnDirect = true)
    public ToolResult<FileArtifact> generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        if (ToolInputs.blank(content)) return ToolResult.fail("MISSING_CONTENT", "请提供 PDF 内容");
        try {
            Path path = ToolInputs.outputPath(directory, fileName);
            Files.createDirectories(path.getParent());
            try (var writer = new PdfWriter(path.toString()); var pdf = new PdfDocument(writer);
                 var document = new Document(pdf)) {
                document.setFont(PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H"));
                document.add(new Paragraph(content));
            }
            return ToolResult.ok("PDF 生成成功", new FileArtifact(path.toString(), Files.size(path)));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("INVALID_FILE_NAME", e.getMessage());
        } catch (IOException | com.itextpdf.io.exceptions.IOException e) {
            return ToolResult.fail("PDF_GENERATION_FAILED", "PDF 生成失败，请检查字体和输出目录");
        }
    }
}
