package com.pereperia.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.pereperia.dao.AttemptDao;
import com.pereperia.dao.DbManager;
import com.pereperia.dao.GapDao;
import com.pereperia.dao.QuestionDao;
import com.pereperia.dao.ReviewDao;
import com.pereperia.dao.WebDao;
import com.pereperia.entity.AttemptView;

public class WebServer {

    private static final int PORT = 8080;

    /** 画面まわりの静的ファイルの置き場所（プロジェクト直下の web/） */
    private static final Path WEB_ROOT = Path.of("web");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MM/dd HH:mm");

    public static void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        AuthFilter auth = new AuthFilter();

        // API のほうがパスとして長いので、"/" に静的配信を置いても食い合わない
        register(server, auth, "/", new StaticHandler(WEB_ROOT));

        register(server, auth, "/api/summary", WebServer::handleSummary);
        register(server, auth, "/api/chapters", WebServer::handleChapters);
        register(server, auth, "/api/questions", WebServer::handleQuestions);
        register(server, auth, "/api/attempts", WebServer::handleAttempts);
        register(server, auth, "/api/notes", WebServer::handleNotes);
        register(server, auth, "/api/books", WebServer::handleBooks);
        register(server, auth, "/api/history", WebServer::handleHistory);
        register(server, auth, "/api/topics", WebServer::handleTopics);
        register(server, auth, "/api/hours", WebServer::handleHours);
        register(server, auth, "/api/review", WebServer::handleReview);
        register(server, auth, "/api/gaps", WebServer::handleGaps);
        register(server, auth, "/api/login", WebServer::handleLogin);
        register(server, auth, "/api/logout", WebServer::handleLogout);

        // 既存の解答から復習予定を埋める（すでにある行は触らない）
        int filled = ReviewDao.backfill(LocalDate.now());
        if (filled > 0) {
            System.out.println("復習予定を " + filled + " 件初期化しました");
        }

        server.setExecutor(null);
        server.start();

