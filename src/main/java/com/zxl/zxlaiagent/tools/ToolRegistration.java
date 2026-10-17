package com.zxl.zxlaiagent.tools;

import com.zxl.zxlaiagent.tools.support.StructuredMcpToolCallback;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * 注册工具
 */
@Configuration
public class ToolRegistration {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Autowired(required = false)
    private List<McpSyncClient> mcpSyncClients = Collections.emptyList();


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
        CurrentTimeTool currentTimeTool = new CurrentTimeTool();

        // 获取MCP客户端提供的工具回调
        ToolCallback[] toolCallbacksFromMcp = mcpSyncClients.stream()
                .flatMap(client -> client.listTools().tools().stream()
                        .map(tool -> (ToolCallback) new StructuredMcpToolCallback(client, tool)))
                .toArray(ToolCallback[]::new);

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
                        terminateTool,
                        currentTimeTool
                ),
                toolCallbacksFromMcp
        );
    }

    /**
     * 合并两个 ToolCallback 数组
     */
    private ToolCallback[] mergeToolCallbacks(ToolCallback[] array1, ToolCallback[] array2) {
        Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
        for (ToolCallback[] group : List.of(array1, array2)) {
            for (ToolCallback callback : group) {
                String name = callback.getToolDefinition().name();
                if (callbacks.putIfAbsent(name, callback) != null) {
                    throw new IllegalStateException("工具名重复: " + name);
                }
            }
        }
        return callbacks.values().toArray(ToolCallback[]::new);
    }
}
