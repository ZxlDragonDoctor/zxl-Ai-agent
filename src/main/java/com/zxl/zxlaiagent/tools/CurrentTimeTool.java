package com.zxl.zxlaiagent.tools;
import com.zxl.zxlaiagent.tools.model.CurrentTimeData;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.ToolInputs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
@Slf4j
public class CurrentTimeTool {
    @Tool(description = "查询指定时区的服务器当前时间，需要当前日期或时间时使用")
    public ToolResult<CurrentTimeData> getCurrentTime(
            @ToolParam(description = "Java 时区 ID，例如 Asia/Shanghai 或 UTC") String zoneId) {
        if (ToolInputs.blank(zoneId)) return ToolResult.fail("MISSING_ZONE", "请提供时区，例如 Asia/Shanghai");
        try {
            ZoneId zone = ZoneId.of(zoneId);
            String time = ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            log.info("TIME_TOOL_EXECUTED zoneId={} time={}", zone.getId(), time);
            return ToolResult.ok("时间查询成功", new CurrentTimeData(zone.getId(), time));
        } catch (DateTimeException e) {
            return ToolResult.fail("INVALID_ZONE", "请使用有效时区，例如 Asia/Shanghai 或 UTC");
        }
    }
}
