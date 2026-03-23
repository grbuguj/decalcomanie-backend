package com.torchers.decalcomanie.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SessionData {
    private Persona persona;

    @Builder.Default
    private List<ChatMessage> history = new ArrayList<>();
}
