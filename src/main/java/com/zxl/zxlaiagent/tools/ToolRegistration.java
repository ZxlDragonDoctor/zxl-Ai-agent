package com.zxl.zxlaiagent.tools;

import com.zxl.zxlaiagent.tools.*;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 注册工具
 */
@Configuration
public class ToolRegistration {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Resource
    private List<McpSyncClient> mcpSyncClients;


    @Bean
    public ToolCallback[] allTools() {
        // 创建本地工具实例
        FileOperationTool fileOperationTool = new FileOperationTool();
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        ImageSplitterTool imageSplitterTool = new ImageSplitterTool();
        TerminateTool terminateTool = new TerminateTool();

        // 获取MCP客户端提供的工具回调
        SyncMcpToolCallbackProvider syncMcpToolCallbackProvider = new SyncMcpToolCallbackProvider(mcpSyncClients);
        ToolCallback[] toolCallbacksFromMcp = syncMcpToolCallbackProvider.getToolCallbacks();

        // 合并所有工具回调
        return mergeToolCallbacks(
                ToolCallbacks.from(
                        fileOperationTool,
                        webSearchTool,
                        webScrapingTool,
                        resourceDownloadTool,
                        terminalOperationTool,
                        pdfGenerationTool,
                        imageSplitterTool,
                        terminateTool
                ),
                toolCallbacksFromMcp
        );
    }

    /**
     * 合并两个 ToolCallback 数组
     */
    private ToolCallback[] mergeToolCallbacks(ToolCallback[] array1, ToolCallback[] array2) {
        ToolCallback[] mergedArray = new ToolCallback[array1.length + array2.length];
        System.arraycopy(array1, 0, mergedArray, 0, array1.length);
        System.arraycopy(array2, 0, mergedArray, array1.length, array2.length);
        return mergedArray;
    }
}
