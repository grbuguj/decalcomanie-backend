package com.torchers.decalcomanie.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConversationTurn {
    private String sender;
    private String message;
}
