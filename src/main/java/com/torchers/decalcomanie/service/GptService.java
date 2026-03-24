package com.torchers.decalcomanie.service;

import com.torchers.decalcomanie.model.ChatMessage;
import com.torchers.decalcomanie.model.Persona;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class GptService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.model}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();

    private String getApiUrl() {
        return "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;
    }

    // Gemini API 공통 호출
    private String callGemini(String systemPrompt, List<Map<String, Object>> contents, double temperature, int maxTokens) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();

        // system instruction
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system_instruction", Map.of(
                "parts", List.of(Map.of("text", systemPrompt))
            ));
        }

        body.put("contents", contents);
        body.put("generationConfig", Map.of(
            "temperature", temperature,
            "maxOutputTokens", maxTokens
        ));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(getApiUrl(), request, Map.class);

        Map responseBody = response.getBody();
        if (responseBody == null) throw new RuntimeException("Gemini 응답 없음");

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            // 안전 필터 차단 시 promptFeedback 확인
            Object feedback = responseBody.get("promptFeedback");
            throw new RuntimeException("Gemini 응답 차단됨: " + feedback);
        }

        Map<String, Object> candidate = candidates.get(0);
        String finishReason = (String) candidate.get("finishReason");

        Map<String, Object> content = (Map<String, Object>) candidate.get("content");
        if (content == null) throw new RuntimeException("Gemini content null (finishReason: " + finishReason + ")");

        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new RuntimeException("Gemini parts 비어있음 (finishReason: " + finishReason + ", content: " + content + ")");
        }

        // thinking 모드: text가 있는 part만 찾기
        for (Map<String, Object> part : parts) {
            if (part.containsKey("text")) {
                return (String) part.get("text");
            }
        }
        throw new RuntimeException("Gemini text part 없음 (parts: " + parts + ")");
    }

    // 기억 추출 전용 호출
    public List<String> extractMemories(String name, List<String> candidates) {
        String candidateText = candidates.stream()
            .map(c -> "- " + c)
            .collect(Collectors.joining("\n"));
        if (candidateText.length() > 5000) {
            candidateText = candidateText.substring(0, 5000) + "\n(이하 생략)";
        }

        String prompt = String.format("""
            [대화 발췌]
            %s

            위 대화에서 '%s'에 대한 사실만 추출해.

            출력 형식 (반드시 지켜):
            - 설명 문장 없이 바로 사실만 나열
            - 한 줄에 하나씩, 줄바꿈으로 구분
            - "~했음", "~인 것 같음" 형태로 끝냄
            - 번호, 제목, 머리말 절대 없음
            - 최대 15개

            규칙:
            - 오직 '%s'가 한 말만 분석, 다른 사람 말은 맥락용
            - 최근 사건 우선
            - 구체적 사건(언제, 무엇을) 위주
            """, candidateText, name, name);

        List<Map<String, Object>> contents = List.of(
            Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))
        );

        try {
            String result = callGemini(null, contents, 0.3, 1000);
            return Arrays.stream(result.split("\n"))
                .map(String::trim)
                .map(s -> s.replaceAll("^[*\\-•·]\\s*", ""))  // 마크다운 bullet 제거
                .filter(s -> !s.isEmpty())
                .toList();
        } catch (Exception e) {
            return List.of("기억 추출 실패: " + e.getMessage());
        }
    }

    public String chat(Persona persona, List<ChatMessage> history, String userMessage) {
        // 대화 히스토리 구성 (최근 20개)
        List<ChatMessage> recent = history.size() > 20
            ? history.subList(history.size() - 20, history.size())
            : history;

        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatMessage msg : recent) {
            // OpenAI "assistant" → Gemini "model"
            String role = msg.getRole().equals("assistant") ? "model" : "user";
            contents.add(Map.of(
                "role", role,
                "parts", List.of(Map.of("text", msg.getContent()))
            ));
        }

        // 현재 사용자 메시지
        contents.add(Map.of(
            "role", "user",
            "parts", List.of(Map.of("text", userMessage))
        ));

        try {
            return callGemini(persona.getSystemPrompt(), contents, 0.75, 800);
        } catch (Exception e) {
            throw new RuntimeException("Gemini API 호출 실패: " + e.getMessage());
        }
    }
}
