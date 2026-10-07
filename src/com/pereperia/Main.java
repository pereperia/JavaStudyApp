package com.pereperia;

import java.util.List;
import java.util.Scanner;
import java.util.Arrays;

import com.pereperia.dao.DbManager;
import com.pereperia.dao.QuestionDao;
import com.pereperia.dao.AttemptDao;
import com.pereperia.entity.Question;
import com.pereperia.web.WebServer;
import com.pereperia.entity.MissingNote;
import com.pereperia.entity.AccuracyStat;
import com.pereperia.entity.AttemptView;
import com.pereperia.entity.ChapterStat;

public class Main {

    public static void main(String[] args) throws Exception {

        // --book=murasaki の形で教材を指定する
        for (String arg : args) {
            if (arg.startsWith("--book=")) {
                DbManager.use(arg.substring("--book=".length()));
            }
        }
        
        DbManager.initialize();
        QuestionDao.seed();

        boolean web = Arrays.asList(args).contains("web");
        if (web) {
            WebServer.start();
            return;
        }

        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                System.out.println();
                System.out.println("1: 解答を記録  2: 未着手を表示  3: メモ未記入を補完  4: 記録の確認と削除  5: 章ごとの進捗  6: 苦手傾向  0: 終了");
                System.out.print("> ");

                String input = sc.nextLine().trim();

                switch (input) {
                    case "1" -> recordRange(sc);
                    case "2" -> showUnanswered();
                    case "3" -> fillMissingNotes(sc);
                    case "4" -> manageAttempts(sc);
                    case "5" -> showChapterStats();
                    case "6" -> showWeakness();
                    case "0" -> {
                        System.out.println("終了します");
                        return;
                    }
                    default -> System.out.println("1~6, 0のいずれかを入力してください");
                }
            }
        }
    }

    /** 1.解答を1件記録する */
    private static void recordRange(Scanner sc) {
        System.out.print("章: ");
        int chapter = Integer.parseInt(sc.nextLine().trim());

        System.out.print("範囲（例 1-10）: ");
        String[] parts = sc.nextLine().trim().split("-");
        int from = Integer.parseInt(parts[0].trim());
        int to = (parts.length > 1) ? Integer.parseInt(parts[1].trim()) : from;

        int size = to - from + 1;
        System.out.print("結果 " + size + "問（y=正解 n=不正解 -=未実施）: ");
        String results = sc.nextLine().trim();

        int ok = 0;
        int ng = 0;

        for (int i = 0; i < results.length() && i < size; i++) {
             char c = Character.toLowerCase(results.charAt(i));
            if (c != 'y' && c != 'n') {
                continue;
            }

            int number = from + i;
            int questionId = QuestionDao.findId(chapter, number);
            if (questionId < 0) {
                System.out.println(chapter + "-" + number + " は存在しません");
                continue;
            }

            boolean correct = (c == 'y');
            String note = null;

            if (!correct) {
                System.out.print(chapter + "-" + number + "のメモ: ");
                String s = sc.nextLine().trim();
                if (!s.isEmpty()) {
                    note = s;
                }
            }

            if (AttemptDao.insert(questionId, nextRoundOf(questionId), correct, note)) {
                if (correct){
                    ok++;
                } else {
                    ng++;
                }
            }
        }
        System.out.println(ok + ng + " 件記録（正解 " + ok + " / 不正解 " + ng + ") ");
    }

    private static int nextRoundOf(int questionId) {
        return AttemptDao.nextRound(questionId);
    }

    /** 4.記録を確認して削除する */
    private static void manageAttempts(Scanner sc) {
        System.out.print("1: 直近20件  2: 重複のみ  > ");
        String mode = sc.nextLine().trim();

        List<AttemptView> list = switch (mode) {
            case "1" -> AttemptDao.findRecent(20);
            case "2" -> AttemptDao.findDuplicates();
            default -> List.of();
        };

        if (list.isEmpty()) {
            System.out.println("該当する記録はありません");
            return;
        }

        list.forEach(System.out::println);
        System.out.print("削除するID（カンマ区切り、空Enterで戻る）: ");
        String input = sc.nextLine().trim();

        if (input.isEmpty()) {
            return;
        }

        int deleted = 0;
        for (String s : input.split(",")) {
            try {
                int id = Integer.parseInt(s.trim());
                if (AttemptDao.delete(id)) {
                    deleted++;
                } else {
                    System.out.println("ID " + id + " は見つかりません");
                }
            } catch (NumberFormatException e) {
                System.out.println("数値ではありません: " + s);
            }
        }
        System.out.println(deleted + " 件削除しました");
    }

    /** 3.不正解かつメモ未記入の記録を表示し、その場で追記する */
    private static void fillMissingNotes(Scanner sc) {
        List<MissingNote> list = AttemptDao.findMissingNotes();

        if (list.isEmpty()) {
            System.out.println("メモ未記入の不正解はありません");
            return;
        }

        System.out.println("メモ未記入の不正解: " + list.size() + " 件");
        list.forEach(System.out::println);

        System.out.print("追記しますか (y/n): ");
        if (!sc.nextLine().trim().equalsIgnoreCase("y")) {
            return;
        }

        int updated = 0;
        for (MissingNote m : list) {
            System.out.print(m.chapter() + "-" + m.number()
                    + " のメモ（空Enterで飛ばす、q で中断）: ");
            String input = sc.nextLine().trim();

            if (input.equalsIgnoreCase("q")) {
                break;
            }
            if (input.isEmpty()) {
                continue;
            }
            if (AttemptDao.updateNote(m.attemptId(), input)) {
                updated++;
            }
        }
        System.out.println(updated + " 件更新しました");
    }


    /** 2.未着手を章ごとの件数で表示する */
    private static void showUnanswered() {
        List<Question> list = QuestionDao.findUnanswered();
        System.out.println("未着手: " + list.size() + " 件");

        list.stream().limit(10).forEach(System.out::println);
        if (list.size() > 10) {
            System.out.println("... 他 " + (list.size() -10) + "件");
        }
    }
    
    /** 5.章ごとの進捗を表示する */
    private static void showChapterStats() {
        List<ChapterStat> list = QuestionDao.findChapterStats();

        list.forEach(System.out::println);

        int total = list.stream().mapToInt(ChapterStat::total).sum();
        int answered = list.stream().mapToInt(ChapterStat::answered).sum();

        System.out.println("─".repeat(40));
        System.out.printf("合計 %d/%d問  未着手 %d問%n",
            answered, total, total - answered);
    }

    /** 6.苦手傾向を表示する */
    private static void showWeakness() {
        List<AccuracyStat> stats = AttemptDao.findAccuracyByChapter();

        if (stats.isEmpty()) {
            System.out.println("まだ記録がありません");
            return;
        }

        System.out.println("【章ごとの正答率:低い順】");
        stats.forEach(System.out::println);

        List<AttemptView> weak = AttemptDao.findWeakQuestions(20);
        System.out.println();
        System.out.println("【要復習:最新が不正解】 " + weak.size() + " 件");
        weak.forEach(System.out::println);
    }
}