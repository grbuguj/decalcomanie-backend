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

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.url}")
    private String apiUrl;

    @Value("${openai.api.model}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();

    // 기억 추출 전용 GPT 호출
    public List<String> extractMemories(String name, List<String> candidates) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // 전체 후보 텍스트가 3000자 초과 시 잘라냄 (토큰 한도 방지)
        String candidateText = candidates.stream()
            .map(c -> "- " + c)
            .collect(Collectors.joining("\n"));
        if (candidateText.length() > 5000) {
            candidateText = candidateText.substring(0, 5000) + "\n(이하 생략)";
        }

        String prompt = String.format("""
            아래는 카카오톡 대화 발췌야. 각 줄에 "발화자: 내용" 형식으로 누가 말했는지 표시되어 있어.
            이 중에서 오직 '%s'가 한 말과 '%s'에 관한 상황만 분석해서
            '%s'에 대해 알 수 있는 사실을 최대 15개 이내로 정리해줘.

            주의:
            - 다른 사람이 한 말은 맥락 파악용으로만 참고하고, '%s'의 사실로 기록하지 마
            - 발췌 순서상 뒤쪽(최근)에 등장하는 사건일수록 더 중요하게 다뤄줘
            - 실제 있었던 구체적인 사건(언제, 무엇을 했는지)을 우선적으로 기록해줘
            - 성격, 관심사, 말버릇, 관계도 포함
            - 각 항목은 한 문장으로 간결하게
            - 번호 없이 줄바꿈으로 구분
            - 불확실한 건 "~한 것 같음" 형태로

            [대화 발췌]
            %s
            """, name, name, name, name, candidateText);

        List<Map<String, String>> messages = List.of(
            Map.of("role", "user", "content", prompt)
        );

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", 600);
        body.put("temperature", 0.3);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, request, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");

            return Arrays.stream(content.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        } catch (Exception e) {
            return List.of("기억 추출 실패: " + e.getMessage());
        }
    }

    public String chat(Persona persona, List<ChatMessage> history, String userMessage) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        List<Map<String, String>> messages = new ArrayList<>();

        // system 프롬프트
        messages.add(Map.of("role", "system", "content", persona.getSystemPrompt()));

        // 대화 히스토리 (최근 20개)
        List<ChatMessage> recent = history.size() > 20
            ? history.subList(history.size() - 20, history.size())
            : history;
        for (ChatMessage msg : recent) {
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }

        // 현재 메시지
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", 200);
        body.put("temperature", 0.75);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, request, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            Map<String, Object> choice = choices.get(0);
            Map<String, String> message = (Map<String, String>) choice.get("message");
            return message.get("content");
        } catch (Exception e) {
            throw new RuntimeException("GPT API 호출 실패: " + e.getMessage());
        }
    }
}
