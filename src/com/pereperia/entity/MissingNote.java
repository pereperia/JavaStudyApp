package com.pereperia.entity;

public record MissingNote(int attemptId, int chapter, int number, int round) {
    @Override
    public String toString() {
        return String.format("[%d] %d -%d (%d周目)", attemptId, chapter, number, round);
    }
}