        System.out.println("起動しました → http://localhost:" + PORT);
        System.out.println("教材: " + DbManager.currentBook());
        System.out.println("認証: " + (Auth.enabled() ? "有効（" + Auth.userName() + "）" : "無効"));
        System.out.println("停止は Ctrl+C");
    }

    /** コンテキストを登録して、認証フィルタを掛ける */
    private static void register(HttpServer server, AuthFilter auth,
                                 String path, HttpHandler handler) {
        server.createContext(path, handler).getFilters().add(auth);
    }

    // ------------------------------------------------------------------
    //  GET /api/summary
    // ------------------------------------------------------------------
    private static void handleSummary(HttpExchange exchange) throws IOException {
        WebDao.Summary s = WebDao.findSummary();

        String json = String.format(
                "{\"book\":%s,\"totalQuestions\":%d,\"answeredQuestions\":%d,"
                + "\"attempts\":%d,\"correct\":%d}",
                Json.escape(DbManager.currentBook()),
                s.totalQuestions(), s.answeredQuestions(), s.attempts(), s.correct());

        sendJson(exchange, json);
    }

    // ------------------------------------------------------------------
    //  GET /api/chapters
    // ------------------------------------------------------------------
    private static void handleChapters(HttpExchange exchange) throws IOException {
        List<WebDao.ChapterProgress> stats = WebDao.findChapterProgress();

        String json = stats.stream()
                .map(s -> String.format(
                        "{\"chapter\":%d,\"total\":%d,\"ok\":%d,\"ng\":%d,"
                        + "\"untouched\":%d,\"answered\":%d}",
                        s.chapter(), s.total(), s.ok(), s.ng(),
                        s.untouched(), s.ok() + s.ng()))
                .collect(Collectors.joining(",", "[", "]"));

        sendJson(exchange, json);
    }

    // ------------------------------------------------------------------
    //  GET /api/questions?chapter=N
    // ------------------------------------------------------------------
    private static void handleQuestions(HttpExchange exchange) throws IOException {
        int chapter = intParam(exchange.getRequestURI().getQuery(), "chapter", -1);

        if (chapter < 0) {
            send(exchange, 400, "text/plain; charset=UTF-8", "chapter を指定してください");
            return;
        }

        List<WebDao.QuestionRow> list = WebDao.findQuestionRows(chapter);

        String json = list.stream()
                .map(q -> String.format(
                        "{\"id\":%d,\"chapter\":%d,\"number\":%d,\"topic\":%s,"
                        + "\"round\":%s,\"correct\":%s}",
                        q.id(), q.chapter(), q.number(), Json.escape(q.topic()),
                        q.lastRound() == null ? "null" : String.valueOf(q.lastRound()),
                        q.lastCorrect() == null ? "null" : String.valueOf(q.lastCorrect())))
                .collect(Collectors.joining(",", "[", "]"));

        sendJson(exchange, json);
    }

    // ------------------------------------------------------------------
    //  GET /api/history?days=84
    // ------------------------------------------------------------------
    private static void handleHistory(HttpExchange exchange) throws IOException {
        int days = intParam(exchange.getRequestURI().getQuery(), "days", 84);
        if (days < 1 || days > 400) {
            days = 84;
        }

        List<WebDao.DayCount> list = WebDao.findDailyHistory(days);

        String json = list.stream()
                .map(d -> String.format("{\"date\":%s,\"total\":%d,\"correct\":%d}",
                        Json.escape(d.date()), d.total(), d.correct()))
                .collect(Collectors.joining(",", "[", "]"));

        sendJson(exchange, String.format("{\"days\":%d,\"history\":%s}", days, json));
    }

    // ------------------------------------------------------------------
    //  GET /api/hours
    // ------------------------------------------------------------------
    private static void handleHours(HttpExchange exchange) throws IOException {
        List<WebDao.HourStat> list = WebDao.findHourStats();

        int measured = list.stream().mapToInt(WebDao.HourStat::total).sum();

        String json = list.stream()
                .map(h -> String.format("{\"hour\":%d,\"total\":%d,\"correct\":%d}",
                        h.hour(), h.total(), h.correct()))
                .collect(Collectors.joining(",", "[", "]"));

        sendJson(exchange, String.format("{\"measured\":%d,\"hours\":%s}", measured, json));
    }

    // ------------------------------------------------------------------
    //  GET  /api/topics
    //  POST /api/topics   questionId=..&topic=..
    // ------------------------------------------------------------------
    private static void handleTopics(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> params = parseQuery(readBody(exchange));

            int questionId = intParam(params, "questionId", -1);
            if (questionId < 0) {
                send(exchange, 400, "text/plain; charset=UTF-8", "questionId が不正です");
                return;
            }

            boolean done = WebDao.updateTopic(questionId, params.getOrDefault("topic", ""));
            sendJson(exchange, "{\"updated\":" + done + "}");
            return;
        }

        String json = WebDao.findTopicStats().stream()
                .map(t -> String.format(
                        "{\"topic\":%s,\"total\":%d,\"ok\":%d,\"ng\":%d,\"untouched\":%d}",
                        Json.escape(t.topic()), t.total(), t.ok(), t.ng(), t.untouched()))
                .collect(Collectors.joining(",", "[", "]"));

        sendJson(exchange, json);
    }

    // ------------------------------------------------------------------
    //  GET  /api/notes?chapter=N
    //  POST /api/notes   attemptId=..&note=..
    // ------------------------------------------------------------------
    private static void handleNotes(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> params = parseQuery(readBody(exchange));

            int attemptId = intParam(params, "attemptId", -1);
            String note = params.getOrDefault("note", "");

            if (attemptId < 0) {
                send(exchange, 400, "text/plain; charset=UTF-8", "attemptId が不正です");
                return;
            }

            boolean done = AttemptDao.updateNote(attemptId, note.isBlank() ? null : note);
            sendJson(exchange, "{\"updated\":" + done + "}");
            return;
        }

        int chapter = intParam(exchange.getRequestURI().getQuery(), "chapter", -1);
        if (chapter < 0) {
            send(exchange, 400, "text/plain; charset=UTF-8", "chapter を指定してください");
            return;
        }

        List<AttemptView> notes = WebDao.findNotesByChapter(chapter);

        String json = notes.stream()
                .map(a -> String.format(
                        "{\"attemptId\":%d,\"number\":%d,\"round\":%d,\"correct\":%s,"
                        + "\"answeredAt\":%s,\"note\":%s}",
                        a.attemptId(), a.number(), a.round(), a.correct(),
                        a.answerdAt() == null ? "null" : Json.escape(a.answerdAt().format(STAMP)),
                        Json.escape(a.note())))
                .collect(Collectors.joining(",", "[", "]"));

        sendJson(exchange, json);
    }

    // ------------------------------------------------------------------
    //  GET  /api/books
    //  POST /api/books   book=murasaki
    // ------------------------------------------------------------------
    private static void handleBooks(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> params = parseQuery(readBody(exchange));
            String book = params.getOrDefault("book", "");

            // data/ に実在する教材だけを受け付ける
            if (!WebDao.listBooks().contains(book)) {
                send(exchange, 400, "text/plain; charset=UTF-8", "その教材はありません: " + book);
                return;
            }

            DbManager.use(book);
            // 空のDBに切り替えてもエラーにならないよう、テーブルだけ用意しておく
            DbManager.initialize();
            ReviewDao.backfill(LocalDate.now());
            System.out.println("教材を切り替えました → " + book);
        }

        String books = WebDao.listBooks().stream()
                .map(Json::escape)
                .collect(Collectors.joining(",", "[", "]"));

        sendJson(exchange, String.format("{\"current\":%s,\"books\":%s,\"auth\":%s}",
                Json.escape(DbManager.currentBook()), books, Auth.enabled()));
    }

    // ------------------------------------------------------------------
    //  GET  /api/review
    //  POST /api/review   questionId=..&correct=y|n&note=..
    // ------------------------------------------------------------------
    private static void handleReview(HttpExchange exchange) throws IOException {
        LocalDate today = LocalDate.now();

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> params = parseQuery(readBody(exchange));

            int questionId = intParam(params, "questionId", -1);
            String value = params.getOrDefault("correct", "").trim().toLowerCase();

            if (questionId < 0 || (!value.startsWith("y") && !value.startsWith("n"))) {
                send(exchange, 400, "text/plain; charset=UTF-8", "questionId と correct が必要です");
                return;
            }

            boolean correct = value.startsWith("y");
            String note = params.get("note");
            if (note != null && note.isBlank()) {
                note = null;
            }

            int round = AttemptDao.nextRound(questionId);
            boolean saved = AttemptDao.insert(questionId, round, correct, note);

            if (saved) {
                ReviewDao.record(questionId, correct, today);

                String cause = params.get("cause");
                if (!correct && cause != null && !cause.isBlank()) {
                    GapDao.setCause(questionId, cause);
                }
            }

            sendJson(exchange, String.format(
                    "{\"saved\":%s,\"round\":%d,\"remaining\":%d}",
                    saved, round, ReviewDao.countDue(today)));
            return;
        }

        int limit = intParam(exchange.getRequestURI().getQuery(), "limit", 20);
        if (limit < 1 || limit > 100) {
            limit = 20;
        }

        List<ReviewDao.DueItem> due = ReviewDao.findDue(today, limit);
        String next = ReviewDao.findNextDueDate(today);

        String items = due.stream()
                .map(d -> String.format(
                        "{\"questionId\":%d,\"chapter\":%d,\"number\":%d,\"topic\":%s,"
                        + "\"nextDue\":%s,\"intervalDays\":%d,\"overdueDays\":%d,"
                        + "\"lastRound\":%s,\"lastCorrect\":%s,\"lastNote\":%s}",
                        d.questionId(), d.chapter(), d.number(), Json.escape(d.topic()),
                        Json.escape(d.nextDue()), d.intervalDays(), d.overdueDays(),
                        d.lastRound() == null ? "null" : String.valueOf(d.lastRound()),
                        d.lastCorrect() == null ? "null" : String.valueOf(d.lastCorrect()),
                        Json.escape(d.lastNote())))
                .collect(Collectors.joining(",", "[", "]"));

        sendJson(exchange, String.format(
                "{\"today\":%s,\"dueCount\":%d,\"nextDueDate\":%s,\"items\":%s}",
                Json.escape(today.toString()), ReviewDao.countDue(today),
                next == null ? "null" : Json.escape(next), items));
    }

    // ------------------------------------------------------------------
    //  GET  /api/gaps?limit=200
    //  POST /api/gaps   questionId=..&cause=..        原因を付け替える
    //  POST /api/gaps   questionId=..&nextDue=YYYY-MM-DD  再テスト日を手で決める
    // ------------------------------------------------------------------
    private static void handleGaps(HttpExchange exchange) throws IOException {
        LocalDate today = LocalDate.now();

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> params = parseQuery(readBody(exchange));

            int questionId = intParam(params, "questionId", -1);
            if (questionId < 0) {
                send(exchange, 400, "text/plain; charset=UTF-8", "questionId が不正です");
                return;
            }

            boolean done = false;

            if (params.containsKey("cause")) {
                done = GapDao.setCause(questionId, params.get("cause"));
            }

            if (params.containsKey("nextDue")) {
                String raw = params.get("nextDue").trim();
                try {
                    done = GapDao.setNextDue(questionId, LocalDate.parse(raw));
                } catch (java.time.format.DateTimeParseException e) {
                    send(exchange, 400, "text/plain; charset=UTF-8",
                            "日付の形式が違います: " + raw);
                    return;
                }
            }

            sendJson(exchange, "{\"updated\":" + done + "}");
            return;
        }

        int limit = intParam(exchange.getRequestURI().getQuery(), "limit", 200);
        if (limit < 1 || limit > 500) {
            limit = 200;
        }

        GapDao.GapSummary sum = GapDao.summarize();

        String items = GapDao.findOpen(today, limit).stream()
                .map(g -> String.format(
                        "{\"questionId\":%d,\"chapter\":%d,\"number\":%d,\"topic\":%s,"
                        + "\"cause\":%s,\"round\":%d,\"answeredAt\":%s,\"note\":%s,"
                        + "\"nextDue\":%s,\"overdueDays\":%d}",
                        g.questionId(), g.chapter(), g.number(), Json.escape(g.topic()),
                        Json.escape(g.cause()), g.round(), Json.escape(g.answeredAt()),
                        Json.escape(g.note()), Json.escape(g.nextDue()), g.overdueDays()))
                .collect(Collectors.joining(",", "[", "]"));

        sendJson(exchange, String.format(
                "{\"today\":%s,\"open\":%d,\"closed\":%d,"
                + "\"knowledge\":%d,\"understanding\":%d,\"careless\":%d,\"unset\":%d,"
                + "\"items\":%s}",
                Json.escape(today.toString()), sum.open(), sum.closed(),
                sum.knowledge(), sum.understanding(), sum.careless(), sum.unset(), items));
    }

    // ------------------------------------------------------------------
    //  POST /api/attempts
    //    chapter=7&results=1:y,2:n&note_1=...&note_2=...
    // ------------------------------------------------------------------
    private static void handleAttempts(HttpExchange exchange) throws IOException {
        // GET /api/attempts?questionId=N は 1 問の解答履歴を返す
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            int questionId = intParam(exchange.getRequestURI().getQuery(), "questionId", -1);

            if (questionId < 0) {
                send(exchange, 400, "text/plain; charset=UTF-8", "questionId を指定してください");
                return;
            }

            String json = WebDao.findAttemptsByQuestion(questionId).stream()
                    .map(a -> String.format(
                            "{\"attemptId\":%d,\"round\":%d,\"correct\":%s,"
                            + "\"answeredAt\":%s,\"note\":%s}",
                            a.attemptId(), a.round(), a.correct(),
                            a.answerdAt() == null ? "null"
                                    : Json.escape(a.answerdAt().format(STAMP)),
                            Json.escape(a.note())))
                    .collect(Collectors.joining(",", "[", "]"));

            sendJson(exchange, json);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=UTF-8", "GET か POST のみ受け付けます");
            return;
        }

        Map<String, String> params = parseQuery(readBody(exchange));

        int chapter = intParam(params, "chapter", -1);
        if (chapter < 0) {
            send(exchange, 400, "text/plain; charset=UTF-8", "chapter が不正です");
            return;
        }

        String results = params.get("results");
        if (results == null || results.isBlank()) {
            send(exchange, 400, "text/plain; charset=UTF-8", "results がありません");
            return;
        }

        // 解いた日。指定が無ければ今日。今日以外なら時刻を持たせない。
        LocalDate today = LocalDate.now();
        LocalDate answeredOn = today;
        String rawDate = params.get("answeredOn");

        if (rawDate != null && !rawDate.isBlank()) {
            try {
                answeredOn = LocalDate.parse(rawDate.trim());
            } catch (java.time.format.DateTimeParseException e) {
                send(exchange, 400, "text/plain; charset=UTF-8", "日付の形式が違います: " + rawDate);
                return;
            }
            if (answeredOn.isAfter(today)) {
                send(exchange, 400, "text/plain; charset=UTF-8", "未来の日付は指定できません");
                return;
            }
        }

        boolean withTime = answeredOn.equals(today);

        int ok = 0;
        int ng = 0;

        for (String item : results.split(",")) {
            String[] kv = item.split(":", 2);
            if (kv.length != 2) {
                continue;
            }

            int number;
            try {
                number = Integer.parseInt(kv[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }

            char c = Character.toLowerCase(kv[1].trim().charAt(0));
            if (c != 'y' && c != 'n') {
                continue;
            }

            int questionId = QuestionDao.findId(chapter, number);
            if (questionId < 0) {
                continue;
            }

            String note = params.get("note_" + number);
            if (note != null && note.isBlank()) {
                note = null;
            }

            boolean correct = (c == 'y');
            int round = AttemptDao.nextRound(questionId);

            if (WebDao.insertAttempt(questionId, round, correct, note, answeredOn, withTime)) {
                ReviewDao.record(questionId, correct, answeredOn);

                String cause = params.get("cause_" + number);
                if (!correct && cause != null && !cause.isBlank()) {
                    GapDao.setCause(questionId, cause);
                }

                if (correct) {
                    ok++;
                } else {
                    ng++;
                }
            }
        }

        sendJson(exchange, String.format(
                "{\"recorded\":%d,\"correct\":%d,\"wrong\":%d,\"answeredOn\":%s}",
                ok + ng, ok, ng, Json.escape(answeredOn.toString())));
    }

    // ------------------------------------------------------------------
    //  POST /api/login    user=..&password=..
    //  POST /api/logout
    // ------------------------------------------------------------------
    private static void handleLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=UTF-8", "POST のみ受け付けます");
            return;
        }

        if (!Auth.enabled()) {
            sendJson(exchange, "{\"ok\":true,\"note\":\"認証は無効です\"}");
            return;
        }

        String from = clientIp(exchange);

        if (!Auth.allowAttempt(from)) {
            send(exchange, 429, "text/plain; charset=UTF-8",
                    "試行回数が多すぎます。5 分ほど待ってください");
            return;
        }

        Map<String, String> params = parseQuery(readBody(exchange));
        String token = Auth.login(params.get("user"), params.get("password"), from);

        if (token == null) {
            send(exchange, 401, "text/plain; charset=UTF-8", "名前かパスワードが違います");
            return;
        }

        exchange.getResponseHeaders().add("Set-Cookie", cookieHeader(exchange, token));
        sendJson(exchange, "{\"ok\":true}");
    }

    private static void handleLogout(HttpExchange exchange) throws IOException {
        Auth.logout(AuthFilter.cookie(exchange, Auth.COOKIE));

        exchange.getResponseHeaders().add("Set-Cookie",
                Auth.COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        sendJson(exchange, "{\"ok\":true}");
    }

    /**
     * 送信元アドレス。nginx などを挟むと getRemoteAddress はプロキシの IP になるので、
     * X-Forwarded-For の先頭を優先する。
     */
    private static String clientIp(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");

        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    /** Cookie の組み立て。HTTPS 経由なら Secure を付ける */
    private static String cookieHeader(HttpExchange exchange, String token) {
        String proto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        boolean https = "https".equalsIgnoreCase(proto);

        long maxAge = 30L * 24 * 60 * 60;
        String days = System.getenv("KUROHON_SESSION_DAYS");
        try {
            if (days != null && !days.isBlank()) {
                maxAge = Long.parseLong(days.trim()) * 24 * 60 * 60;
            }
        } catch (NumberFormatException ignored) {
            // 既定の 30 日を使う
        }

        return Auth.COOKIE + "=" + token
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAge
                + (https ? "; Secure" : "");
    }

    // ------------------------------------------------------------------
    //  共通処理
    // ------------------------------------------------------------------

    /** レスポンスを返す */
    static void send(HttpExchange exchange, int status,
                     String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendJson(HttpExchange exchange, String json) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, 200, "application/json; charset=UTF-8", json);
    }

    /** "a=1&b=2" を Map にする */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) {
            return map;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static int intParam(Map<String, String> params, String key, int fallback) {
        try {
            return Integer.parseInt(params.getOrDefault(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int intParam(String query, String key, int fallback) {
        return intParam(parseQuery(query), key, fallback);
    }

    /** POST のボディを文字列として読む */
    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
