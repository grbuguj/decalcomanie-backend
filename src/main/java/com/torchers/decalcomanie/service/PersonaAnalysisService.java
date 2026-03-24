package com.torchers.decalcomanie.service;

import com.torchers.decalcomanie.model.ConversationTurn;
import com.torchers.decalcomanie.model.Persona;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PersonaAnalysisService {

    private final GptService gptService;

    @org.springframework.beans.factory.annotation.Value("${gemini.api.model:}")
    private String modelName;

    public Persona analyze(String name, List<String> myMessages,
                            Map<String, List<String>> allMessages,
                            List<ConversationTurn> orderedTurns) {

        if (myMessages == null || myMessages.isEmpty())
            throw new IllegalArgumentException("메시지가 없습니다.");

        String speechStyle   = detectSpeechStyle(myMessages);
        String avgLength     = detectAvgLength(myMessages);
        List<String> phrases = extractCommonPhrases(myMessages);
        String endings       = detectEndingPatterns(myMessages);
        String reactionStyle = detectReactionStyle(myMessages);
        String emotionStyle  = detectEmotionStyle(myMessages);

        // 대화 쌍 추출 (핵심 개선)
        List<String> conversationPairs = extractConversationPairs(name, orderedTurns);

        // 최근 가중 샘플 (최신 대화일수록 중요)
        List<String> recentSamples = extractRecentWeightedSamples(myMessages);

        // 기억 추출 (2단계 GPT)
        List<String> candidates = collectCandidates(name, allMessages, orderedTurns);
        List<String> memories = candidates.isEmpty() ? List.of()
            : gptService.extractMemories(name, candidates);

        String mbti = estimateMbti(myMessages, orderedTurns, name);

        boolean isGemini = modelName != null && modelName.toLowerCase().contains("gemini");
        String systemPrompt = isGemini
            ? buildGeminiSystemPrompt(name, speechStyle, avgLength, phrases, endings,
                reactionStyle, emotionStyle, conversationPairs, recentSamples, memories, mbti)
            : buildSystemPrompt(name, speechStyle, avgLength, phrases, endings,
                reactionStyle, emotionStyle, conversationPairs, recentSamples, memories, mbti);

        return Persona.builder()
            .name(name)
            .speechStyle(speechStyle)
            .avgMessageLength(avgLength)
            .commonPhrases(phrases)
            .endingPatterns(endings)
            .memories(memories)
            .mbti(mbti)
            .systemPrompt(systemPrompt)
            .build();
    }

    // ── 말투 분석 ──────────────────────────────────────────

    private String detectSpeechStyle(List<String> messages) {
        long honorific = messages.stream()
            .filter(m -> m.endsWith("요") || m.endsWith("습니다") || m.endsWith("세요") || m.endsWith("죠"))
            .count();
        return honorific > messages.size() * 0.3 ? "존댓말" : "반말";
    }

    private String detectAvgLength(List<String> messages) {
        double avg = messages.stream().mapToInt(String::length).average().orElse(0);
        if (avg < 5)  return "매우 짧음 (평균 " + (int)avg + "자) — 단답 위주";
        if (avg < 15) return "짧음 (평균 " + (int)avg + "자) — 핵심만 말함";
        if (avg < 40) return "보통 (평균 " + (int)avg + "자)";
        return "긺 (평균 " + (int)avg + "자) — 설명 많음";
    }

    private List<String> extractCommonPhrases(List<String> messages) {
        String[] candidates = {
            "ㅋㅋ", "ㅋㅋㅋ", "ㅎㅎ", "ㅠㅠ", "ㄹㅇ", "진짜", "완전", "헐", "대박",
            "근데", "그냥", "좀", "ㅇㅇ", "ㄴㄴ", "ㅇㅋ", "ㅜㅜ", "모르겠다", "맞아",
            "아니", "잠깐", "어", "응", "그렇구나", "알겠어", "ㄱㄱ", "뭔데", "왜"
        };
        Map<String, Long> freq = new LinkedHashMap<>();
        for (String kw : candidates) {
            long cnt = messages.stream().filter(m -> m.contains(kw)).count();
            if (cnt > 0) freq.put(kw, cnt);
        }
        return freq.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(7).map(Map.Entry::getKey).collect(Collectors.toList());
    }

    private String detectEndingPatterns(List<String> messages) {
        int total = messages.size();
        List<String> patterns = new ArrayList<>();
        if (messages.stream().filter(m -> m.contains("ㅋㅋ")).count() > total * 0.08) patterns.add("ㅋㅋ 자주 씀");
        if (messages.stream().filter(m -> m.contains("ㅎㅎ")).count() > total * 0.08) patterns.add("ㅎㅎ 씀");
        if (messages.stream().filter(m -> m.contains("ㅠ")).count()   > total * 0.05) patterns.add("ㅠㅠ 감성적 표현 있음");
        if (messages.stream().filter(m -> m.endsWith("~")).count()    > total * 0.05) patterns.add("~ 어미");
        if (messages.stream().filter(m -> m.endsWith("!")).count()    > total * 0.1)  patterns.add("! 자주");
        if (messages.stream().filter(m -> m.length() <= 3).count()    > total * 0.3)  patterns.add("단답 비율 높음");
        return patterns.isEmpty() ? "특별한 패턴 없음" : String.join(", ", patterns);
    }

    private String detectReactionStyle(List<String> messages) {
        long question = messages.stream().filter(m -> m.contains("?")).count();
        long empathy  = messages.stream().filter(m ->
            m.contains("그렇구나") || m.contains("맞아") || m.contains("진짜?") || m.contains("헐")).count();
        long blunt    = messages.stream().filter(m ->
            m.equals("ㅇㅇ") || m.equals("응") || m.equals("어") || m.equals("ㄴㄴ")).count();

        List<String> styles = new ArrayList<>();
        if (question > messages.size() * 0.15) styles.add("질문을 자주 던짐");
        if (empathy  > messages.size() * 0.1)  styles.add("공감 반응을 잘 함");
        if (blunt    > messages.size() * 0.2)  styles.add("단답 반응 많음");
        return styles.isEmpty() ? "특별한 반응 패턴 없음" : String.join(", ", styles);
    }

    private String detectEmotionStyle(List<String> messages) {
        long positive = messages.stream().filter(m ->
            m.contains("좋아") || m.contains("ㅋㅋ") || m.contains("재밌") || m.contains("최고")).count();
        long negative = messages.stream().filter(m ->
            m.contains("힘들") || m.contains("짜증") || m.contains("ㅠ") || m.contains("싫어")).count();
        if (positive > negative * 2) return "긍정적이고 밝은 편";
        if (negative > positive * 2) return "감정 표현 솔직한 편";
        return "감정 표현 무덤덤한 편";
    }

    // ── 대화 쌍 추출 (핵심) ────────────────────────────────
    // 상대방 말 + 타겟 반응을 쌍으로 묶어서 GPT에게 보여줌

    private List<String> extractConversationPairs(String targetName, List<ConversationTurn> turns) {
        List<String> pairs = new ArrayList<>();
        if (turns == null || turns.isEmpty()) return pairs;

        for (int i = 1; i < turns.size(); i++) {
            ConversationTurn current = turns.get(i);
            if (!current.getSender().equals(targetName)) continue;

            // 직전 발화자가 다른 사람이면 → 쌍 생성
            ConversationTurn prev = turns.get(i - 1);
            if (prev.getSender().equals(targetName)) continue;

            String pair = prev.getSender() + ": " + prev.getMessage()
                + "\n" + targetName + ": " + current.getMessage();
            pairs.add(pair);
        }

        // 최근 50개 위주로, 전체에서 균등 샘플링도 추가
        int total = pairs.size();
        if (total <= 50) return pairs;

        List<String> sampled = new ArrayList<>();
        // 최근 30개
        sampled.addAll(pairs.subList(total - 30, total));
        // 나머지 구간에서 20개 균등 샘플
        int step = (total - 30) / 20;
        for (int i = 0; i < total - 30 && sampled.size() < 50; i += Math.max(1, step)) {
            sampled.add(0, pairs.get(i));
        }
        return sampled;
    }

    // ── 최근 가중 샘플 ──────────────────────────────────────
    // 최신 메시지에 더 많은 비중을 두고 샘플링

    private List<String> extractRecentWeightedSamples(List<String> messages) {
        List<String> filtered = messages.stream()
            .filter(m -> m.length() >= 2 && m.length() <= 60)
            .collect(Collectors.toList());

        int size = filtered.size();
        if (size == 0) return List.of();

        List<String> samples = new ArrayList<>();
        int recentCount = Math.min(20, size);
        int olderCount  = Math.min(10, size - recentCount);

        // 최근 20개
        samples.addAll(filtered.subList(Math.max(0, size - recentCount), size));

        // 나머지 구간에서 10개 균등
        if (olderCount > 0) {
            int step = Math.max(1, (size - recentCount) / olderCount);
            for (int i = 0; i < size - recentCount && samples.size() < 30; i += step) {
                samples.add(filtered.get(i));
            }
        }
        return samples;
    }

    // ── 기억 후보 수집 ─────────────────────────────────────
    // 발화자를 명확히 표시해서 GPT가 혼동하지 않도록 함

    private List<String> collectCandidates(String targetName,
                                             Map<String, List<String>> allMessages,
                                             List<ConversationTurn> orderedTurns) {
        List<String> candidates = new ArrayList<>();

        // 1. 이벤트 키워드가 등장한 대화 쌍을 "발화자: 내용" 형태로 수집
        //    → 누가 한 말인지 명확히 해서 GPT 혼동 방지
        String[] eventKeywords = {
            "여행", "공연", "콘서트", "발표", "시험", "과제", "회의",
            "밥", "카페", "술", "맛집", "먹었", "갔다", "다녀왔",
            "싸웠", "미안", "화났", "보고싶", "울었",
            "취업", "졸업", "군대", "퇴사", "이직", "아프"
        };

        // 이벤트 키워드 포함 대화 — 최근 200턴에서 10개, 나머지 800턴에서 5개 (최신 우선, ±2턴 컨텍스트)
        if (orderedTurns != null) {
            int total = orderedTurns.size();
            List<ConversationTurn> window800 = orderedTurns.subList(Math.max(0, total - 1000), Math.max(0, total - 200));
            List<ConversationTurn> window200 = orderedTurns.subList(Math.max(0, total - 200), total);

            collectEvents(targetName, window200, eventKeywords, 20, candidates);
            collectEvents(targetName, window800, eventKeywords, 10, candidates);
        }

        // 2. 타겟이 직접 한 말 — 최근 1000개 중 마지막 20개 (발화자 명시)
        List<String> myMessages = allMessages.getOrDefault(targetName, List.of());
        int size = myMessages.size();
        List<String> recentMy = myMessages.subList(Math.max(0, size - 1000), size).stream()
            .filter(m -> m.length() >= 5 && m.length() <= 60)
            .filter(m -> !m.startsWith("http"))
            .collect(Collectors.toList());
        int mySize = recentMy.size();
        recentMy.subList(Math.max(0, mySize - 20), mySize).stream()
            .map(m -> targetName + ": " + m)
            .forEach(candidates::add);

        return candidates.stream().distinct().limit(50).collect(Collectors.toList());
    }

    // 역순(최신 우선)으로 이벤트 키워드 포함 대화를 수집, ±2턴 컨텍스트 포함
    private void collectEvents(String targetName, List<ConversationTurn> turns,
                                String[] keywords, int maxCount, List<String> out) {
        int collected = 0;
        for (int i = turns.size() - 1; i >= 0 && collected < maxCount; i--) {
            ConversationTurn turn = turns.get(i);
            String msg = turn.getMessage();
            boolean hasKeyword = Arrays.stream(keywords).anyMatch(msg::contains);
            if (!hasKeyword || msg.length() > 80) continue;

            StringBuilder ctx = new StringBuilder();
            // 앞 2턴
            for (int j = Math.max(0, i - 2); j < i; j++) {
                ConversationTurn t = turns.get(j);
                ctx.append(t.getSender()).append(": ").append(truncate(t.getMessage(), 50)).append("\n");
            }
            // 현재
            ctx.append(turn.getSender()).append(": ").append(truncate(msg, 50));
            // 뒤 2턴
            for (int j = i + 1; j <= Math.min(turns.size() - 1, i + 2); j++) {
                ConversationTurn t = turns.get(j);
                ctx.append("\n").append(t.getSender()).append(": ").append(truncate(t.getMessage(), 50));
            }
            out.add(ctx.toString());
            collected++;
        }
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "..";
    }

    // ── MBTI 추정 ────────────────────────────────────────────

    private String estimateMbti(List<String> messages, List<ConversationTurn> turns, String name) {
        // E/I: 대화 첫 발화자 빈도 (외향 = 먼저 말 자주 검)
        long initiatedCount = 0;
        if (turns != null) {
            for (int i = 0; i < turns.size(); i++) {
                if (turns.get(i).getSender().equals(name)) {
                    if (i == 0 || !turns.get(i - 1).getSender().equals(name)) {
                        initiatedCount++;
                    }
                }
            }
        }
        long totalMyTurns = turns == null ? 1 :
            turns.stream().filter(t -> t.getSender().equals(name)).count();
        double initiateRatio = totalMyTurns > 0 ? (double) initiatedCount / totalMyTurns : 0.5;
        char ei = initiateRatio > 0.45 ? 'E' : 'I';

        // N/S: 추상적 vs 구체적
        long nCount = messages.stream().filter(m ->
            m.contains("느낌") || m.contains("생각") || m.contains("것 같") ||
            m.contains("왠지") || m.contains("뭔가") || m.contains("아마")).count();
        long sCount = messages.stream().filter(m ->
            m.contains("오늘") || m.contains("내일") || m.contains("어제") ||
            m.contains("몇 시") || m.contains("어디") || m.contains("얼마")).count();
        char ns = nCount >= sCount ? 'N' : 'S';

        // T/F: 논리 vs 감성
        long tCount = messages.stream().filter(m ->
            m.contains("왜냐") || m.contains("그래서") || m.contains("따라서") ||
            m.contains("분석") || m.contains("이유") || m.contains("논리")).count();
        long fCount = messages.stream().filter(m ->
            m.contains("ㅠ") || m.contains("보고싶") || m.contains("힘들") ||
            m.contains("속상") || m.contains("행복") || m.contains("슬프")).count();
        char tf = fCount > tCount ? 'F' : 'T';

        // J/P: 계획 vs 즉흥
        long jCount = messages.stream().filter(m ->
            m.contains("계획") || m.contains("일정") || m.contains("정해") ||
            m.contains("준비") || m.contains("미리") || m.contains("스케줄")).count();
        long pCount = messages.stream().filter(m ->
            m.contains("즉흥") || m.contains("갑자기") || m.contains("그냥") ||
            m.contains("나중에") || m.contains("뭐든") || m.contains("대충")).count();
        char jp = jCount > pCount ? 'J' : 'P';

        return "" + ei + ns + tf + jp;
    }

    private String mbtiToConversationStyle(String mbti) {
        if (mbti == null || mbti.length() != 4) return "";
        List<String> traits = new ArrayList<>();

        if (mbti.charAt(0) == 'E') {
            traits.add("대화를 주도하고 먼저 화제를 꺼내는 편");
            traits.add("자기 얘기를 자주 꺼냄");
        } else {
            traits.add("필요한 말만 하고 많이 물어보지 않는 편");
            traits.add("상대가 말하면 받아치는 스타일");
        }
        if (mbti.charAt(1) == 'N') {
            traits.add("'뭔가 그런 느낌이야', '왠지 모르게' 같은 감각적 표현 자주 씀");
        } else {
            traits.add("구체적 사실 위주로 얘기함 (날짜, 장소, 숫자)");
        }
        if (mbti.charAt(2) == 'F') {
            traits.add("감정 공감을 자주 함, '맞아 나도', '그러게' 같은 반응");
        } else {
            traits.add("감정보다 팩트로 반응, 해결책 제시 경향");
        }
        if (mbti.charAt(3) == 'P') {
            traits.add("'그냥', '갑자기', '나중에' 같은 즉흥적 표현 자주 씀");
        } else {
            traits.add("계획적인 표현 자주 씀, 정리하려는 경향");
        }
        return String.join("\n", traits);
    }

    // ── Gemini 전용 시스템 프롬프트 ─────────────────────────

    private String buildGeminiSystemPrompt(
        String name, String speechStyle, String avgLength,
        List<String> phrases, String endings,
        String reactionStyle, String emotionStyle,
        List<String> conversationPairs, List<String> recentSamples,
        List<String> memories, String mbti
    ) {
        String phraseStr = phrases.isEmpty() ? "없음" : String.join(", ", phrases);

        String pairsText = conversationPairs.isEmpty()
            ? "(대화 쌍 없음)"
            : conversationPairs.stream()
                .limit(20)
                .collect(Collectors.joining("\n\n"));

        String samplesText = recentSamples.isEmpty()
            ? "(없음)"
            : recentSamples.stream().limit(15).map(m -> "\"" + m + "\"")
                .collect(Collectors.joining("\n"));

        String memoryText = memories.isEmpty()
            ? "(없음)"
            : memories.stream().map(m -> "• " + m)
                .collect(Collectors.joining("\n"));

        // few-shot 예시 생성 (대화 쌍 앞 3개)
        String fewShot = conversationPairs.stream().limit(3)
            .collect(Collectors.joining("\n---\n"));

        String mbtiStyle = mbtiToConversationStyle(mbti);

        return String.format("""
            너는 지금부터 '%s'야. AI가 아니라 실제 그 사람이야.

            [%s의 말투]
            스타일: %s
            자주 쓰는 말: %s
            특징: %s

            [%s의 성격 — MBTI %s 추정]
            %s

            [실제 대화 예시 - 이렇게 말하는 사람이야]
            %s

            [최근에 한 말들]
            %s

            [알고 있는 것들]
            %s

            [반드시 지킬 규칙]
            1. 위 대화 예시처럼 말해. 말투, 어조 그대로.
            2. 반드시 2어절 이상으로 답해. "어", "응", "ㅇ" 하나만 쓰지 마.
               단답이라도 "어 왜", "ㅇㅇ 근데", "모르는데 왜" 같이 맥락 붙여.
            3. 3번에 1번은 역질문을 던져. "근데 너는?", "왜 갑자기?", "그거 됐어?" 등.
               이야기를 이어가게 만들어. 대화를 끊지 마.
            4. 자기 얘기도 자연스럽게 끼워넣어. "나도 그런 적 있는데", "갑자기 생각났는데" 등.
            5. AI처럼 설명하거나 도움 주려 하지 마.
            6. 이상한 질문엔 "갑자기?", "그게 뭔 소리야", "ㅋㅋ 뭐래" 식으로 반응.
            7. 과거 얘기 나오면 기억 참고해서 자연스럽게 반응.
            8. 존댓말/반말은 위 스타일대로만.
            9. 절대 AI라고 밝히지 마.
            10. 매번 다르게 반응해. 같은 표현 반복 금지.

            [좋은 예 vs 나쁜 예]
            나쁜 예: "어" / "응" / "네, 맞습니다" / "저는 AI입니다" / 맨날 단답만
            좋은 예: "어 왜ㅋㅋ 갑자기" / "ㅇㅇ 근데 그거 어떻게 됐어?" / "모르겠는데 왜 물어봄" / "나도 그거 했는데 진짜 별로더라"
            """,
            name, name, speechStyle, phraseStr, endings,
            name, mbti, mbtiStyle,
            pairsText.isEmpty() ? fewShot : pairsText,
            samplesText, memoryText
        );
    }

    // ── GPT 전용 시스템 프롬프트 빌더 ──────────────────────

    private String buildSystemPrompt(
        String name, String speechStyle, String avgLength,
        List<String> phrases, String endings,
        String reactionStyle, String emotionStyle,
        List<String> conversationPairs, List<String> recentSamples,
        List<String> memories, String mbti
    ) {
        String phraseStr = phrases.isEmpty() ? "없음" : String.join(", ", phrases);

        String pairsText = conversationPairs.isEmpty()
            ? "  (대화 쌍 없음)"
            : conversationPairs.stream()
                .map(p -> p.lines()
                    .map(l -> "  " + l)
                    .collect(Collectors.joining("\n")))
                .collect(Collectors.joining("\n\n"));

        String samplesText = recentSamples.isEmpty()
            ? "  (없음)"
            : recentSamples.stream().map(m -> "  \"" + m + "\"")
                .collect(Collectors.joining("\n"));

        String memoryText = memories.isEmpty()
            ? "  (추출된 기억 없음)"
            : memories.stream().map(m -> "  • " + m)
                .collect(Collectors.joining("\n"));

        String mbtiStyle = mbtiToConversationStyle(mbti);

        return String.format("""
            당신은 '%s'입니다. 롤플레이가 아니라 진짜 그 사람입니다.
            어떤 질문이 와도 그 사람으로서 즉각 반응하세요.

            ━━━━━━━━━━━━━━━━━━━━━
            [말투 특성]
            - 스타일: %s / 길이: %s
            - 자주 쓰는 표현: %s
            - 문장 패턴: %s
            - 반응 방식: %s / 감정: %s

            ━━━━━━━━━━━━━━━━━━━━━
            [성격 — MBTI %s 추정]
            %s

            ━━━━━━━━━━━━━━━━━━━━━
            [실제 대화 패턴 — 이 사람의 실제 반응]
            (가장 중요. 말투·길이·어조를 그대로 따를 것)

            %s

            ━━━━━━━━━━━━━━━━━━━━━
            [이 사람이 최근에 한 말들]

            %s

            ━━━━━━━━━━━━━━━━━━━━━
            [기억 — 실제로 있었던 일, 성격, 관심사]

            %s

            ━━━━━━━━━━━━━━━━━━━━━
            [절대 규칙]
            1. 말투, 길이, 어조는 위 대화 패턴 그대로. 절대 격식체나 친절한 말투 쓰지 마세요.
            2. AI라고 절대 밝히지 마세요. 당신은 '%s'입니다.
            3. 짧게. 한두 문장 이내. 설명하거나 정보를 제공하려 하지 마세요.
            4. 도움이 되려 하지 마세요. 그냥 '%s'처럼 반응하면 됩니다.
            5. 갑작스럽거나 이상한 질문엔 당황하거나 무시하거나 딴말로 반응하세요.
               예: "갑자기?", "그게 뭔 소리야", "몰라 나한테 왜 물어봐", "ㅋㅋ 뭐래"
            6. 과거 사건을 물으면 기억을 참고해 자연스럽게 반응하세요.
            7. 성격에 맞게 자연스럽게 역질문도 던지고, 자기 얘기도 끼워넣으세요.
               예: "근데 너는?", "나도 그런 적 있는데", "갑자기 생각났는데"
            8. 모르는 건 "모르겠는데" 식으로, 그 사람 말투로 답하세요.
            9. 과하게 친절하거나 공감하거나 감성적으로 굴지 마세요.
            10. 존댓말/반말은 위 스타일 그대로 유지하세요.
            """,
            name, speechStyle, avgLength, phraseStr, endings,
            reactionStyle, emotionStyle,
            mbti, mbtiStyle,
            pairsText, samplesText, memoryText, name, name
        );
    }
}
