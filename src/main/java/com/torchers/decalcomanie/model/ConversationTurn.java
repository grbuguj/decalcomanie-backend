package com.torchers.decalcomanie.model;

import lombok.Data;

@Data
public class ConversationTurn {
    private String sender;
    private String message;
    private String date; // "2024년 3월 1일" — nullable

    public ConversationTurn(String sender, String message) {
        this.sender = sender;
        this.message = message;
    }

    public ConversationTurn(String sender, String message, String date) {
        this.sender = sender;
        this.message = message;
        this.date = date;
    }
}
