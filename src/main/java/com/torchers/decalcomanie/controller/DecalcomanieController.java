package com.torchers.decalcomanie.controller;

import com.torchers.decalcomanie.model.*;
import com.torchers.decalcomanie.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DecalcomanieController {

    private final KakaoParserService parserService;
    private final PersonaAnalysisService personaService;
    private final GptService gptService;
    private final SessionStoreService sessionStore;

    // 1. 파일 업로드 → 참여자 목록 반환
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            String sessionId = UUID.randomUUID().toString();
            ParsedChat parsed = parserService.parse(file.getInputStream(), sessionId, file.getOriginalFilename());

            if (parsed.getParticipants().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "대화 내용을 파싱할 수 없습니다. 카카오톡 내보내기 파일인지 확인해주세요."));
            }

            sessionStore.storeParsedChat(sessionId, parsed);

            return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "participants", parsed.getParticipants()
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "파일 처리 중 오류: " + e.getMessage()));
        }
    }

    // 2. 클론할 인물 선택 → 페르소나 분석
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String targetName = body.get("targetName");

        ParsedChat parsed = sessionStore.getParsedChat(sessionId);
        if (parsed == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "세션이 만료되었습니다. 파일을 다시 업로드해주세요."));
        }

        List<String> messages = parsed.getMessagesByParticipant().get(targetName);
        if (messages == null || messages.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "해당 인물의 메시지를 찾을 수 없습니다."));
        }

        Persona persona = personaService.analyze(
            targetName, messages,
            parsed.getMessagesByParticipant(),
            parsed.getOrderedTurns()
        );

        SessionData sessionData = SessionData.builder()
            .persona(persona)
            .build();
        sessionStore.storeSession(sessionId, sessionData);

        Map<String, Object> personaMap = new HashMap<>();
        personaMap.put("name", persona.getName());
        personaMap.put("speechStyle", persona.getSpeechStyle());
        personaMap.put("avgMessageLength", persona.getAvgMessageLength());
        personaMap.put("commonPhrases", persona.getCommonPhrases());
        personaMap.put("endingPatterns", persona.getEndingPatterns());
        personaMap.put("memories", persona.getMemories());

        return ResponseEntity.ok(Map.of("sessionId", sessionId, "persona", personaMap));
    }

    // 3. 채팅
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String userMessage = body.get("message");

        SessionData session = sessionStore.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "세션이 만료되었습니다. 처음부터 다시 시작해주세요."));
        }

        String aiResponse = gptService.chat(session.getPersona(), session.getHistory(), userMessage);

        // 히스토리에 추가
        session.getHistory().add(new ChatMessage("user", userMessage));
        session.getHistory().add(new ChatMessage("assistant", aiResponse));

        return ResponseEntity.ok(Map.of("message", aiResponse));
    }

    // 4. 세션 삭제
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<?> deleteSession(@PathVariable String sessionId) {
        sessionStore.deleteSession(sessionId);
        return ResponseEntity.ok(Map.of("message", "세션이 삭제되었습니다."));
    }
}
