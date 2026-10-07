package com.pereperia.entity;

public record AccuracyStat(int chapter, int attempts, int correct) {

    /** 正答率（0.0~1.0） */
    public double accuracy(){
        return attempts == 0 ? 0 : (double) correct / attempts;
    }

    @ Override
    public String toString(){
        int bars = (int) Math.round(accuracy() * 10);
        String gauge = "█".repeat(bars) + "░".repeat(10 - bars);

        return String.format("%d章 %s %3.0f%% %2d/%2d",
            chapter, gauge, accuracy() * 100, correct, attempts);
    }
}
