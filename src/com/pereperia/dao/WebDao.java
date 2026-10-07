package com.pereperia.dao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.pereperia.entity.AttemptView;

/**
 * Web 画面のための集計をまとめたクラス。
 *
 * 既存の QuestionDao / AttemptDao には手を入れずに済むよう、
 * Web でしか使わない問い合わせをここに集めている。
 */
public class WebDao {

    /** 章ごとの内訳。ok は最新が正解の問題数、ng は最新が不正解の問題数 */
    public record ChapterProgress(int chapter, int total, int ok, int ng) {
        public int untouched() {
            return total - ok - ng;
        }
    }

    /** 教材全体のまとめ */
    public record Summary(int totalQuestions, int answeredQuestions,
                          int attempts, int correct) {
    }

    /** 問題 1 件と、その最新の解答状況。topic を含む */
    public record QuestionRow(int id, int chapter, int number, String topic,
                              Integer lastRound, Boolean lastCorrect) {
    }

    /** 1 日分の記録数 */
    public record DayCount(String date, int total, int correct) {
    }

    /** 時間帯ごとの成績。時刻が分かる記録だけが対象 */
    public record HourStat(int hour, int total, int correct) {
    }

    /** 分野（topic）ごとの成績 */
    public record TopicStat(String topic, int total, int ok, int ng) {
        public int untouched() {
            return total - ok - ng;
        }
    }

