package com.torchers.decalcomanie.service;

import com.torchers.decalcomanie.model.ChatMessage;
import com.torchers.decalcomanie.model.ConversationTurn;
import com.torchers.decalcomanie.model.Persona;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
            String result = callGemini(null, contents, 0.3, 3000);
            return Arrays.stream(result.split("\n"))
                .map(String::trim)
                .map(s -> s.replaceAll("^[*\\-•·\\d\\.\\[\\]]+\\s*", ""))  // 마크다운 + 카테고리 헤더 제거
                .map(s -> s.replaceAll("^(종류\\d.*|카테고리\\d.*)$", ""))   // 카테고리 제목 줄 제거
                .filter(s -> !s.isEmpty())
                .filter(s -> s.length() >= 10)          // 너무 짧은 것 제거 (단편 잘림 방지)
                .filter(s -> !s.matches(".*[년월일(\\d]+$"))  // 날짜 mid-sentence 잘림 제거
                .filter(s -> s.contains("했") || s.contains("함") || s.contains("임") || s.contains("됨")
                          || s.contains("는") || s.contains("인") || s.contains("편") || s.contains("있"))
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
        String today = LocalDate.now(KST).format(DateTimeFormatter.ofPattern("yyyy년 M월 d일"));
        return String.format("""
            카카오톡 대화에서 '%s'에 대한 기억을 뽑아줘. 오늘은 %s야.

            대화:
            %s

            아래 3종류를 합쳐서 총 15개 이내로 뽑아. 단, 2년 이상 된 오래된 일은 제외해. 최근 1-2년 이내 것만.

            종류1 — 구체적 사건/경험
            (언제, 어디서, 뭘 했는지. [날짜] 있으면 "작년에", "몇 달 전에" 같은 표현 포함)
            예: 작년 겨울에 시험 준비로 한 달 내내 힘들어했음
            예: 2개월 전에 친구들이랑 홍대 맛집 다녀왔음

            종류2 — 이 사람만의 습관/특징
            (반복적으로 보이는 행동 패턴이나 성격)
            예: 새벽에 갑자기 연락 오는 편임
            예: 계획 세워두고 자주 취소하는 경향 있음

            종류3 — 둘 사이 에피소드
            (대화 상대와 있었던 특별한 일)
            예: 같이 여행 계획 짰다가 취소된 적 있음

            규칙:
            - '%s'가 직접 한 말 기준으로만 (상대방 말은 맥락)
            - 첫 줄부터 바로 내용 (제목, 번호, 기호 없이)
            - 한 줄에 하나씩, "~했음" / "~함" 형태로 끝
            """, name, today, candidateText, name);
    }

    public String greet(Persona persona, String nickname) {
        String systemPrompt = persona.getSystemPrompt()
            + "\n\n" + buildTimeContext();
        if (nickname != null && !nickname.isBlank()) {
            systemPrompt += "\n상대방 이름은 '" + nickname + "'이야. 이름은 거의 부르지 마. 진짜 필요할 때만.";
        }
        List<Map<String, Object>> contents = List.of(
            Map.of("role", "user", "parts", List.of(Map.of("text",
                "대화 시작. 상대방에게 먼저 짧게 한두 마디로 자연스럽게 말 걸어. 그 사람 말투 그대로.")))
        );
        try {
            return callGemini(systemPrompt, contents, 0.85, 1200);
        } catch (Exception e) {
            return "ㅇㅇ";
        }
    }

    public String chat(Persona persona, List<ChatMessage> history, String userMessage, String nickname) {
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

        // 날짜/시간 + 닉네임 → 시스템 프롬프트에 추가
        String systemPrompt = persona.getSystemPrompt()
            + "\n\n" + buildTimeContext();
        if (nickname != null && !nickname.isBlank()) {
            systemPrompt += "\n상대방 이름은 '" + nickname + "'이야. 이름은 거의 부르지 마. 진짜 필요할 때(강조, 놀람, 오해 풀 때)만 가끔 써.";
        }

        // 주제별 RAG: 미리 인덱싱된 topic exchanges에서 관련 대화 주입
        if (persona.getTopicExchanges() != null && !persona.getTopicExchanges().isEmpty()) {
            String topicCtx = buildTopicContext(userMessage, persona.getTopicExchanges());
            if (!topicCtx.isBlank()) {
                systemPrompt += "\n\n[과거 대화 참고 — 맥락 파악용. 이 내용을 직접 꺼내거나 언급하지 마. 상대가 먼저 꺼낼 때만 반응.]\n" + topicCtx;
            }
        }

        try {
            return callGemini(systemPrompt, contents, 0.75, 1200);
        } catch (Exception e) {
            throw new RuntimeException("Gemini API 호출 실패: " + e.getMessage());
        }
    }

    // ── 현재 날짜/시간 컨텍스트 생성 ─────────────────────

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private String buildTimeContext() {
        String today = LocalDate.now(KST).format(DateTimeFormatter.ofPattern("yyyy년 M월 d일"));
        LocalTime now = LocalTime.now(KST);
        int hour = now.getHour();
        String ampm = hour < 12 ? "오전" : "오후";
        int displayHour = hour % 12 == 0 ? 12 : hour % 12;
        String currentTime = ampm + " " + displayHour + "시";
        String timeSlot = hour < 5 ? "새벽" : hour < 9 ? "아침" : hour < 12 ? "오전"
                        : hour < 14 ? "점심" : hour < 18 ? "오후" : hour < 21 ? "저녁" : "밤";

        return String.format(
            "현재: %s %s(%s). 이 시간대를 자연스럽게 인식하고 있어. 기억 속 날짜는 오늘 기준으로 '작년에', '저번 달에' 등으로 말해.",
            today, currentTime, timeSlot
        );
    }

    // ── 주제 기반 RAG (분석 시점에 미리 인덱싱된 exchanges 활용) ──────

    private static final Map<String, String[]> TOPIC_KEYWORDS = new LinkedHashMap<>() {{
        put("음식",    new String[]{"밥", "먹", "식당", "카페", "커피", "치킨", "라면", "배고", "맛있", "배달"});
        put("공부/학교", new String[]{"시험", "과제", "수업", "학교", "공부", "성적", "교수"});
        put("일/직장", new String[]{"회사", "업무", "퇴근", "출근", "야근", "회의"});
        put("운동",    new String[]{"운동", "헬스", "달리기", "다이어트", "탁구", "축구"});
        put("여행",    new String[]{"여행", "갔다", "다녀왔", "놀러"});
        put("게임",    new String[]{"게임", "롤", "배그", "스팀"});
        put("건강",    new String[]{"아프", "병원", "약", "감기", "열나"});
        put("연애",    new String[]{"좋아", "사귀", "헤어", "남친", "여친", "썸"});
    }};

    private String buildTopicContext(String userMessage, Map<String, List<String>> topicExchanges) {
        for (Map.Entry<String, String[]> entry : TOPIC_KEYWORDS.entrySet()) {
            String topic = entry.getKey();
            String[] kws = entry.getValue();
            boolean matched = Arrays.stream(kws).anyMatch(userMessage::contains);
            if (!matched) continue;

            List<String> exchanges = topicExchanges.get(topic);
            if (exchanges == null || exchanges.isEmpty()) continue;

            return exchanges.stream()
                .limit(3)
                .collect(Collectors.joining("\n\n"));
        }
        return "";
    }
}
