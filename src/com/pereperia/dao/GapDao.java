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
 * 「穴」＝ 最新の解答が不正解のままの問題。
 *
 * 原因を knowledge（知識）/ understanding（理解）/ careless（ケアレスミス）で分類し、
 * 再テストの予定日と合わせて追う。
 */
public class GapDao {

    public static final List<String> CAUSES = List.of("knowledge", "understanding", "careless");

    /** 穴 1 件 */
    public record Gap(int questionId, int chapter, int number, String topic,
                      String cause, int round, String answeredAt, String note,
                      String nextDue, int overdueDays) {
    }

    /** 穴の集計 */
    public record GapSummary(int open, int closed, int knowledge, int understanding,
                             int careless, int unset) {
    }

    /** 最新の解答に原因を付ける。null や空文字で解除 */
    public static boolean setCause(int questionId, String cause) {
        String value = (cause == null || cause.isBlank()) ? null : cause.trim();

        if (value != null && !CAUSES.contains(value)) {
            return false;
        }

        String sql = """
            UPDATE attempt SET cause = ?
            WHERE id = (SELECT MAX(a2.id) FROM attempt a2 WHERE a2.question_id = ?)
            """;

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, value);
            ps.setInt(2, questionId);
            return ps.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("原因の更新に失敗: " + e.getMessage());
            return false;
        }
    }

    /** 再テストの予定日を手で決める */
    public static boolean setNextDue(int questionId, LocalDate date) {
        String sql = """
            MERGE INTO review_schedule (question_id, next_due, interval_d, updated_at)
            KEY (question_id)
            VALUES (?, ?, COALESCE((SELECT interval_d FROM review_schedule
                                     WHERE question_id = ?), 1), ?)
            """;

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, questionId);
            ps.setDate(2, Date.valueOf(date));
            ps.setInt(3, questionId);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            return ps.executeUpdate() >= 1;

        } catch (SQLException e) {
            System.err.println("再テスト日の更新に失敗: " + e.getMessage());
            return false;
        }
    }

    /** 残っている穴を、再テスト日の早い順に返す */
    public static List<Gap> findOpen(LocalDate today, int limit) {
        String sql = """
            SELECT q.id, q.chapter, q.number, q.topic,
                   latest.cause, latest.round, latest.answered_on, latest.note,
                   r.next_due
            FROM question q
            JOIN attempt latest
              ON latest.question_id = q.id
             AND latest.id = (SELECT MAX(a2.id) FROM attempt a2 WHERE a2.question_id = q.id)
            LEFT JOIN review_schedule r ON r.question_id = q.id
            WHERE latest.correct = FALSE
            ORDER BY r.next_due NULLS FIRST, q.chapter, q.number
            LIMIT ?
            """;

        List<Gap> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Date answered = rs.getDate("answered_on");
                    Date due = rs.getDate("next_due");

                    list.add(new Gap(
                            rs.getInt("id"),
                            rs.getInt("chapter"),
                            rs.getInt("number"),
                            rs.getString("topic"),
                            rs.getString("cause"),
                            rs.getInt("round"),
                            answered == null ? null : answered.toLocalDate().toString(),
                            rs.getString("note"),
                            due == null ? null : due.toLocalDate().toString(),
                            due == null ? 0
                                    : (int) (today.toEpochDay() - due.toLocalDate().toEpochDay())));
                }
            }
        } catch (SQLException e) {
            System.err.println("穴の取得に失敗: " + e.getMessage());
        }
        return list;
    }

    /**
     * 集計。
     * open  = 最新が不正解の問題数
     * closed = 過去に間違えたが、最新は正解になった問題数（埋まった穴）
     */
    public static GapSummary summarize() {
        String sql = """
            SELECT
              (SELECT COUNT(*) FROM question q
                JOIN attempt l ON l.question_id = q.id
                 AND l.id = (SELECT MAX(a2.id) FROM attempt a2 WHERE a2.question_id = q.id)
               WHERE l.correct = FALSE) AS open_cnt,

              (SELECT COUNT(*) FROM question q
                JOIN attempt l ON l.question_id = q.id
                 AND l.id = (SELECT MAX(a2.id) FROM attempt a2 WHERE a2.question_id = q.id)
               WHERE l.correct = TRUE
                 AND EXISTS (SELECT 1 FROM attempt a3
                              WHERE a3.question_id = q.id AND a3.correct = FALSE)) AS closed_cnt,

              (SELECT COUNT(*) FROM attempt l
                WHERE l.correct = FALSE
                  AND l.id = (SELECT MAX(a2.id) FROM attempt a2 WHERE a2.question_id = l.question_id)
                  AND l.cause = 'knowledge') AS k_cnt,

              (SELECT COUNT(*) FROM attempt l
                WHERE l.correct = FALSE
                  AND l.id = (SELECT MAX(a2.id) FROM attempt a2 WHERE a2.question_id = l.question_id)
                  AND l.cause = 'understanding') AS u_cnt,

              (SELECT COUNT(*) FROM attempt l
                WHERE l.correct = FALSE
                  AND l.id = (SELECT MAX(a2.id) FROM attempt a2 WHERE a2.question_id = l.question_id)
                  AND l.cause = 'careless') AS c_cnt
            """;

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            rs.next();
            int open = rs.getInt("open_cnt");
            int k = rs.getInt("k_cnt");
            int u = rs.getInt("u_cnt");
            int c = rs.getInt("c_cnt");

            return new GapSummary(open, rs.getInt("closed_cnt"), k, u, c, open - k - u - c);

        } catch (SQLException e) {
            System.err.println("穴の集計に失敗: " + e.getMessage());
            return new GapSummary(0, 0, 0, 0, 0, 0);
        }
    }
}
