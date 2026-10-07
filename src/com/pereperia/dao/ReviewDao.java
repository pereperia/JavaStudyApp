package com.pereperia.dao;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 復習スケジュール。
 *
 * 間隔は正解のたびに 1 → 3 → 7 → 14 → 30 日と伸ばし、
 * 間違えたら 1 日に戻す。SM-2 のような自己評価の入力は求めない。
 */
public class ReviewDao {

    /** 間隔のはしご（日） */
    private static final int[] LADDER = {1, 3, 7, 14, 30};

    /** 復習の対象 1 件 */
    public record DueItem(int questionId, int chapter, int number, String topic,
                          String nextDue, int intervalDays, int overdueDays,
                          Integer lastRound, Boolean lastCorrect, String lastNote) {
    }

    /** 現在の間隔から次の間隔を返す */
    static int nextInterval(int current) {
        for (int d : LADDER) {
            if (d > current) {
                return d;
            }
        }
        return LADDER[LADDER.length - 1];
    }

    /**
     * 解答結果を受けてスケジュールを更新する。
     * 行が無ければ作る。
     */
    public static void record(int questionId, boolean correct, LocalDate today) {
        int current = currentInterval(questionId);
        int next = correct ? nextInterval(current) : LADDER[0];

        String sql = """
            MERGE INTO review_schedule (question_id, next_due, interval_d, updated_at)
            KEY (question_id)
            VALUES (?, ?, ?, ?)
            """;

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, questionId);
            ps.setDate(2, Date.valueOf(today.plusDays(next)));
            ps.setInt(3, next);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();

        } catch (SQLException e) {
            System.err.println("復習予定の更新に失敗: " + e.getMessage());
        }
    }

    /** 現在の間隔。行が無ければ 0（次は 1 日になる） */
    private static int currentInterval(int questionId) {
        String sql = "SELECT interval_d FROM review_schedule WHERE question_id = ?";

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, questionId);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            System.err.println("間隔の取得に失敗: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 既存の attempt から復習予定を埋める。
     * すでに行がある問題は触らないので、何度実行しても安全。
     *
     * @return 追加した件数
     */
    public static int backfill(LocalDate today) {
        String sql = """
            SELECT a.question_id,
                   a.correct,
                   a.answered_on AS answered_on
            FROM attempt a
            WHERE a.id = (SELECT MAX(a2.id)
                            FROM attempt a2
                           WHERE a2.question_id = a.question_id)
              AND a.question_id NOT IN (SELECT question_id FROM review_schedule)
            """;

        record Seed(int questionId, boolean correct, LocalDate answeredOn) { }
        List<Seed> seeds = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Date d = rs.getDate("answered_on");
                seeds.add(new Seed(
                        rs.getInt("question_id"),
                        rs.getBoolean("correct"),
                        d == null ? today : d.toLocalDate()));
            }
        } catch (SQLException e) {
            System.err.println("復習予定の初期化に失敗: " + e.getMessage());
            return 0;
        }

        if (seeds.isEmpty()) {
            return 0;
        }

        String insert = """
            INSERT INTO review_schedule (question_id, next_due, interval_d, updated_at)
            VALUES (?, ?, ?, ?)
            """;

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(insert)) {

            for (Seed s : seeds) {
                // 正解なら 3 日後、不正解なら翌日を起点にする
                int interval = s.correct() ? 3 : 1;
                ps.setInt(1, s.questionId());
                ps.setDate(2, Date.valueOf(s.answeredOn().plusDays(interval)));
                ps.setInt(3, interval);
                ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
                ps.addBatch();
            }
            return ps.executeBatch().length;

        } catch (SQLException e) {
            System.err.println("復習予定の投入に失敗: " + e.getMessage());
            return 0;
        }
    }

    /** 期限が来ている問題を、期限の古い順に返す */
    public static List<DueItem> findDue(LocalDate today, int limit) {
        String sql = """
            SELECT r.question_id, q.chapter, q.number, q.topic,
                   r.next_due, r.interval_d,
                   latest.round   AS last_round,
                   latest.correct AS last_correct,
                   latest.note    AS last_note
            FROM review_schedule r
            JOIN question q ON q.id = r.question_id
            LEFT JOIN attempt latest
                   ON latest.question_id = q.id
                  AND latest.id = (SELECT MAX(a2.id)
                                     FROM attempt a2
                                    WHERE a2.question_id = q.id)
            WHERE r.next_due <= ?
            ORDER BY r.next_due, q.chapter, q.number
            LIMIT ?
            """;

        List<DueItem> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setDate(1, Date.valueOf(today));
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate due = rs.getDate("next_due").toLocalDate();

                    int round = rs.getInt("last_round");
                    Integer lastRound = rs.wasNull() ? null : round;

                    boolean correct = rs.getBoolean("last_correct");
                    Boolean lastCorrect = rs.wasNull() ? null : correct;

                    list.add(new DueItem(
                            rs.getInt("question_id"),
                            rs.getInt("chapter"),
                            rs.getInt("number"),
                            rs.getString("topic"),
                            due.toString(),
                            rs.getInt("interval_d"),
                            (int) (today.toEpochDay() - due.toEpochDay()),
                            lastRound,
                            lastCorrect,
                            rs.getString("last_note")));
                }
            }
        } catch (SQLException e) {
            System.err.println("復習対象の取得に失敗: " + e.getMessage());
        }
        return list;
    }

    /** 期限が来ている件数 */
    public static int countDue(LocalDate today) {
        String sql = "SELECT COUNT(*) FROM review_schedule WHERE next_due <= ?";

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setDate(1, Date.valueOf(today));

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("件数の取得に失敗: " + e.getMessage());
            return 0;
        }
    }

    /** 明日以降で、次に復習がある日 */
    public static String findNextDueDate(LocalDate today) {
        String sql = "SELECT MIN(next_due) FROM review_schedule WHERE next_due > ?";

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setDate(1, Date.valueOf(today));

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Date d = rs.getDate(1);
                return d == null ? null : d.toLocalDate().toString();
            }
        } catch (SQLException e) {
            return null;
        }
    }
}
