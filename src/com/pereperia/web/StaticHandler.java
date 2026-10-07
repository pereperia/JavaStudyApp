package com.pereperia.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * web/ 配下の静的ファイルを配信するハンドラ。
 *
 * "/" に登録して使う。/api/... のほうがパスとして長いので、
 * com.sun.net.httpserver の最長一致の規則により API 側が優先される。
 */
public class StaticHandler implements HttpHandler {

    private static final Map<String, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put(".html", "text/html; charset=UTF-8");
        TYPES.put(".css", "text/css; charset=UTF-8");
        TYPES.put(".js", "text/javascript; charset=UTF-8");
        TYPES.put(".webmanifest", "application/manifest+json; charset=UTF-8");
        TYPES.put(".json", "application/json; charset=UTF-8");
        TYPES.put(".png", "image/png");
        TYPES.put(".svg", "image/svg+xml");
        TYPES.put(".ico", "image/x-icon");
        TYPES.put(".woff2", "font/woff2");
        TYPES.put(".map", "application/json; charset=UTF-8");
    }

    private final Path root;

    public StaticHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                WebServer.send(exchange, 405, "text/plain; charset=UTF-8", "GET のみ受け付けます");
                return;
            }

            String rel = exchange.getRequestURI().getPath();
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            if (rel.isEmpty() || rel.endsWith("/")) {
                rel = rel + "index.html";
            }

            Path file = root.resolve(rel).normalize();

            // 配信ルートの外に出ようとする要求は拒否する
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                WebServer.send(exchange, 404, "text/plain; charset=UTF-8",
                        "見つかりません: " + rel);
                return;
            }

            byte[] body = Files.readAllBytes(file);
            String name = file.getFileName().toString();

            String type = "application/octet-stream";
            for (Map.Entry<String, String> e : TYPES.entrySet()) {
                if (name.endsWith(e.getKey())) {
                    type = e.getValue();
                    break;
                }
            }

            // 画面まわりは常に最新を取りに行かせる。vendor だけ長めに持たせる。
            boolean vendor = file.getParent() != null
                    && file.getParent().getFileName() != null
                    && file.getParent().getFileName().toString().equals("vendor");
            exchange.getResponseHeaders().set("Cache-Control",
                    vendor ? "public, max-age=604800" : "no-cache");

            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }

        } finally {
            exchange.close();
        }
    }
}
