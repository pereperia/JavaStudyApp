package com.pereperia.web;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;

/**
 * ログインしていないリクエストを弾くフィルタ。
 *
 * KUROHON_PASSWORD_HASH が未設定なら素通しするので、
 * 設定しない限り今までどおり動く。
 */
public class AuthFilter extends Filter {

    /** ログインしていなくても通すパス */
    private static final List<String> OPEN = List.of(
            "/login.html",
            "/api/login",
            "/style.css",
            "/manifest.webmanifest",
            "/sw.js",
            "/favicon.ico");

    private static final List<String> OPEN_PREFIX = List.of("/icons/", "/vendor/");

    @Override
    public String description() {
        return "kurohon auth";
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        if (!Auth.enabled() || isOpen(exchange.getRequestURI().getPath())) {
            chain.doFilter(exchange);
            return;
        }

        if (Auth.valid(cookie(exchange, Auth.COOKIE))) {
            chain.doFilter(exchange);
            return;
        }

        // API は 401、画面はログインページへ送る
        if (exchange.getRequestURI().getPath().startsWith("/api/")) {
            WebServer.send(exchange, 401, "text/plain; charset=UTF-8", "ログインしてください");
        } else {
            exchange.getResponseHeaders().set("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }
    }

    private static boolean isOpen(String path) {
        if (OPEN.contains(path)) {
            return true;
        }
        for (String prefix : OPEN_PREFIX) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Cookie ヘッダから 1 つ取り出す */
    static String cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }

        for (String header : headers) {
            for (String part : header.split(";")) {
                String item = part.trim();
                int eq = item.indexOf('=');
                if (eq > 0 && item.substring(0, eq).equals(name)) {
                    return item.substring(eq + 1);
                }
            }
        }
        return null;
    }
}
