package com.pereperia.entity;

public record QuestionStatus (int questionId, int chapter, int number,
                              Integer lastRound, Boolean lastCorrect) {
    
    /** 一度も解いていないか */
    public boolean inUnanswered() {
        return lastRound == null;
    }
}
    

