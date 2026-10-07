package com.pereperia.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import com.pereperia.entity.Question;
import com.pereperia.entity.QuestionStatus;
import com.pereperia.entity.ChapterStat;

public class QuestionDao {

    // 1.{章番号、その章の問題数}
    private static final Map<String, int[][]> BOOKS = Map.of(
        "kurohon", new int[][] {{1,9},{2,44},{3,43},{4,40},{5,26},{6,25},{7,60},{8,60}},
        "murasaki", new int[][] {{1,6},{2,15},{3,20},{4,16},{5,18},{6,24},{7,16}}   // 紫本の構成が分かったら埋める
    );

    public static void seed() {

        if (count() > 0){
            System.out.println("登録済みのためスキップします");
            return;
        }

        int[][] chapters = BOOKS.get(DbManager.currentBook());
        if (chapters == null) {
            System.out.println("教材 " + DbManager.currentBook() + " の構成が未登録です");
            return;
        }

        String sql = "INSERT INTO question (chapter, number) VALUES (?, ?)";

        try (Connection conn = DbManager.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {

            for(int[] ch : chapters) {
                int chapter = ch[0];
                int count = ch[1];

                for (int num = 1; num <= count; num++) {
                    ps.setInt(1, chapter);
                    ps.setInt(2, num);
                    ps.addBatch();
                }
            }
            int[] result = ps.executeBatch();
            System.out.println(result.length + " 件登録しました");

        } catch (SQLException e) {
            System.err.println("投入に失敗： " + e.getMessage());
        }
    }

    /** question テーブルの件数を返す */
    private static int count() {
        String sql = "SELECT COUNT(*) FROM question";

        try (Connection conn = DbManager.getConnection();
        PreparedStatement ps = conn.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {

            rs.next();
            return rs.getInt(1);

        } catch (SQLException e) {
            System.err.println("件数取得に失敗：　" + e.getMessage());
            return -1;
        }
    }

    /** 2.未着手の問題を返す */
    public static List<Question> findUnanswered(){
        String sql = """
            SELECT q.id, q.chapter, q.number, q.topic
            FROM question q
            LEFT JOIN attempt a ON q.id = a.question_id
            WHERE a.id IS NULL
            ORDER BY q.chapter, q.number
            """;

        List<Question> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                list.add(new Question(
                    rs.getInt("id"),
                    rs.getInt("chapter"),
                    rs.getInt("number"),
                    rs.getString("topic")
                ));
            }
        } catch (SQLException e) {
            System.err.println("取得に失敗："  + e.getMessage());
        }
        return list;
    }

    /** 章と問題番号から id を返す。なければ -1 */
    public static int findId(int chapter, int number) {
        String sql = "SELECT id FROM question WHERE chapter = ? AND number = ?";

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, chapter);
            ps.setInt(2, number);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
                return -1;
            }
        } catch (SQLException e) {
            System.err.println("検索に失敗: " + e.getMessage());
            return -1;
        }
    }

    /** 5.章ごとの進捗を表示する */
    public static List<ChapterStat> findChapterStats() {
        String sql = """
            SELECT q.chapter,
                   COUNT(*) AS total,
                   COUNT(DISTINCT a.question_id) AS answered
            FROM question q
            LEFT JOIN attempt a ON q.id = a.question_id
            GROUP BY q.chapter
            ORDER BY q.chapter
            """;

        List<ChapterStat> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    list.add(new ChapterStat(
                        rs.getInt("chapter"),
                        rs.getInt("total"),
                        rs.getInt("answered")
                    ));
                }
            } catch (SQLException e) {
                System.err.println("集計に失敗: " + e.getMessage());
            }
            return list;
    }

    /** 指定した章の問題を、最新の解答状況付きで返す */
    public static List<QuestionStatus> findByChapter(int chapter) {
        String sql = """
            SELECT q.id, q.chapter, q.number,
                a.round AS last_round,
                a.correct AS last_correct
            FROM question q
            LEFT JOIN attempt a
            ON a.question_id = q.id
            AND a.round = (
                SELECT MAX(a2.round)
                FROM attempt a2
                WHERE a2.question_id = q.id
                )
            WHERE q.chapter = ?
            ORDER BY q.number                
            """;

        List<QuestionStatus> list = new ArrayList<>();

        try (Connection conn = DbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
                ps.setInt(1, chapter);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int round = rs.getInt("last_round");
                        Integer lastRound = rs.wasNull() ? null : round;
                        
                        boolean correct = rs.getBoolean("last_correct");
                        Boolean lastCorrect = rs.wasNull() ? null : correct;

                        list.add(new QuestionStatus(
                            rs.getInt("id"),
                            rs.getInt("chapter"),
                            rs.getInt("number"),
                            lastRound,
                            lastCorrect
                        ));
                    }
                }
        } catch (SQLException e) {
            System.err.println("取得に失敗: " + e.getMessage());
        }
        return list;
    }
}