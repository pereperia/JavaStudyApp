package com.pereperia.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.pereperia.dao.AttemptDao;
import com.pereperia.dao.DbManager;
import com.pereperia.dao.GapDao;
import com.pereperia.dao.WebDao;
import com.pereperia.entity.AttemptView;

/**
 * 学習の進捗を Markdown にまとめて progress.md に書き出す。
 *
 * 単体で動かす:
 *   java -cp "bin:lib/h2-2.2.224.jar" com.pereperia.report.ProgressReport
 *
 * Web サーバーが起動していると H2 のファイルロックで失敗するので、
 * 実行前に止めること。
 */
public class ProgressReport {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 出力先。Docker では data/progress.md にしてホストから見えるようにする */
    private static final Path OUT = Path.of(
            System.getenv().getOrDefault("PROGRESS_OUT", "progress.md"));

    public static void main(String[] args) throws IOException {
        StringBuilder md = new StringBuilder();

        md.append("# kurohon-tracker 学習進捗\n\n")
          .append("最終更新: ").append(LocalDateTime.now().format(STAMP)).append("\n\n")
          .append("このファイルは `ProgressReport` が自動生成しています。手で編集しないでください。\n");

        for (String book : WebDao.listBooks()) {
            DbManager.use(book);
            appendBook(md, book);
        }

        Files.writeString(OUT, md.toString(), StandardCharsets.UTF_8);
        System.out.println("書き出しました → " + OUT.toAbsolutePath());
    }

    private static void appendBook(StringBuilder md, String book) {
        WebDao.Summary s = WebDao.findSummary();

        // 問題が 1 件も無い DB は載せない
        if (s.totalQuestions() == 0) {
            return;
        }

        md.append("\n---\n\n## ").append(label(book))
          .append("（`").append(book).append("`）\n\n");

        double accuracy = s.attempts() == 0 ? 0 : (double) s.correct() / s.attempts() * 100;

        md.append("| 項目 | 値 |\n|---|---|\n")
          .append("| 総問題数 | ").append(s.totalQuestions()).append(" |\n")
          .append("| 着手済み | ").append(s.answeredQuestions())
          .append(" (").append(pct(s.answeredQuestions(), s.totalQuestions())).append(") |\n")
          .append("| 未着手 | ").append(s.totalQuestions() - s.answeredQuestions()).append(" |\n")
          .append("| 解答回数 | ").append(s.attempts()).append(" |\n")
          .append("| 正答率 | ").append(String.format("%.1f%%", accuracy))
          .append("（").append(s.correct()).append(" / ").append(s.attempts()).append("） |\n\n");

        appendGaps(md);
        appendChapters(md);
        appendTopics(md);
        appendHistory(md);
        appendWeak(md);
    }

    private static void appendGaps(StringBuilder md) {
        GapDao.GapSummary g = GapDao.summarize();

        if (g.open() == 0 && g.closed() == 0) {
            return;
        }

        md.append("### 穴の状況\n\n")
          .append("| 状態 | 問題数 |\n|---|---:|\n")
          .append("| 残っている穴 | ").append(g.open()).append(" |\n")
          .append("| 埋まった穴 | ").append(g.closed()).append(" |\n\n");

        if (g.open() > 0) {
            md.append("原因の内訳: 知識 ").append(g.knowledge())
              .append(" / 理解 ").append(g.understanding())
              .append(" / ケアレスミス ").append(g.careless())
              .append(" / 未分類 ").append(g.unset()).append("\n\n");
        }

        List<GapDao.Gap> list = GapDao.findOpen(LocalDate.now(), 10);
        if (!list.isEmpty()) {
            md.append("再テストが近い穴（上位 ").append(list.size()).append(" 件）\n\n")
              .append("| 問題 | 原因 | 再テスト日 | メモ |\n|---|---|---|---|\n");

            for (GapDao.Gap gap : list) {
                md.append("| 第").append(gap.chapter()).append("章 ").append(gap.number()).append("番 | ")
                  .append(causeLabel(gap.cause())).append(" | ")
                  .append(gap.nextDue() == null ? "未設定" : gap.nextDue())
                  .append(gap.overdueDays() > 0 ? "（" + gap.overdueDays() + "日遅れ）" : "")
                  .append(" | ")
                  .append(gap.note() == null ? "" : gap.note().replace("\n", " ").replace("|", "\\|"))
                  .append(" |\n");
            }
            md.append("\n");
        }
    }

