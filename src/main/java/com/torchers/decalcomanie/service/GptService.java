package com.torchers.decalcomanie.service;

import com.torchers.decalcomanie.model.ChatMessage;
import com.torchers.decalcomanie.model.ConversationTurn;
import com.torchers.decalcomanie.model.Persona;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        boolean isGemini = model != null && model.toLowerCase().contains("gemini");
        String prompt = isGemini
            ? buildGeminiMemoryPrompt(name, candidateText)
            : buildGptMemoryPrompt(name, candidateText);

        List<Map<String, Object>> contents = List.of(
            Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))
        );

        try {
            String result = callGemini(null, contents, 0.3, 2000);
            return Arrays.stream(result.split("\n"))
                .map(String::trim)
                .map(s -> s.replaceAll("^[*\\-•·\\d\\.]+\\s*", ""))  // 마크다운 bullet, 번호 제거
                .filter(s -> !s.isEmpty())
                .filter(s -> s.length() > 3)
                .toList();
        } catch (Exception e) {
            return List.of("기억 추출 실패: " + e.getMessage());
        }
    }

    private String buildGptMemoryPrompt(String name, String candidateText) {
        return String.format("""
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
    }

    private String buildGeminiMemoryPrompt(String name, String candidateText) {
        return String.format("""
            아래 카카오톡 대화에서 '%s'에 대한 사실을 뽑아줘.

            대화:
            %s

            지시사항:
            1. '%s'가 직접 한 말에서만 사실 추출 (다른 사람 말은 맥락 참고용)
            2. 한 줄에 하나씩만 써
            3. 각 줄은 반드시 "~했음" 또는 "~인 것 같음"으로 끝내
            4. 첫 줄부터 바로 사실 나열 (인트로, 제목, 번호 없이)
            5. 최대 15줄
            6. 구체적으로: 장소, 사람, 사건 포함

            출력 예시:
            시험 기간이라 공부 중이었음
            친구랑 카페에서 만났음
            취업 준비 중인 것 같음
            """, name, candidateText, name);
    }

    public String greet(Persona persona) {
        List<Map<String, Object>> contents = List.of(
            Map.of("role", "user", "parts", List.of(Map.of("text",
                "대화 시작. 상대방에게 먼저 짧게 한두 마디로 자연스럽게 말 걸어. 그 사람 말투 그대로.")))
        );
        try {
            return callGemini(persona.getSystemPrompt(), contents, 0.85, 1200);
        } catch (Exception e) {
            return "ㅇㅇ";
        }
    }

    public String chat(Persona persona, List<ChatMessage> history, String userMessage,
                       List<ConversationTurn> allTurns) {
        List<ChatMessage> recent = history.size() > 20
            ? history.subList(history.size() - 20, history.size())
            : history;

        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatMessage msg : recent) {
            String role = msg.getRole().equals("assistant") ? "model" : "user";
            contents.add(Map.of(
                "role", role,
                "parts", List.of(Map.of("text", msg.getContent()))
            ));
        }
        contents.add(Map.of(
            "role", "user",
            "parts", List.of(Map.of("text", userMessage))
        ));

        // RAG: 현재 메시지 키워드로 관련 과거 대화 검색 → 시스템 프롬프트에 동적 주입
        String systemPrompt = persona.getSystemPrompt();
        if (allTurns != null && !allTurns.isEmpty()) {
            String ragContext = buildRagContext(persona.getName(), userMessage, allTurns);
            if (!ragContext.isBlank()) {
                systemPrompt += "\n\n[지금 대화와 관련된 과거 실제 대화 — 자연스럽게 참고해]\n"
                    + ragContext;
            }
        }

        try {
            return callGemini(systemPrompt, contents, 0.75, 1200);
        } catch (Exception e) {
            throw new RuntimeException("Gemini API 호출 실패: " + e.getMessage());
        }
    }

    // ── RAG: 키워드 기반 과거 대화 검색 ───────────────────

    private String buildRagContext(String name, String userMessage, List<ConversationTurn> turns) {
        List<String> keywords = extractKeywords(userMessage);
        if (keywords.isEmpty()) return "";

        // 각 turn에 키워드 점수 매기기
        Map<Integer, Integer> scores = new HashMap<>();
        for (int i = 0; i < turns.size(); i++) {
            String msg = turns.get(i).getMessage();
            int score = 0;
            for (String kw : keywords) {
                if (msg.contains(kw)) score++;
            }
            if (score > 0) scores.put(i, score);
        }

        if (scores.isEmpty()) return "";

        // 점수 높은 top 3, ±2턴 컨텍스트 붙여서 반환
        return scores.entrySet().stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
            .limit(3)
            .map(e -> {
                int idx = e.getKey();
                StringBuilder ctx = new StringBuilder();
                for (int j = Math.max(0, idx - 2); j <= Math.min(turns.size() - 1, idx + 2); j++) {
                    ConversationTurn t = turns.get(j);
                    String msg = t.getMessage().length() > 60
                        ? t.getMessage().substring(0, 60) + ".."
                        : t.getMessage();
                    ctx.append(t.getSender()).append(": ").append(msg).append("\n");
                }
                return ctx.toString().trim();
            })
            .collect(java.util.stream.Collectors.joining("\n---\n"));
    }

    private static final Set<String> STOP_WORDS = Set.of(
        "이야", "나는", "우리", "그게", "이게", "뭐야", "어디", "지금", "그냥",
        "진짜", "이거", "그거", "이런", "그런", "어떤", "되게", "너무", "정말",
        "있어", "없어", "해줘", "할게", "한거", "하는", "하고", "해서", "했어",
        "근데", "그리고", "그래서", "하지만", "아니", "맞아", "그럼", "그래"
    );

    private List<String> extractKeywords(String text) {
        List<String> words = new ArrayList<>();
        Matcher m = Pattern.compile("[가-힣]{2,}").matcher(text);
        while (m.find()) {
            String w = m.group();
            if (!STOP_WORDS.contains(w)) words.add(w);
        }
        return words;
    }
}
