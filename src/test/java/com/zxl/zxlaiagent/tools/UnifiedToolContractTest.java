package com.zxl.zxlaiagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedToolContractTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyLocalToolUsesTheSameReturnContract() {
        List.of(CurrentTimeTool.class, FileOperationTool.class, WebSearchTool.class,
                WebScrapingTool.class, ResourceDownloadTool.class, TerminalOperationTool.class,
                PDFGenerationTool.class, ImageSplitterTool.class, TerminateTool.class).forEach(type -> {
            for (var method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    assertEquals(ToolResult.class, method.getReturnType(), method.toString());
                }
            }
        });
    }

    @Test
    void timeToolReturnsStructuredSuccessAndParameterErrors() {
        var tool = new CurrentTimeTool();
        var result = tool.getCurrentTime("UTC");
        assertTrue(result.success());
        assertEquals("OK", result.code());
        assertEquals("UTC", result.data().zoneId());
        assertNotNull(result.data().currentTime());
        assertEquals("MISSING_ZONE", tool.getCurrentTime(null).code());
        assertEquals("INVALID_ZONE", tool.getCurrentTime("Mars/Olympus").code());
        assertFalse(tool.getCurrentTime("Mars/Olympus").success());
    }

    @Test
    void callbackSerializesAnObjectRatherThanAnEncodedJsonString() throws Exception {
        var callback = ToolCallbacks.from(new CurrentTimeTool())[0];
        var node = mapper.readTree(callback.call("{\"zoneId\":\"UTC\"}"));
        assertTrue(node.isObject());
        assertTrue(node.path("success").asBoolean());
        assertEquals("UTC", node.path("data").path("zoneId").asText());
    }

    @Test
    void fileResultsIncludeActualContentAndFileErrors() {
        var tool = new FileOperationTool(directory);
        assertTrue(tool.writeFile("note.txt", "第三课").success());
        assertEquals("第三课", tool.readFile("note.txt").data().content());
        assertFalse(tool.readFile("missing.txt").success());
        assertEquals("INVALID_FILE_NAME", tool.writeFile("../outside.txt", "x").code());
    }

    @Test
    void allToolsRejectMissingParametersBeforeExternalWork() {
        assertFalse(new FileOperationTool(directory).readFile(null).success());
        assertFalse(new WebSearchTool("unused").searchWeb("").success());
        assertFalse(new WebScrapingTool().scrapeWebPage("").success());
        assertFalse(new ResourceDownloadTool().downloadResource("", "file.txt").success());
        assertFalse(new TerminalOperationTool().executeTerminalCommand("").success());
        assertFalse(new PDFGenerationTool().generatePDF("", "x").success());
        assertFalse(new ImageSplitterTool().splitAndMergeImage("", "image.png").success());
        assertTrue(new TerminateTool().doTerminate().success());
    }
}
