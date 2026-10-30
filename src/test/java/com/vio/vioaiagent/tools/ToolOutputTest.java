package com.vio.vioaiagent.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Format contracts use real local HTTP responses, without public network availability. */
class ToolOutputTest {
    private static final class LocalPage implements AutoCloseable {
        private final HttpServer server;
        LocalPage(String body) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] html = ("<html><head><title>Fixture page</title></head><body>" + body + "</body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, html.length);
                try (var response = exchange.getResponseBody()) { response.write(html); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
        public void close() { server.stop(0); }
    }

    @Test void testScrapeReturnsTextNotHtml() throws Exception {
        try (var page = new LocalPage("<h1>Readable heading</h1><div>Paragraph &amp; text</div>")) {
            String result = new WebScrapingTool().scrapeWebPage(page.url());
            assertEquals("Title: Fixture page\n\nContent:\nReadable heading Paragraph & text", result);
            assertFalse(result.contains("<html>"));
            assertFalse(result.contains("<body>"));
            assertFalse(result.contains("<div"));
        }
    }

    @Test void testScrapeOutputIsTruncated() throws Exception {
        try (var page = new LocalPage("<div>" + "a".repeat(2100) + "TAIL_MARKER</div>")) {
            String result = new WebScrapingTool().scrapeWebPage(page.url());
            assertEquals("Title: Fixture page\n\nContent:\n" + "a".repeat(2000)
                    + "...(truncated, total 2111 chars)", result);
            assertFalse(result.contains("TAIL_MARKER"));
        }
    }

    @Test void testScrapeInvalidUrlReturnsError() {
        // Syntactically invalid input fails locally; no DNS/network dependency.
        String result = new WebScrapingTool().scrapeWebPage("not-a-valid-url");
        assertTrue(result.startsWith("Error scraping web page:"));
    }

    @Test void testSearchReturnsFormattedText() {
        WebSearchTool tool = new WebSearchTool("fixture-key", (query, key) -> new WebSearchTool.SearchResponse(
                200, "{\"organic_results\":[{\"title\":\"fixture\",\"link\":\"https://example.invalid\",\"snippet\":\"readable text\"}]}"));
        String result = tool.searchWeb("test query");
        assertTrue(result.startsWith("Search results for"));
        assertTrue(result.contains("readable text"));
        assertFalse(result.trim().startsWith("{"));
        assertFalse(result.trim().startsWith("["));
        assertTrue(result.length() < 5000);
    }

    @Test void exactBodyLimitDoesNotTruncateOrLoseContent() throws Exception {
        try (var page = new LocalPage("<p>" + "b".repeat(2000) + "</p>")) {
            String result = new WebScrapingTool().scrapeWebPage(page.url());
            assertEquals("Title: Fixture page\n\nContent:\n" + "b".repeat(2000), result);
            assertFalse(result.contains("truncated"));
        }
    }
}
