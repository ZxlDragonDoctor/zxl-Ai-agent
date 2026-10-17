package com.zxl.zxlaiagent.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionLocalTest {
    @TempDir Path directory;
    private HttpServer server;
    private String base;
    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            byte[] body = "{\"organic_results\":[{\"title\":\"one\",\"link\":\"https://example.com\",\"snippet\":\"local fixture\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/page", exchange -> {
            byte[] body = "<html><head><title>Lesson 3</title></head><body>local page</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/resource", exchange -> {
            byte[] body = "本地资源".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/failure", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stopServer() { server.stop(0); }

    @Test void searchAcceptsFewerThanFiveResults() {
        var result = new WebSearchTool("fixture-key", base + "/search").searchWeb("local");
        assertTrue(result.success(), result.message());
        assertEquals(1, result.data().size());
        assertEquals("one", result.data().get(0).title());
    }
    @Test void searchFailureDoesNotExposeCredentials() {
        var result = new WebSearchTool("fixture-secret", base + "/failure").searchWeb("local");
        assertFalse(result.success());
        assertFalse(result.message().contains("fixture-secret"));
    }
    @Test void webpagePayloadContainsTheActualPage() {
        var result = new WebScrapingTool().scrapeWebPage(base + "/page");
        assertTrue(result.success(), result.message());
        assertEquals("Lesson 3", result.data().title());
        assertTrue(result.data().html().contains("local page"));
    }
    @Test void downloadPayloadPointsToTheActualBytes() throws Exception {
        var result = new ResourceDownloadTool(directory).downloadResource(base + "/resource", "download.txt");
        assertTrue(result.success(), result.message());
        assertEquals("本地资源", Files.readString(Path.of(result.data().path())));
        assertEquals(Files.size(Path.of(result.data().path())), result.data().sizeBytes());
    }
    @Test void pdfResultPointsToAnActualPdf() throws Exception {
        var result = new PDFGenerationTool(directory).generatePDF("lesson.pdf", "第三课：统一工具结果");
        assertTrue(result.success(), result.message());
        assertTrue(Files.readString(Path.of(result.data().path()), StandardCharsets.ISO_8859_1).startsWith("%PDF-"));
    }
    @Test void imageResultPreservesAllRowsAcrossThreeColumns() throws Exception {
        var input = new BufferedImage(1, 4, BufferedImage.TYPE_INT_RGB);
        int[] colors = {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffff00};
        for (int row = 0; row < colors.length; row++) input.setRGB(0, row, colors[row]);
        Path source = directory.resolve("source.png");
        ImageIO.write(input, "png", source.toFile());
        var result = new ImageSplitterTool(directory).splitAndMergeImage(source.toString(), "output.png");
        assertTrue(result.success(), result.message());
        var output = ImageIO.read(Path.of(result.data().path()).toFile());
        assertEquals(3, output.getWidth());
        assertEquals(2, output.getHeight());
        assertEquals(colors[0], output.getRGB(0, 0));
        assertEquals(colors[1], output.getRGB(1, 0));
        assertEquals(colors[2], output.getRGB(2, 0));
        assertEquals(colors[3], output.getRGB(2, 1));
    }
    @Test void commandResultReportsExitCodeAndOutput() {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var tool = new TerminalOperationTool();
        var success = tool.executeTerminalCommand("echo lesson3");
        assertTrue(success.success(), success.message());
        assertTrue(success.data().output().contains("lesson3"));
        var failure = tool.executeTerminalCommand("exit /b 7");
        assertFalse(failure.success());
        assertEquals(7, failure.data().exitCode());
    }
}
