package com.pereperia.entity;

public record ChapterStat(int chapter, int total, int answered) {
    
    /** 未着手の問題数 */
    public int unanswered() {
        return total - answered;
    }

    /** 着手率（0.0~1.0) */
    public double progress() {
        return total == 0 ? 0 : (double) answered / total;
    }

    @Override
    public String toString() {
        int bars = (int) Math.round(progress() * 10);
        String gauge = "█".repeat(bars) + "░".repeat(10 - bars);

        return String.format("%d章 %s %3.0f%%  %2d/%2d問  未着手 %2d問",
            chapter, gauge, progress() * 100, answered, total, unanswered());
    }
}
