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

        String speechStyle    = detectSpeechStyle(myMessages);
        String avgLength      = detectAvgLength(myMessages);
        List<String> phrases  = extractCommonPhrases(myMessages);
        String endings        = detectEndingPatterns(myMessages);      // 짧은 태그
        String endingStyle    = detectEndingStyle(myMessages);         // 상세 종결어미
        String typingHabits   = detectTypingHabits(myMessages);        // 오타/ㅋ 패턴
        String burstPattern   = detectBurstPattern(name, orderedTurns);
        List<String> topics   = detectTopics(myMessages);
        String reactionStyle  = detectReactionStyle(myMessages);
        String emotionStyle   = detectEmotionStyle(myMessages);

        // 상황별 대화 쌍 분류
        Map<String, List<String>> situationalPairs =
            classifyPairsBySituation(name, orderedTurns);

        // 최근 가중 샘플
        List<String> recentSamples = extractRecentWeightedSamples(myMessages);

        // 기억 추출 (Gemini)
        List<String> candidates = collectCandidates(name, allMessages, orderedTurns);
        List<String> memories = candidates.isEmpty() ? List.of()
            : gptService.extractMemories(name, candidates);

        String mbti = estimateMbti(myMessages, orderedTurns, name);

        // 주제별 대화쌍 미리 인덱싱 (런타임 RAG 대체)
        Map<String, List<String>> topicExchanges = buildTopicExchangeMap(name, orderedTurns);

        boolean isGemini = modelName != null && modelName.toLowerCase().contains("gemini");
        String systemPrompt = isGemini
            ? buildGeminiSystemPrompt(name, speechStyle, avgLength, phrases, endings,
                endingStyle, typingHabits, burstPattern, topics,
                reactionStyle, emotionStyle, situationalPairs, recentSamples, memories, mbti)
            : buildSystemPrompt(name, speechStyle, avgLength, phrases, endings,
                reactionStyle, emotionStyle,
                situationalPairs.values().stream().flatMap(List::stream).collect(Collectors.toList()),
                recentSamples, memories, mbti);

        return Persona.builder()
            .name(name)
            .speechStyle(speechStyle)
            .avgMessageLength(avgLength)
            .commonPhrases(phrases)
            .endingPatterns(endings)
            .endingStyle(endingStyle)
            .typingHabits(typingHabits)
            .burstPattern(burstPattern)
            .topics(topics)
            .memories(memories)
            .mbti(mbti)
            .topicExchanges(topicExchanges)
            .systemPrompt(systemPrompt)
            .build();
    }

    // ── 기본 말투 분석 ──────────────────────────────────────

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

    // ── 신규: 종결어미 상세 분포 ────────────────────────────

    private String detectEndingStyle(List<String> messages) {
        // (어미 → 설명) 순서대로
        String[][] endingDefs = {
            {"ㄴ데", "~ㄴ데"},  {"는데", "~는데"}, {"잖아", "~잖아"},
            {"거든", "~거든"}, {"이야", "~이야"},  {"이야", "~야"},
            {"함", "~함"},     {"임", "~임"},      {"ㄹ듯", "~ㄹ듯"},
            {"지", "~지"},     {"네", "~네"},      {"나", "~나"},
            {"구나", "~구나"}, {"라", "~라"},      {"어", "~어"},
        };

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String[] def : endingDefs) {
            String ending = def[0];
            String label  = def[1];
            long cnt = messages.stream().filter(m -> m.endsWith(ending)).count();
            if (cnt >= 3) counts.merge(label, cnt, Long::sum);
        }

        if (counts.isEmpty()) return "특정 종결어미 패턴 없음";

        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(5)
            .map(e -> e.getKey() + " " + e.getValue() + "회")
            .collect(Collectors.joining(", "));
    }

    // ── 신규: 오타/축약/타이핑 습관 ─────────────────────────

    private String detectTypingHabits(List<String> messages) {
        int total = messages.size();
        List<String> habits = new ArrayList<>();

        // ㅋ 분포
        long oneOrTwo = messages.stream()
            .filter(m -> (m.contains("ㅋㅋ") && !m.contains("ㅋㅋㅋ")) || (m.contains("ㅋ") && !m.contains("ㅋㅋ")))
            .count();
        long manyK = messages.stream().filter(m -> m.contains("ㅋㅋㅋ")).count();
        if (manyK > total * 0.1) habits.add("ㅋ을 3개 이상 연속으로 씀");
        else if (oneOrTwo > total * 0.1) habits.add("ㅋ을 1-2개 씀");

        // 줄임 자음
        if (messages.stream().filter(m -> m.contains("ㄱㅅ")).count() > total * 0.02) habits.add("'ㄱㅅ' 씀");
        if (messages.stream().filter(m -> m.contains("ㅂㅇ")).count() > total * 0.02) habits.add("'ㅂㅇ' 씀");
        if (messages.stream().filter(m -> m.contains("ㄷㄷ")).count() > total * 0.02) habits.add("'ㄷㄷ' 씀");
        if (messages.stream().filter(m -> m.contains("ㅇㅋ")).count() > total * 0.03) habits.add("'ㅇㅋ' 씀");
        if (messages.stream().filter(m -> m.contains("ㅇㅇ")).count() > total * 0.05) habits.add("'ㅇㅇ' 씀");

        // 오타 패턴
        if (messages.stream().filter(m -> m.contains("됬")).count() > 2) habits.add("'됬' 사용 (됐의 오타)");
        if (messages.stream().filter(m -> m.contains("않")).count() > total * 0.03) {
            long incorrectAn = messages.stream()
                .filter(m -> m.contains("않해") || m.contains("않됨") || m.contains("않음"))
                .count();
            if (incorrectAn > 1) habits.add("'않' 오용 경향");
        }

        // 문장부호 여부
        long withPunct = messages.stream()
            .filter(m -> m.endsWith(".") || m.endsWith("!") || m.endsWith("?"))
            .count();
        if (withPunct < total * 0.05) habits.add("문장부호 거의 안 씀");
        else if (withPunct > total * 0.4) habits.add("문장부호 자주 씀");

        // 이모티콘/특수문자
        if (messages.stream().filter(m -> m.contains("ㅎ")).count() > total * 0.08) habits.add("'ㅎㅎ' 계열 씀");

        return habits.isEmpty() ? "특별한 습관 없음" : String.join(", ", habits);
    }

    // ── 신규: 연속 버블 패턴 ────────────────────────────────

    private String detectBurstPattern(String name, List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) return "대체로 1개씩 보냄";

        List<Integer> bursts = new ArrayList<>();
        int current = 0;
        for (ConversationTurn t : turns) {
            if (t.getSender().equals(name)) {
                current++;
            } else {
                if (current > 0) { bursts.add(current); current = 0; }
            }
        }
        if (current > 0) bursts.add(current);
        if (bursts.isEmpty()) return "대체로 1개씩 보냄";

        double avg = bursts.stream().mapToInt(i -> i).average().orElse(1.0);
        long multi = bursts.stream().filter(b -> b >= 3).count();
        long total = bursts.size();

        if (avg >= 2.5)
            return String.format("연속으로 보내는 편 (평균 %.1f개, %d번 중 %d번이 3개 이상)", avg, total, multi);
        if (avg >= 1.7)
            return String.format("가끔 연속으로 보냄 (평균 %.1f개)", avg);
        return "대체로 1개씩 보냄";
    }

    // ── 신규: 주제별 대화쌍 인덱스 구축 (분석 시점 RAG) ────────

    private static final Map<String, String[]> TOPIC_KEYWORD_MAP = new LinkedHashMap<>() {{
        put("음식",    new String[]{"밥", "먹", "식당", "카페", "커피", "치킨", "라면", "배고", "맛있", "맛집", "배달"});
        put("공부/학교", new String[]{"시험", "과제", "수업", "학교", "공부", "성적", "교수", "레포트", "강의"});
        put("일/직장", new String[]{"회사", "업무", "퇴근", "출근", "야근", "회의", "프로젝트", "상사"});
        put("운동",    new String[]{"운동", "헬스", "달리기", "다이어트", "헬스장", "탁구", "축구"});
        put("여행",    new String[]{"여행", "갔다", "다녀왔", "놀러", "여기 왔", "공항", "숙소"});
        put("게임",    new String[]{"게임", "롤", "배그", "스팀", "플레이", "랭크"});
        put("건강",    new String[]{"아프", "병원", "약", "감기", "머리아", "열나", "두통"});
        put("연애",    new String[]{"좋아", "사귀", "헤어", "남친", "여친", "썸", "소개팅"});
    }};

    private Map<String, List<String>> buildTopicExchangeMap(String name, List<ConversationTurn> turns) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (turns == null || turns.isEmpty()) return result;

        for (Map.Entry<String, String[]> topicEntry : TOPIC_KEYWORD_MAP.entrySet()) {
            String topic = topicEntry.getKey();
            String[] keywords = topicEntry.getValue();
            List<String> exchanges = new ArrayList<>();

            for (int i = 1; i < turns.size() && exchanges.size() < 5; i++) {
                ConversationTurn cur  = turns.get(i);
                ConversationTurn prev = turns.get(i - 1);
                // 이 페르소나가 포함된 교환만
                if (!cur.getSender().equals(name) && !prev.getSender().equals(name)) continue;
                // 주제 키워드 포함 여부
                boolean match = Arrays.stream(keywords).anyMatch(kw ->
                    cur.getMessage().contains(kw) || prev.getMessage().contains(kw));
                if (!match) continue;

                String pair = prev.getSender() + ": " + truncate(prev.getMessage(), 50)
                    + "\n" + cur.getSender() + ": " + truncate(cur.getMessage(), 50);
                exchanges.add(pair);
            }
            if (!exchanges.isEmpty()) result.put(topic, exchanges);
        }
        return result;
    }

    // ── 신규: 주제/관심사 감지 ──────────────────────────────

    private List<String> detectTopics(List<String> messages) {
        int total = messages.size();
        Map<String, String[]> topicMap = new LinkedHashMap<>();
        topicMap.put("음식/맛집", new String[]{"밥", "먹었", "먹자", "식당", "카페", "커피", "맛있", "치킨", "라면", "배고"});
        topicMap.put("공부/학교", new String[]{"시험", "과제", "수업", "학교", "교수", "공부", "성적", "강의", "레포트"});
        topicMap.put("일/직장", new String[]{"회사", "업무", "일하", "퇴근", "출근", "회의", "야근", "프로젝트"});
        topicMap.put("운동/건강", new String[]{"운동", "헬스", "달리기", "다이어트", "아프", "병원", "헬스장"});
        topicMap.put("게임", new String[]{"게임", "롤", "배그", "스팀", "플레이", "랭크", "졌", "이겼"});
        topicMap.put("드라마/영화", new String[]{"드라마", "영화", "넷플", "봤어", "재밌었", "시즌"});
        topicMap.put("음악", new String[]{"노래", "음악", "플리", "앨범", "콘서트", "유튜브"});
        topicMap.put("여행/외출", new String[]{"여행", "갔다", "다녀왔", "구경", "놀러", "여기 왔"});

        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : topicMap.entrySet()) {
            String[] kws = entry.getValue();
            long cnt = messages.stream()
                .filter(m -> Arrays.stream(kws).anyMatch(m::contains))
                .count();
            if (cnt >= Math.max(3, total * 0.03)) result.add(entry.getKey());
        }
        return result;
    }

    // ── 신규: 상황별 대화 쌍 분류 ──────────────────────────

    private Map<String, List<String>> classifyPairsBySituation(String name, List<ConversationTurn> turns) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("일상 수다", new ArrayList<>());
        result.put("계획/약속", new ArrayList<>());
        result.put("감정/진지", new ArrayList<>());
        result.put("거절/부정", new ArrayList<>());

        if (turns == null) return result;

        for (int i = 1; i < turns.size(); i++) {
            ConversationTurn cur = turns.get(i);
            if (!cur.getSender().equals(name)) continue;
            ConversationTurn prev = turns.get(i - 1);
            if (prev.getSender().equals(name)) continue;

            String pair = prev.getSender() + ": " + prev.getMessage()
                + "\n" + name + ": " + cur.getMessage();
            String lower = pair;

            if (containsAny(lower, "언제", "몇 시", "갈까", "어디서", "약속", "시간", "몇시")) {
                result.get("계획/약속").add(pair);
            } else if (containsAny(lower, "힘들", "걱정", "사실", "솔직", "미안", "보고싶", "울", "속상")) {
                result.get("감정/진지").add(pair);
            } else if (containsAny(lower, "됐어", "몰라", "싫어", "아니", "ㄴㄴ", "별로", "안 함", "모르겠")) {
                result.get("거절/부정").add(pair);
            } else {
                result.get("일상 수다").add(pair);
            }
        }
        return result;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }

    // ── 반응/감정 스타일 ────────────────────────────────────

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

    // ── 최근 가중 샘플 ──────────────────────────────────────

    private List<String> extractRecentWeightedSamples(List<String> messages) {
        List<String> filtered = messages.stream()
            .filter(m -> m.length() >= 2 && m.length() <= 60)
            .collect(Collectors.toList());

        int size = filtered.size();
        if (size == 0) return List.of();

        List<String> samples = new ArrayList<>();
        int recentCount = Math.min(20, size);
        int olderCount  = Math.min(10, size - recentCount);

        samples.addAll(filtered.subList(Math.max(0, size - recentCount), size));
        if (olderCount > 0) {
            int step = Math.max(1, (size - recentCount) / olderCount);
            for (int i = 0; i < size - recentCount && samples.size() < 30; i += step) {
                samples.add(filtered.get(i));
            }
        }
        return samples;
    }

    // ── 기억 후보 수집 ─────────────────────────────────────

    private List<String> collectCandidates(String targetName,
                                             Map<String, List<String>> allMessages,
                                             List<ConversationTurn> orderedTurns) {
        List<String> candidates = new ArrayList<>();

        String[] eventKeywords = {
            "여행", "공연", "콘서트", "발표", "시험", "과제", "회의",
            "밥", "카페", "술", "맛집", "먹었", "갔다", "다녀왔",
            "싸웠", "미안", "화났", "보고싶", "울었",
            "취업", "졸업", "군대", "퇴사", "이직", "아프"
        };

        if (orderedTurns != null) {
            int total = orderedTurns.size();
            List<ConversationTurn> window800 = orderedTurns.subList(Math.max(0, total - 1000), Math.max(0, total - 200));
            List<ConversationTurn> window200 = orderedTurns.subList(Math.max(0, total - 200), total);

            collectEvents(targetName, window200, eventKeywords, 20, candidates);
            collectEvents(targetName, window800, eventKeywords, 10, candidates);
        }

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

    private void collectEvents(String targetName, List<ConversationTurn> turns,
                                String[] keywords, int maxCount, List<String> out) {
        int collected = 0;
        for (int i = turns.size() - 1; i >= 0 && collected < maxCount; i--) {
            ConversationTurn turn = turns.get(i);
            String msg = turn.getMessage();
            boolean hasKeyword = Arrays.stream(keywords).anyMatch(msg::contains);
            if (!hasKeyword || msg.length() > 80) continue;

            StringBuilder ctx = new StringBuilder();
            // 날짜 헤더 포함 (있으면)
            if (turn.getDate() != null) {
                ctx.append("[").append(turn.getDate()).append("]\n");
            }
            for (int j = Math.max(0, i - 2); j < i; j++) {
                ConversationTurn t = turns.get(j);
                ctx.append(t.getSender()).append(": ").append(truncate(t.getMessage(), 50)).append("\n");
            }
            ctx.append(turn.getSender()).append(": ").append(truncate(msg, 50));
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
        long initiatedCount = 0;
        if (turns != null) {
            for (int i = 0; i < turns.size(); i++) {
                if (turns.get(i).getSender().equals(name)) {
                    if (i == 0 || !turns.get(i - 1).getSender().equals(name)) initiatedCount++;
                }
            }
        }
        long totalMyTurns = turns == null ? 1 :
            turns.stream().filter(t -> t.getSender().equals(name)).count();
        double initiateRatio = totalMyTurns > 0 ? (double) initiatedCount / totalMyTurns : 0.5;
        char ei = initiateRatio > 0.45 ? 'E' : 'I';

        long nCount = messages.stream().filter(m ->
            m.contains("느낌") || m.contains("생각") || m.contains("것 같") ||
            m.contains("왠지") || m.contains("뭔가") || m.contains("아마")).count();
        long sCount = messages.stream().filter(m ->
            m.contains("오늘") || m.contains("내일") || m.contains("어제") ||
            m.contains("몇 시") || m.contains("어디") || m.contains("얼마")).count();
        char ns = nCount >= sCount ? 'N' : 'S';

        long tCount = messages.stream().filter(m ->
            m.contains("왜냐") || m.contains("그래서") || m.contains("따라서") ||
            m.contains("분석") || m.contains("이유") || m.contains("논리")).count();
        long fCount = messages.stream().filter(m ->
            m.contains("ㅠ") || m.contains("보고싶") || m.contains("힘들") ||
            m.contains("속상") || m.contains("행복") || m.contains("슬프")).count();
        char tf = fCount > tCount ? 'F' : 'T';

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
        String endingStyle, String typingHabits, String burstPattern,
        List<String> topics, String reactionStyle, String emotionStyle,
        Map<String, List<String>> situationalPairs,
        List<String> recentSamples, List<String> memories, String mbti
    ) {
        String phraseStr = phrases.isEmpty() ? "없음" : String.join(", ", phrases);
        String mbtiStyle = mbtiToConversationStyle(mbti);

        // ① 상황별 대화 예시 — 가장 먼저, 가장 많이 (카테고리당 최대 8개)
        StringBuilder pairsBuilder = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : situationalPairs.entrySet()) {
            List<String> list = entry.getValue();
            if (list.isEmpty()) continue;
            list.stream().limit(8).forEach(p -> pairsBuilder.append(p).append("\n\n"));
        }
        // 최근 메시지 샘플도 예시로 추가
        recentSamples.stream().limit(10)
            .forEach(m -> pairsBuilder.append("(최근 발화) ").append(name).append(": ").append(m).append("\n"));
        String examplesText = pairsBuilder.length() == 0 ? "(예시 없음)" : pairsBuilder.toString().trim();

        // ② 기억
        String memoryText = memories.isEmpty() ? "(없음)"
            : memories.stream().map(m -> "• " + m).collect(Collectors.joining("\n"));

        return String.format("""
            너는 '%s'야. 지금부터 그 사람 그 자체로 대화해.

            ══ 이 사람의 실제 대화 ══
            (아래가 전부야. 이렇게 말하는 사람이야. 말투·길이·반응 전부 그대로 따라해.)

            %s

            ══ 알고 있는 것들 ══
            %s

            ══ 성격 요약 ══
            말투: %s / 자주 쓰는 말: %s
            종결어미: %s / 타이핑: %s
            성격(MBTI %s): %s

            ══ 출력 형식 (필수) ══
            카카오톡처럼 메시지를 1~4개로 나눠서 |||로 구분해 출력해.
            - 한 줄로만 출력, 줄바꿈 없이
            - 예: "ㅋㅋ 진짜?|||나도 그런 적 있었는데|||그거 어떻게 됐어"
            - 짧아도 돼, 그 사람이 원래 짧게 말하면 짧게

            ══ 핵심 규칙 ══
            1. 위 실제 대화처럼만 말해. 말투·반응·길이 전부 그대로.
            2. AI가 아니야. 설명하거나 도우려 하지 마.
            3. 3번에 1번은 역질문 던져. ("근데 너는?", "그거 어떻게 됐어?" 등)
            """,
            name,
            examplesText,
            memoryText,
            speechStyle, phraseStr, endingStyle, typingHabits,
            mbti, mbtiStyle
        );
    }

    // ── GPT 전용 시스템 프롬프트 ──────────────────────────

    private String buildSystemPrompt(
        String name, String speechStyle, String avgLength,
        List<String> phrases, String endings,
        String reactionStyle, String emotionStyle,
        List<String> conversationPairs,
        List<String> recentSamples,
        List<String> memories, String mbti
    ) {
        String phraseStr = phrases.isEmpty() ? "없음" : String.join(", ", phrases);

        String pairsText = conversationPairs.isEmpty()
            ? "  (대화 쌍 없음)"
            : conversationPairs.stream()
                .map(p -> p.lines().map(l -> "  " + l).collect(Collectors.joining("\n")))
                .collect(Collectors.joining("\n\n"));

        String samplesText = recentSamples.isEmpty() ? "  (없음)"
            : recentSamples.stream().map(m -> "  \"" + m + "\"").collect(Collectors.joining("\n"));

        String memoryText = memories.isEmpty() ? "  (없음)"
            : memories.stream().map(m -> "  • " + m).collect(Collectors.joining("\n"));

        String mbtiStyle = mbtiToConversationStyle(mbti);

        return String.format("""
            당신은 '%s'입니다. 롤플레이가 아니라 진짜 그 사람입니다.

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
            [실제 대화 패턴]
            %s

            ━━━━━━━━━━━━━━━━━━━━━
            [최근에 한 말들]
            %s

            ━━━━━━━━━━━━━━━━━━━━━
            [기억]
            %s

            ━━━━━━━━━━━━━━━━━━━━━
            [절대 규칙]
            1. 말투·어조 위 패턴 그대로.
            2. AI라고 밝히지 마.
            3. 짧게. 한두 문장 이내.
            4. 도움 주려 하지 마.
            5. 역질문 자주 던지고 자기 얘기도 끼워넣어.
            6. 기억 참고해서 반응.
            7. 존댓말/반말 스타일 유지.
            """,
            name, speechStyle, avgLength, phraseStr, endings,
            reactionStyle, emotionStyle,
            mbti, mbtiStyle,
            pairsText, samplesText, memoryText
        );
    }
}
