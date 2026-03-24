package com.torchers.decalcomanie.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class Persona {
    private String name;
    private String speechStyle;
    private String avgMessageLength;
    private List<String> commonPhrases;
    private String endingPatterns;      // 짧은 태그용 (기존)
    private String endingStyle;         // 종결어미 상세 분포 (~ㄴ데 45번, ...)
    private String typingHabits;        // 오타/축약/ㅋ 패턴
    private String burstPattern;        // 연속 메시지 습관
    private List<String> topics;        // 자주 언급하는 주제
    private List<String> memories;
    private String mbti;
    private String systemPrompt;
}
