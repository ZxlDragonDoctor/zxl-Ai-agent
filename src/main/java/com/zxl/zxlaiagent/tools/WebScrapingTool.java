package com.zxl.zxlaiagent.tools;
import com.zxl.zxlaiagent.tools.model.ToolData.WebPage;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.ToolInputs;
import org.jsoup.Jsoup;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.io.IOException;
public class WebScrapingTool {
    @Tool(description = "Scrape the content of a web page")
    public ToolResult<WebPage> scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        if (!ToolInputs.httpUrl(url)) return ToolResult.fail("INVALID_URL", "请提供有效的 HTTP 或 HTTPS 地址");
        try {
            var document = Jsoup.connect(url).timeout(20000).get();
            return ToolResult.ok("网页读取成功", new WebPage(url, document.title(), document.html()));
        } catch (IOException e) {
            return ToolResult.fail("WEB_SCRAPE_FAILED", "网页读取失败，请检查地址和网络");
        }
    }
}
