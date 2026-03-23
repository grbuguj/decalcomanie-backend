package com.torchers.decalcomanie.service;

import com.torchers.decalcomanie.model.ParsedChat;
import com.torchers.decalcomanie.model.SessionData;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionStoreService {

    // 메모리에만 저장 - 서버 재시작 시 자동 삭제
    private final Map<String, ParsedChat> parsedChats = new ConcurrentHashMap<>();
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    public void storeParsedChat(String sessionId, ParsedChat chat) {
        parsedChats.put(sessionId, chat);
    }

    public ParsedChat getParsedChat(String sessionId) {
        return parsedChats.get(sessionId);
    }

    public void storeSession(String sessionId, SessionData data) {
        sessions.put(sessionId, data);
    }

    public SessionData getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void deleteSession(String sessionId) {
        parsedChats.remove(sessionId);
        sessions.remove(sessionId);
    }

    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }
}
