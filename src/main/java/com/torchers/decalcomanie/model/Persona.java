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
    private String endingPatterns;
    private List<String> memories;      // 실제 대화에서 추출한 사건/기억
    private String mbti;               // 대화 패턴 기반 추정 MBTI
    private String systemPrompt;
}
