package dev.keel.tools;

import dev.keel.KeelProperties;
import dev.keel.core.ToolSpec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class HttpFetchTool implements Tool {

    private static final int MAX_BYTES = 512 * 1024;

    private final List<String> allowedHosts;
    private final int maxChars;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public HttpFetchTool(KeelProperties properties) {
        this.allowedHosts = properties.tools().httpFetch().allowedHosts().stream().map(String::toLowerCase).toList();
        this.maxChars = properties.tools().httpFetch().maxChars();
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("http_fetch",
                "Fetch a public HTTPS page and return its text. Only these hosts are allowed: "
                        + String.join(", ", allowedHosts) + ".",
                Schemas.object(Map.of("url", Schemas.string("Full https:// URL"))));
    }

    @Override
    public Kind kind() {
        return Kind.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        URI uri;
        try {
            uri = new URI(invocation.requireText("url"));
        } catch (URISyntaxException e) {
            return ToolResult.error("Not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return ToolResult.error("Only plain https:// URLs on port 443 are allowed");
        }
        var host = uri.getHost().toLowerCase();
        if (allowedHosts.stream().noneMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed))) {
            return ToolResult.error("Host " + host + " is not on the allowlist");
        }
        var request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "keel-lite/0.1")
                .header("Accept", "text/html, text/plain, application/json")
                .GET()
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                var text = new String(body.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
                if (response.statusCode() >= 300) {
                    return ToolResult.error("HTTP " + response.statusCode());
                }
                return ToolResult.ok(truncate(toText(text)));
            }
        } catch (IOException e) {
            throw new OutcomeUnknownException("Fetch failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutcomeUnknownException("Interrupted", e);
        }
    }

    static String toText(String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String truncate(String text) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + " …";
    }
}
