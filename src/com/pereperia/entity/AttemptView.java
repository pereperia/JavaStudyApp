package com.pereperia.entity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public record AttemptView(int attemptId, int chapter, int number, int round, boolean correct, LocalDateTime answerdAt, String note) {

    private static final DateTimeFormatter FMT =
    DateTimeFormatter.ofPattern("MM/dd HH:mm");

    @Override public String toString() {
        return String.format("[%d] %d-%d %d周目 %s %s %s",
            attemptId, chapter, number, round,
            correct ? "〇" : "×",
            answerdAt == null ? "----" :answerdAt.format(FMT),
            note == null ? "" : note );
    }
}