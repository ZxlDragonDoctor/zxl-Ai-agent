package com.zxl.zxlaiagent.tools;
import com.zxl.zxlaiagent.tools.model.ToolData.Termination;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import org.springframework.ai.tool.annotation.Tool;
public class TerminateTool {
    @Tool(description = "Terminate only when the user explicitly requests tool-based termination. Call this tool alone, after observing other tool results. Ordinary requests finish by giving a final answer.")
    public ToolResult<Termination> doTerminate() {
        return ToolResult.ok("已按请求终止执行", new Termination(true));
    }
}
