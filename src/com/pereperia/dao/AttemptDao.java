package com.pereperia.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

import com.pereperia.entity.AccuracyStat;
import com.pereperia.entity.AttemptView;
import com.pereperia.entity.MissingNote;

public class AttemptDao {

    /** 次の周回を返す。未着手なら 1 */
    public static int nextRound(int questionId) {
        String sql = """
            SELECT COALESCE(MAX(a.round), 0) + 1
            FROM attempt a
            WHERE a.question_id = ?
            """;

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setInt(1, questionId);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("周回取得に失敗：" + e.getMessage());
            return 1;
        }
    }

    /** 解答結果を1件登録する。成功なら true */
    public static boolean insert(int questionId, int round, boolean correct, String note) {
        String sql = """
            INSERT INTO attempt (question_id, round, correct, answered_at, note)
            VALUES (?, ?, ?, ?, ?)
            """;

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, questionId);
            ps.setInt(2, round);
            ps.setBoolean(3, correct);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(5, note);

            return ps.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("記録に失敗： " + e.getMessage());
            return false;
        }
    }

        /** 不正解かつメモ未記入の記録を返す */
    public static List<MissingNote> findMissingNotes() {
        String sql = """
            SELECT a.id, q.chapter, q.number, a.round
            FROM attempt a
            JOIN question q ON a.question_id = q.id
            WHERE a.correct = FALSE
            AND (a.note IS NULL OR a.note = '')
            ORDER BY q.chapter, q.number, a.round
            """;

        List<MissingNote> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                list.add(new MissingNote(
                    rs.getInt("id"),
                    rs.getInt("chapter"),
                    rs.getInt("number"),
                    rs.getInt("round")
                ));
            }
        } catch (SQLException e) {
            System.err.println("取得に失敗： " + e.getMessage());
        }
        return list;
    }

    /** メモを後から更新する */
    public static boolean updateNote(int attemptId, String note) {
        String sql = "UPDATE attempt SET note = ? WHERE id = ?";

        try (Connection conn = DbManager.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, note);
            ps.setInt(2, attemptId);
            return ps.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("更新に失敗： " + e.getMessage());
            return false;
        }
    }

    /** 直近の記録を新しい順に返す */
    public static List<AttemptView> findRecent(int limit) {
        String sql = """
                SELECT a.id, q.chapter, q.number, a.round, a.correct, a.answered_at, a.note
                FROM attempt a
                JOIN question q ON a.question_id = q.id
                ORDER BY a.id DESC
                LIMIT ?
                """;

        List<AttemptView> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setInt(1, limit);

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
                            rs.getString("note")
                        ));
                    }
                }
            } catch (SQLException e) {
                System.err.println("取得に失敗： " + e.getMessage());
            }
            return list;
    }

    /** 重複している記録を返す（同じ問題・同じ周回が2件以上） */
    public static List<AttemptView> findDuplicates() {
        String sql = """
                
            SELECT a.id, q.chapter, q.number, a.round,a.correct, a.answered_at, a.note
            FROM  attempt a
            JOIN question q ON a.question_id = q.id
            WHERE (a.question_id, a.round) IN (
                SELECT question_id, round
                FROM attempt
                GROUP BY question_id, round
                HAVING COUNT(*) > 1
            )
            ORDER BY q.chapter, q.number, a.round, a.id
            """;
        List<AttemptView> list = new ArrayList<>();

        try (Connection conn= DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Timestamp ts = rs.getTimestamp("answered_at");

                list.add(new AttemptView(
                    rs.getInt("id"),
                    rs.getInt("chapter"),
                    rs.getInt("number"),
                    rs.getInt("round"),
                    rs.getBoolean("correct"),
                    ts == null ? null : ts.toLocalDateTime(),
                    rs.getString("note")
                ));
            }
        } catch (SQLException e) {
            System.err.println("取得に失敗： " + e.getMessage());
        }
        return list;
    }

    /** 記録を1件削除する。成功なら true */
    public static boolean delete(int attemptId) {
        String sql = "DELETE FROM attempt WHERE id = ?";

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
                ps.setInt(1, attemptId);
                return ps.executeUpdate() == 1;

        } catch (SQLException e) {
            System.err.println("削除に失敗： " + e.getMessage());
            return false;
        }
    }

    /** 6.章ごとの正答率を、低い順に返す */
    public static List<AccuracyStat> findAccuracyByChapter() {
        String sql = """
            SELECT q.chapter,
                   COUNT(*) AS attempts,
                   SUM(CASE WHEN a.correct THEN 1 ELSE 0 END) AS correct
            FROM attempt a
            JOIN question q ON a.question_id = q.id
            GROUP BY q.chapter
            ORDER BY SUM(CASE WHEN a.correct THEN 1 ELSE 0 END) * 1.0 / COUNT(*)    
            """;

        List<AccuracyStat> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                list.add(new AccuracyStat(
                    rs.getInt("chapter"),
                    rs.getInt("attempts"),
                    rs.getInt("correct")
                ));
            }
        } catch (SQLException e) {
            System.err.println("集計に失敗: " + e.getMessage());
        }
        return list;
    }

    /** 6.直近の回答が不正解の問題を、間違えた回数が多い順に返す */
    public static List<AttemptView> findWeakQuestions(int limit) {
        String sql = """
            SELECT a.id, q.chapter, q.number, a.round,
                   a.correct, a.answered_at, a.note
            FROM attempt a
            JOIN question q ON a.question_id = q.id
            WHERE a.round = (
                SELECT MAX(a2.round)
                FROM attempt a2
                WHERE a2.question_id = a.question_id
            )
            AND a.correct = FALSE
            ORDER BY q.chapter, q.number
            LIMIT ?
            """;

        List<AttemptView> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("answered_at");
                    list.add(new AttemptView(
                        rs.getInt("id"), rs.getInt("chapter"), rs.getInt("number"),
                        rs.getInt("round"), rs.getBoolean("correct"),
                        ts == null ? null : ts.toLocalDateTime(),
                        rs.getString("note")
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("取得に失敗: " + e.getMessage());
        }
        return list;
    }
}