    private static String causeLabel(String cause) {
        if (cause == null) {
            return "未分類";
        }
        return switch (cause) {
            case "knowledge" -> "知識";
            case "understanding" -> "理解";
            case "careless" -> "ケアレスミス";
            default -> cause;
        };
    }

    private static void appendChapters(StringBuilder md) {
        List<WebDao.ChapterProgress> list = WebDao.findChapterProgress();
        if (list.isEmpty()) {
            return;
        }

        md.append("### 章ごとの状況\n\n")
          .append("| 章 | 問題数 | 正解 | 不正解 | 未着手 |\n|---|---|---|---|---|\n");

        for (WebDao.ChapterProgress c : list) {
            md.append("| 第").append(c.chapter()).append("章 | ")
              .append(c.total()).append(" | ")
              .append(c.ok()).append(" | ")
              .append(c.ng()).append(" | ")
              .append(c.untouched()).append(" |\n");
        }
        md.append("\n※ 正解・不正解は「最新の解答がどちらだったか」で数えています。\n\n");
    }

    private static void appendTopics(StringBuilder md) {
        List<WebDao.TopicStat> list = WebDao.findTopicStats();
        if (list.isEmpty()) {
            return;
        }

        md.append("### 分野ごとの成績（正答率の低い順）\n\n")
          .append("| 分野 | 問題数 | 正解 | 不正解 |\n|---|---|---|---|\n");

        for (WebDao.TopicStat t : list) {
            md.append("| ").append(t.topic()).append(" | ")
              .append(t.total()).append(" | ")
              .append(t.ok()).append(" | ")
              .append(t.ng()).append(" |\n");
        }
        md.append("\n");
    }

    private static void appendHistory(StringBuilder md) {
        List<WebDao.DayCount> days = WebDao.findDailyHistory(30);
        if (days.isEmpty()) {
            md.append("### 直近の学習\n\n過去 30 日の記録はありません。\n\n");
            return;
        }

        md.append("### 直近 30 日の学習\n\n")
          .append("学習した日: ").append(days.size()).append(" 日");

        WebDao.DayCount last = days.get(days.size() - 1);
        long since = LocalDate.now().toEpochDay() - LocalDate.parse(last.date()).toEpochDay();
        md.append(" / 最後に解いた日: ").append(last.date());
        if (since == 0) {
            md.append("（今日）");
        } else {
            md.append("（").append(since).append(" 日前）");
        }
        md.append("\n\n| 日付 | 解答数 | 正解 |\n|---|---|---|\n");

        int from = Math.max(0, days.size() - 10);
        for (WebDao.DayCount d : days.subList(from, days.size())) {
            md.append("| ").append(d.date()).append(" | ")
              .append(d.total()).append(" | ").append(d.correct()).append(" |\n");
        }
        md.append("\n");
    }

    private static void appendWeak(StringBuilder md) {
        List<AttemptView> weak = AttemptDao.findWeakQuestions(15);
        if (weak.isEmpty()) {
            return;
        }

        md.append("### 直近が不正解の問題\n\n");
        for (AttemptView a : weak) {
            md.append("- 第").append(a.chapter()).append("章 ")
              .append(a.number()).append("番（").append(a.round()).append("周目）");
            if (a.note() != null && !a.note().isBlank()) {
                md.append(" — ").append(a.note().replace("\n", " "));
            }
            md.append("\n");
        }
        md.append("\n");
    }

    private static String pct(int part, int total) {
        return total == 0 ? "0%" : String.format("%.1f%%", (double) part / total * 100);
    }

    private static String label(String book) {
        return switch (book) {
            case "kurohon" -> "黒本";
            case "murasaki" -> "紫本";
            default -> book;
        };
    }
}
