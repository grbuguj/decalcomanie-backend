package com.torchers.decalcomanie.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class Persona {
    private String name;
    private String speechStyle;
    private String avgMessageLength;
    private List<String> commonPhrases;
    private String endingPatterns;
    private String endingStyle;
    private String typingHabits;
    private String burstPattern;
    private List<String> topics;
    private List<String> memories;
    private String mbti;
    // 주제별 대화쌍 인덱스 (RAG용) — 분석 시점에 미리 구축
    private Map<String, List<String>> topicExchanges;
    private String systemPrompt;
}
