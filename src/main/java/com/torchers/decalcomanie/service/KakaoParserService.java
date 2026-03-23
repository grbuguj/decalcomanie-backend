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

    private static final Pattern TXT_PATTERN =
        Pattern.compile("^(오전|오후) \\d{1,2}:\\d{2}, (.+?) : (.+)$");

    private static final Pattern DATE_PATTERN =
        Pattern.compile("^\"?\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\"?$");

    private static final Pattern PHONE_PATTERN =
        Pattern.compile("\\d{3}-\\d{3,4}-\\d{4}");

    public ParsedChat parse(InputStream inputStream, String sessionId, String filename) throws Exception {
        byte[] bytes = inputStream.readAllBytes();
        String charset = detectCharset(bytes);
        String content = new String(bytes, Charset.forName(charset));
        if (content.startsWith("\uFEFF")) content = content.substring(1);

        boolean isCsv = filename != null && filename.toLowerCase().endsWith(".csv");
        return isCsv ? parseCsv(content, sessionId) : parseTxt(content, sessionId);
    }

    private String detectCharset(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return "UTF-8";
        }
        try {
            String test = new String(bytes, "UTF-8");
            if (test.matches(".*[가-힣].*")) return "UTF-8";
        } catch (Exception ignored) {}
        return "EUC-KR";
    }

    private ParsedChat parseTxt(String content, String sessionId) {
        Map<String, List<String>> messagesByParticipant = new LinkedHashMap<>();
        List<ConversationTurn> orderedTurns = new ArrayList<>();

        for (String line : content.split("\n")) {
            Matcher matcher = TXT_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                String name = matcher.group(2).trim();
                String message = maskSensitiveInfo(matcher.group(3).trim());
                if (!isSkippable(message)) {
                    messagesByParticipant.computeIfAbsent(name, k -> new ArrayList<>()).add(message);
                    orderedTurns.add(new ConversationTurn(name, message));
                }
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
            if (!DATE_PATTERN.matcher(dateField).matches()) continue;

            int secondComma = line.indexOf(',', firstComma + 1);
            if (secondComma == -1) continue;

            String name = stripQuotes(line.substring(firstComma + 1, secondComma).trim());
            String message = stripQuotes(line.substring(secondComma + 1).trim());
            message = message.replace("\"\"", "\"");
            message = maskSensitiveInfo(message);

            if (!name.isEmpty() && !isSkippable(message)) {
                messagesByParticipant.computeIfAbsent(name, k -> new ArrayList<>()).add(message);
                orderedTurns.add(new ConversationTurn(name, message));
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
        return PHONE_PATTERN.matcher(message).replaceAll("***-****-****");
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
