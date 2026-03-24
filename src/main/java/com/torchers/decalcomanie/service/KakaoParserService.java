package com.torchers.decalcomanie.service;

import com.torchers.decalcomanie.model.ConversationTurn;
import com.torchers.decalcomanie.model.ParsedChat;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KakaoParserService {

    // 구버전: 오전 9:57, 김재웅 : 메시지
    private static final Pattern TXT_PATTERN_OLD =
        Pattern.compile("^(오전|오후) \\d{1,2}:\\d{2}, (.+?) : (.+)$");

    // 신버전: [김재웅] [오전 9:57] 메시지
    private static final Pattern TXT_PATTERN_NEW =
        Pattern.compile("^\\[(.+?)\\] \\[(오전|오후) \\d{1,2}:\\d{2}\\] (.+)$");

    // TXT 날짜 구분선: "2024년 3월 1일 금요일" or "2024년 03월 01일 금요일"
    private static final Pattern TXT_DATE_LINE =
        Pattern.compile("^(\\d{4}년 \\d{1,2}월 \\d{1,2}일)[가-힣 ]*$");

    // CSV datetime: "2024-03-01 09:57:00"
    private static final Pattern CSV_DATE_PATTERN =
        Pattern.compile("^\"?(\\d{4})-(\\d{2})-(\\d{2}) \\d{2}:\\d{2}:\\d{2}\"?$");

    private static final Pattern PHONE_PATTERN =
        Pattern.compile("\\d{3}-\\d{3,4}-\\d{4}");
    private static final Pattern ADDRESS_PATTERN =
        Pattern.compile("[가-힣]+(시|도|구|군)\\s[가-힣0-9\\s]+(로|길|동|가)\\s*\\d+[가-힣0-9\\-\\s]*");

    // (구 DATE_PATTERN 대체됨 — CSV_DATE_PATTERN 사용)

    public ParsedChat parse(InputStream inputStream, String sessionId, String filename) throws Exception {
        byte[] bytes = inputStream.readAllBytes();
        String charset = detectCharset(bytes);
        String content = new String(bytes, Charset.forName(charset));
        if (content.startsWith("\uFEFF")) content = content.substring(1);

        boolean isCsv = filename != null && filename.toLowerCase().endsWith(".csv");
        return isCsv ? parseCsv(content, sessionId) : parseTxt(content, sessionId);
    }

    private String detectCharset(byte[] bytes) {
        // UTF-8 BOM 체크
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return "UTF-8";
        }
        // UTF-8로 디코딩 후 한글 존재 여부 확인 (문자 단위 순회 — multiline 안전)
        try {
            String test = new String(bytes, "UTF-8");
            for (char c : test.toCharArray()) {
                if (c >= '가' && c <= '힣') return "UTF-8";
            }
        } catch (Exception ignored) {}
        return "EUC-KR";
    }

    private ParsedChat parseTxt(String content, String sessionId) {
        Map<String, List<String>> messagesByParticipant = new LinkedHashMap<>();
        List<ConversationTurn> orderedTurns = new ArrayList<>();
        String currentDate = null;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();

            // 날짜 구분선 감지: "2024년 3월 1일 금요일"
            Matcher dateMatcher = TXT_DATE_LINE.matcher(trimmed);
            if (dateMatcher.matches()) {
                currentDate = dateMatcher.group(1); // "2024년 3월 1일"
                continue;
            }

            String name = null, message = null;

            // 신버전: [이름] [오전/오후 HH:MM] 메시지
            Matcher newMatcher = TXT_PATTERN_NEW.matcher(trimmed);
            if (newMatcher.matches()) {
                name = newMatcher.group(1).trim();
                message = maskSensitiveInfo(newMatcher.group(3).trim());
            } else {
                // 구버전: 오전/오후 HH:MM, 이름 : 메시지
                Matcher oldMatcher = TXT_PATTERN_OLD.matcher(trimmed);
                if (oldMatcher.matches()) {
                    name = oldMatcher.group(2).trim();
                    message = maskSensitiveInfo(oldMatcher.group(3).trim());
                }
            }

            if (name != null && message != null && !isSkippable(message)) {
                messagesByParticipant.computeIfAbsent(name, k -> new ArrayList<>()).add(message);
                orderedTurns.add(new ConversationTurn(name, message, currentDate));
            }
        }

        return buildResult(sessionId, messagesByParticipant, orderedTurns);
    }

    private ParsedChat parseCsv(String content, String sessionId) {
        Map<String, List<String>> messagesByParticipant = new LinkedHashMap<>();
        List<ConversationTurn> orderedTurns = new ArrayList<>();
        String[] lines = content.split("\n");

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            int firstComma = line.indexOf(',');
            if (firstComma == -1) continue;

            String dateField = line.substring(0, firstComma).trim();
            Matcher csvDateMatcher = CSV_DATE_PATTERN.matcher(dateField);
            if (!csvDateMatcher.matches()) continue;

            // "2024-03-01" → "2024년 3월 1일"
            int year  = Integer.parseInt(csvDateMatcher.group(1));
            int month = Integer.parseInt(csvDateMatcher.group(2));
            int day   = Integer.parseInt(csvDateMatcher.group(3));
            String date = year + "년 " + month + "월 " + day + "일";

            int secondComma = line.indexOf(',', firstComma + 1);
            if (secondComma == -1) continue;

            String name = stripQuotes(line.substring(firstComma + 1, secondComma).trim());
            String message = stripQuotes(line.substring(secondComma + 1).trim());
            message = message.replace("\"\"", "\"");
            message = maskSensitiveInfo(message);

            if (!name.isEmpty() && !isSkippable(message)) {
                messagesByParticipant.computeIfAbsent(name, k -> new ArrayList<>()).add(message);
                orderedTurns.add(new ConversationTurn(name, message, date));
            }
        }

        return buildResult(sessionId, messagesByParticipant, orderedTurns);
    }

    private ParsedChat buildResult(String sessionId,
                                    Map<String, List<String>> messagesByParticipant,
                                    List<ConversationTurn> orderedTurns) {
        messagesByParticipant.entrySet().removeIf(e -> e.getValue().isEmpty());
        return ParsedChat.builder()
            .sessionId(sessionId)
            .participants(new ArrayList<>(messagesByParticipant.keySet()))
            .messagesByParticipant(messagesByParticipant)
            .orderedTurns(orderedTurns)
            .build();
    }

    private String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
            return s.substring(1, s.length() - 1);
        return s;
    }

    private String maskSensitiveInfo(String message) {
        String masked = PHONE_PATTERN.matcher(message).replaceAll("***-****-****");
        masked = ADDRESS_PATTERN.matcher(masked).replaceAll("[주소]");
        return masked;
    }

    private boolean isSkippable(String message) {
        return message.isEmpty() ||
               message.equals("이모티콘") || message.equals("사진") ||
               message.equals("동영상") || message.equals("파일") ||
               message.equals("음성메시지") ||
               message.startsWith("http") ||
               message.length() > 500;
    }
}
