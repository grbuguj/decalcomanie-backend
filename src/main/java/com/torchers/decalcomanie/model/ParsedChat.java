package com.torchers.decalcomanie.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ParsedChat {
    private String sessionId;
    private List<String> participants;
    private Map<String, List<String>> messagesByParticipant;
    private List<ConversationTurn> orderedTurns; // 시간순 전체 대화
}