    /**
     * 章ごとの進捗を返す。
     *
     * 「最新の解答」は round ではなく id の最大値で決めている。
     * 同じ周回が重複して入っていても 1 問が 2 回数えられないようにするため。
     */
    public static List<ChapterProgress> findChapterProgress() {
        String sql = """
            SELECT q.chapter,
                   COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN latest.correct = TRUE  THEN 1 ELSE 0 END), 0) AS ok,
                   COALESCE(SUM(CASE WHEN latest.correct = FALSE THEN 1 ELSE 0 END), 0) AS ng
            FROM question q
            LEFT JOIN attempt latest
                   ON latest.question_id = q.id
                  AND latest.id = (SELECT MAX(a2.id)
                                     FROM attempt a2
                                    WHERE a2.question_id = q.id)
            GROUP BY q.chapter
            ORDER BY q.chapter
            """;

        List<ChapterProgress> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                list.add(new ChapterProgress(
                        rs.getInt("chapter"),
                        rs.getInt("total"),
                        rs.getInt("ok"),
                        rs.getInt("ng")));
            }
        } catch (SQLException e) {
            System.err.println("章別集計に失敗: " + e.getMessage());
        }
        return list;
    }

    /** 教材全体のまとめを返す */
    public static Summary findSummary() {
        String sql = """
            SELECT (SELECT COUNT(*) FROM question)                        AS total_q,
                   (SELECT COUNT(DISTINCT question_id) FROM attempt)      AS answered_q,
                   (SELECT COUNT(*) FROM attempt)                         AS attempts,
                   (SELECT COUNT(*) FROM attempt WHERE correct = TRUE)    AS correct
            """;

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            rs.next();
            return new Summary(
                    rs.getInt("total_q"),
                    rs.getInt("answered_q"),
                    rs.getInt("attempts"),
                    rs.getInt("correct"));

        } catch (SQLException e) {
            System.err.println("集計に失敗: " + e.getMessage());
            return new Summary(0, 0, 0, 0);
        }
    }

    /** 指定した章の、メモが入っている記録を返す */
    public static List<AttemptView> findNotesByChapter(int chapter) {
        String sql = """
            SELECT a.id, q.chapter, q.number, a.round, a.correct, a.answered_at, a.note
            FROM attempt a
            JOIN question q ON a.question_id = q.id
            WHERE q.chapter = ?
              AND a.note IS NOT NULL
              AND a.note <> ''
            ORDER BY q.number, a.round
            """;

        List<AttemptView> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, chapter);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("answered_at");
                    list.add(new AttemptView(
                            rs.getInt("id"),
                            rs.getInt("chapter"),
                            rs.getInt("number"),
                            rs.getInt("round"),
                            rs.getBoolean("correct"),
                            ts == null ? null : ts.toLocalDateTime(),
                            rs.getString("note")));
                }
            }
        } catch (SQLException e) {
            System.err.println("メモ取得に失敗: " + e.getMessage());
        }
        return list;
    }

    /** 指定した章の問題を、topic と最新の解答状況付きで返す */
    public static List<QuestionRow> findQuestionRows(int chapter) {
        String sql = """
            SELECT q.id, q.chapter, q.number, q.topic,
                   latest.round   AS last_round,
                   latest.correct AS last_correct
            FROM question q
            LEFT JOIN attempt latest
                   ON latest.question_id = q.id
                  AND latest.id = (SELECT MAX(a2.id)
                                     FROM attempt a2
                                    WHERE a2.question_id = q.id)
            WHERE q.chapter = ?
            ORDER BY q.number
            """;

        List<QuestionRow> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, chapter);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int round = rs.getInt("last_round");
                    Integer lastRound = rs.wasNull() ? null : round;

                    boolean correct = rs.getBoolean("last_correct");
                    Boolean lastCorrect = rs.wasNull() ? null : correct;

                    list.add(new QuestionRow(
                            rs.getInt("id"),
                            rs.getInt("chapter"),
                            rs.getInt("number"),
                            rs.getString("topic"),
                            lastRound,
                            lastCorrect));
                }
            }
        } catch (SQLException e) {
            System.err.println("問題一覧の取得に失敗: " + e.getMessage());
        }
        return list;
    }

    /** 1 問の解答履歴を古い順に返す */
    public static List<AttemptView> findAttemptsByQuestion(int questionId) {
        String sql = """
            SELECT a.id, q.chapter, q.number, a.round, a.correct, a.answered_at, a.note
            FROM attempt a
            JOIN question q ON a.question_id = q.id
            WHERE a.question_id = ?
            ORDER BY a.id
            """;

        List<AttemptView> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, questionId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("answered_at");
                    list.add(new AttemptView(
                            rs.getInt("id"),
                            rs.getInt("chapter"),
                            rs.getInt("number"),
                            rs.getInt("round"),
                            rs.getBoolean("correct"),
                            ts == null ? null : ts.toLocalDateTime(),
                            rs.getString("note")));
                }
            }
        } catch (SQLException e) {
            System.err.println("履歴の取得に失敗: " + e.getMessage());
        }
        return list;
    }

    /** 直近 days 日分の、日ごとの記録数を返す（記録が無い日は含まれない） */
    public static List<DayCount> findDailyHistory(int days) {
        String sql = """
            SELECT a.answered_on AS d,
                   COUNT(*) AS total,
                   SUM(CASE WHEN a.correct THEN 1 ELSE 0 END) AS ok
            FROM attempt a
            WHERE a.answered_on >= ?
            GROUP BY a.answered_on
            ORDER BY d
            """;

        List<DayCount> list = new ArrayList<>();
        java.sql.Date from = java.sql.Date.valueOf(LocalDate.now().minusDays(days));

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setDate(1, from);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new DayCount(
                            rs.getDate("d").toLocalDate().toString(),
                            rs.getInt("total"),
                            rs.getInt("ok")));
                }
            }
        } catch (SQLException e) {
            System.err.println("履歴の集計に失敗: " + e.getMessage());
        }
        return list;
    }

    /**
     * 時間帯ごとの成績を返す。
     *
     * answered_at が NULL の記録（あとから入力した過去分）は時刻が分からないので
     * 対象にしない。偽の 0 時が混ざって集計が歪むのを避けるため。
     */
    public static List<HourStat> findHourStats() {
        String sql = """
            SELECT HOUR(a.answered_at) AS h,
                   COUNT(*) AS total,
                   SUM(CASE WHEN a.correct THEN 1 ELSE 0 END) AS ok
            FROM attempt a
            WHERE a.answered_at IS NOT NULL
            GROUP BY HOUR(a.answered_at)
            ORDER BY h
            """;

        List<HourStat> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                list.add(new HourStat(rs.getInt("h"), rs.getInt("total"), rs.getInt("ok")));
            }
        } catch (SQLException e) {
            System.err.println("時間帯集計に失敗: " + e.getMessage());
        }
        return list;
    }

    /** 分野（topic）ごとの成績を、正答率の低い順に返す */
    public static List<TopicStat> findTopicStats() {
        String sql = """
            SELECT q.topic,
                   COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN latest.correct = TRUE  THEN 1 ELSE 0 END), 0) AS ok,
                   COALESCE(SUM(CASE WHEN latest.correct = FALSE THEN 1 ELSE 0 END), 0) AS ng
            FROM question q
            LEFT JOIN attempt latest
                   ON latest.question_id = q.id
                  AND latest.id = (SELECT MAX(a2.id)
                                     FROM attempt a2
                                    WHERE a2.question_id = q.id)
            WHERE q.topic IS NOT NULL AND q.topic <> ''
            GROUP BY q.topic
            ORDER BY COALESCE(SUM(CASE WHEN latest.correct = TRUE THEN 1 ELSE 0 END), 0) * 1.0
                     / NULLIF(COUNT(*), 0), q.topic
            """;

        List<TopicStat> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                list.add(new TopicStat(
                        rs.getString("topic"),
                        rs.getInt("total"),
                        rs.getInt("ok"),
                        rs.getInt("ng")));
            }
        } catch (SQLException e) {
            System.err.println("分野別集計に失敗: " + e.getMessage());
        }
        return list;
    }

    /** 問題に分野（topic）を設定する。空文字を渡すと消える */
    public static boolean updateTopic(int questionId, String topic) {
        String sql = "UPDATE question SET topic = ? WHERE id = ?";

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, (topic == null || topic.isBlank()) ? null : topic.trim());
            ps.setInt(2, questionId);
            return ps.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("分野の更新に失敗: " + e.getMessage());
            return false;
        }
    }

    /**
     * 解答を 1 件登録する。
     *
     * withTime が false のときは answered_at を NULL にして、
     * answered_on に日付だけを入れる。過去分をあとから入力する用。
     */
    public static boolean insertAttempt(int questionId, int round, boolean correct,
                                        String note, LocalDate on, boolean withTime) {
        String sql = """
            INSERT INTO attempt (question_id, round, correct, answered_at, answered_on, note)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, questionId);
            ps.setInt(2, round);
            ps.setBoolean(3, correct);

            if (withTime) {
                ps.setTimestamp(4, Timestamp.valueOf(java.time.LocalDateTime.now()));
            } else {
                ps.setNull(4, java.sql.Types.TIMESTAMP);
            }

            ps.setDate(5, java.sql.Date.valueOf(on));
            ps.setString(6, note);

            return ps.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("記録に失敗: " + e.getMessage());
            return false;
        }
    }

    /**
     * data/ 配下の .mv.db から教材名の一覧を作る。
     * リネームで退避した .old は拡張子が一致しないので自然に除外される。
     */
    public static List<String> listBooks() {
        Path dir = Path.of("data");
        List<String> books = new ArrayList<>();

        try (Stream<Path> stream = Files.list(dir)) {
            stream.map(p -> p.getFileName().toString())
                  .filter(n -> n.endsWith(".mv.db"))
                  .map(n -> n.substring(0, n.length() - ".mv.db".length()))
                  .sorted()
                  .forEach(books::add);
        } catch (IOException e) {
            System.err.println("教材一覧の取得に失敗: " + e.getMessage());
        }

        if (books.isEmpty()) {
            books.add(DbManager.currentBook());
        }
        return books;
    }
}
