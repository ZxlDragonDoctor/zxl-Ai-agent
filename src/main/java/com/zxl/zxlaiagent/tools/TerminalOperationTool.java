package com.zxl.zxlaiagent.tools;
import com.zxl.zxlaiagent.tools.model.ToolData.CommandOutput;
import com.zxl.zxlaiagent.tools.model.ToolResult;
import com.zxl.zxlaiagent.tools.support.ToolInputs;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
public class TerminalOperationTool {
    private static final int MAX_OUTPUT = 64000;
    @Tool(description = "Execute a Windows terminal command, with a 30-second timeout")
    public ToolResult<CommandOutput> executeTerminalCommand(
            @ToolParam(description = "Command to execute in the terminal") String command) {
        if (ToolInputs.blank(command)) return ToolResult.fail("MISSING_COMMAND", "请提供命令");
        Process process = null;
        StringBuffer output = new StringBuffer();
        AtomicBoolean truncated = new AtomicBoolean();
        try {
            process = new ProcessBuilder("cmd.exe", "/d", "/c", command).redirectErrorStream(true).start();
            Process running = process;
            Thread reader = new Thread(() -> {
                try (var input = new java.io.InputStreamReader(running.getInputStream(), Charset.defaultCharset())) {
                    char[] buffer = new char[2048];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        int length = Math.min(count, MAX_OUTPUT - output.length());
                        if (length > 0) output.append(buffer, 0, length);
                        if (length < count) truncated.set(true);
                    }
                } catch (IOException ignored) { /* Process termination closes the stream. */ }
            }, "tool-command-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                stop(process);
                reader.join(2000);
                return ToolResult.fail("COMMAND_TIMEOUT", "命令执行超过 30 秒，已终止",
                        new CommandOutput(-1, output.toString(), truncated.get()));
            }
            reader.join(2000);
            var data = new CommandOutput(process.exitValue(), output.toString(), truncated.get());
            return data.exitCode() == 0 ? ToolResult.ok("命令执行成功", data)
                    : ToolResult.fail("COMMAND_FAILED", "命令返回非零退出码", data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) stop(process);
            return ToolResult.fail("COMMAND_INTERRUPTED", "命令执行被中断");
        } catch (IOException e) {
            return ToolResult.fail("COMMAND_START_FAILED", "无法启动命令，请检查终端环境");
        }
    }
    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
