package com.zxl.zxlaiagent.tools;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONUtil;
import com.zxl.zxlaiagent.tools.model.ToolData.SearchItem;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.ToolInputs;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.util.List;
import java.util.Map;
public class WebSearchTool {
    private final String apiKey;
    private final String endpoint;
    public WebSearchTool(String apiKey) { this(apiKey, "https://www.searchapi.io/api/v1/search"); }
    WebSearchTool(String apiKey, String endpoint) { this.apiKey = apiKey; this.endpoint = endpoint; }
    @Tool(description = "Search for information from Baidu Search Engine")
    public ToolResult<List<SearchItem>> searchWeb(@ToolParam(description = "Search query keyword") String query) {
        if (ToolInputs.blank(query)) return ToolResult.fail("MISSING_QUERY", "请提供搜索关键词");
        if (ToolInputs.blank(apiKey)) return ToolResult.fail("SEARCH_NOT_CONFIGURED", "搜索服务尚未配置");
        try (var response = HttpRequest.get(endpoint)
                .form(Map.of("q", query, "api_key", apiKey, "engine", "baidu")).timeout(20000).execute()) {
            if (!response.isOk()) return ToolResult.fail("SEARCH_SERVICE_ERROR", "搜索服务请求失败");
            var object = JSONUtil.parseObj(response.body());
            if (object.containsKey("error")) return ToolResult.fail("SEARCH_SERVICE_ERROR", "搜索服务返回错误");
            var results = object.getJSONArray("organic_results");
            List<SearchItem> items = results == null ? List.of() : results.stream().limit(5).map(item -> {
                var result = JSONUtil.parseObj(item);
                return new SearchItem(result.getStr("title"), result.getStr("link"), result.getStr("snippet"));
            }).toList();
            return ToolResult.ok(items.isEmpty() ? "未找到搜索结果" : "搜索完成", items);
        } catch (RuntimeException e) {
            return ToolResult.fail("SEARCH_SERVICE_ERROR", "搜索失败，请检查服务配置和网络");
        }
    }
}
