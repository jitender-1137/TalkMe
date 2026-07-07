package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.Message;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.MessageType;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.MessageRepository;
import com.chat.talkMe.service.SmartReplyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Generates multilingual reply suggestions via OpenAI-compatible chat APIs, with
 * a <b>primary → fallback</b> provider chain:
 * <ul>
 *   <li><b>Primary:</b> Groq (free, fast, {@code llama-3.1-8b-instant} by default).</li>
 *   <li><b>Fallback:</b> Hugging Face router — tried automatically only when the
 *       primary errors, rate-limits, times out, or returns nothing.</li>
 * </ul>
 * Both are OpenAI-compatible, so any such provider can be swapped in by config.
 *
 * <p><b>Privacy:</b> the recent conversation IS sent to these external providers
 * (it leaves your network). It's never logged here, and if both providers fail
 * the endpoint returns an empty list so the client falls back to local
 * rule-based suggestions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartReplyServiceImpl implements SmartReplyService {

    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.enabled:false}")
    private boolean enabled;

    @Value("${app.ai.context-messages:10}")
    private int contextMessages;

    @Value("${app.ai.suggestions:3}")
    private int suggestionCount;

    @Value("${app.ai.timeout-ms:8000}")
    private long timeoutMs;

    // Primary provider (Groq by default).
    @Value("${app.ai.primary.base-url:https://api.groq.com/openai/v1}")
    private String primaryBaseUrl;
    @Value("${app.ai.primary.api-key:}")
    private String primaryApiKey;
    @Value("${app.ai.primary.model:llama-3.1-8b-instant}")
    private String primaryModel;

    // Fallback provider (Hugging Face router by default). Used only if primary fails.
    @Value("${app.ai.fallback.base-url:https://router.huggingface.co/v1}")
    private String fallbackBaseUrl;
    @Value("${app.ai.fallback.api-key:}")
    private String fallbackApiKey;
    @Value("${app.ai.fallback.model:Qwen/Qwen2.5-7B-Instruct}")
    private String fallbackModel;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private static final String SYSTEM_PROMPT = """
            You suggest short replies for a chat app. Given a one-to-one conversation, propose \
            replies that the participant labelled "Me" could send as the very next message.
            Rules:
            - Mirror the user's EXACT language, script and style. If they write a romanized or \
            code-mixed language — e.g. Hinglish (Hindi written in Latin letters, like "kya kr rahe ho"), \
            Tanglish, Arabizi, Spanglish — reply in that SAME romanized/mixed style. NEVER convert it to \
            the native script (e.g. Devanagari) and NEVER switch to English unless the user did.
            - Keep each reply short and natural, like a real chat message (a few words up to one sentence). \
            Casual, friendly tone; a light emoji is fine.
            - Every reply MUST directly and specifically respond to the LAST message from "Them". \
            Stay on-topic and answer what was actually said — no generic filler.
            - Make the replies clearly different from one another (e.g. a positive, a neutral/clarifying, \
            and a hesitant or negative option when it fits).
            - Respond with ONLY a JSON object of the form {"replies": ["...", "..."]} and nothing else.""";

    // One-shot example anchors the hardest case: romanized/code-mixed input must
    // produce romanized/code-mixed output (not Devanagari, not English).
    private static final String EXAMPLE_USER = """
            Conversation so far (oldest to newest):
            Them: kya kr rahe ho

            Suggest 3 different replies for "Me" to send next, in the conversation's language.""";
    private static final String EXAMPLE_ASSISTANT =
            "{\"replies\": [\"Bas chill kar raha hu 😄 tum batao?\", \"Kuch khaas nahi, tumhara kya plan hai?\", \"Abhi free hu, milne ka mann hai kya?\"]}";

    @Override
    @Transactional(readOnly = true)
    public List<String> suggestReplies(String chatUuid, User currentUser) {
        if (!enabled) {
            log.debug("smart-reply: disabled (app.ai.enabled=false)");
            return Collections.emptyList();
        }

        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        ChatMember member = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        // Newest-first, visibility-filtered (cleared/blocked/held already excluded).
        List<Message> recent = messageRepository.findMessagesBeforeCursor(
                chat, currentUser.getId(), member.getClearedAt(), member.getLeftAt(), null,
                PageRequest.of(0, Math.max(2, contextMessages)));

        // Build chronological text-only turns.
        List<String[]> turns = new ArrayList<>(); // [label, text]
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message m = recent.get(i);
            if (m.getMessageType() != MessageType.TEXT) continue;
            String text = m.getContent() == null ? "" : m.getContent().replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) continue;
            if (text.length() > 500) text = text.substring(0, 500);
            boolean fromMe = m.getSender() != null && m.getSender().getId().equals(currentUser.getId());
            turns.add(new String[]{fromMe ? "Me" : "Them", text});
        }

        // Nothing to reply to, or we sent the last message (not our turn).
        if (turns.isEmpty()) {
            log.info("smart-reply: no usable text messages in chat {} — nothing to reply to", chatUuid);
            return Collections.emptyList();
        }
        if ("Me".equals(turns.get(turns.size() - 1)[0])) {
            log.info("smart-reply: last message in chat {} is yours — no suggestions until they reply", chatUuid);
            return Collections.emptyList();
        }

        String convo = buildConvo(turns);

        // Primary (Groq) → fallback (HF) only if primary yields nothing.
        List<String> out = tryProvider("primary", primaryBaseUrl, primaryApiKey, primaryModel, convo, chatUuid);
        if (out.isEmpty() && isConfigured(fallbackBaseUrl, fallbackApiKey, fallbackModel)) {
            log.info("smart-reply: primary produced nothing for chat {} — trying fallback provider", chatUuid);
            out = tryProvider("fallback", fallbackBaseUrl, fallbackApiKey, fallbackModel, convo, chatUuid);
        }
        log.info("smart-reply: returning {} suggestion(s) for chat {}", out.size(), chatUuid);
        return out;
    }

    private boolean isConfigured(String baseUrl, String apiKey, String model) {
        return baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank();
    }

    /** Call one provider; never throws — a failure returns an empty list so the chain can continue. */
    private List<String> tryProvider(String label, String baseUrl, String apiKey, String model,
                                     String convo, String chatUuid) {
        try {
            return callProvider(baseUrl, apiKey, model, convo);
        } catch (Exception e) {
            // Never log message content — only the failure reason (timeout / auth / rate limit).
            log.warn("smart-reply: {} provider ({}) failed for chat {} ({}: {})",
                    label, model, chatUuid, e.getClass().getSimpleName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private String buildConvo(List<String[]> turns) {
        String lastMsg = turns.get(turns.size() - 1)[1];
        StringBuilder convo = new StringBuilder("Conversation so far (oldest to newest):\n");
        for (String[] turn : turns) convo.append(turn[0]).append(": ").append(turn[1]).append('\n');
        convo.append("\nThem's LAST message (reply directly to THIS): \"").append(lastMsg).append("\"\n");
        convo.append("Suggest ").append(suggestionCount)
                .append(" different replies for \"Me\" to send next. Each must be a natural, on-topic reply to that last message, in the SAME language and script (keep Hinglish romanized). ")
                .append("Return JSON: {\"replies\": [ ... ]}.");
        return convo.toString();
    }

    private List<String> callProvider(String baseUrl, String apiKey, String model, String convo) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.4);
        body.put("top_p", 0.9);
        body.put("max_tokens", 80 + suggestionCount * 48);
        // Force valid JSON output (OpenAI-compatible; supported by Groq & HF router).
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        // Few-shot: teach romanized/code-mixed mirroring (e.g. Hinglish).
        messages.addObject().put("role", "user").put("content", EXAMPLE_USER);
        messages.addObject().put("role", "assistant").put("content", EXAMPLE_ASSISTANT);
        messages.addObject().put("role", "user").put("content", convo);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (apiKey != null && !apiKey.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> resp = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            // Body carries the provider error (bad key / rate limit) — not user content.
            log.warn("smart-reply: provider {} returned HTTP {} — {}", model, resp.statusCode(),
                    resp.body() == null ? "" : resp.body().replaceAll("\\s+", " ").trim());
            return Collections.emptyList();
        }

        JsonNode root = objectMapper.readTree(resp.body());
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        List<String> parsed = parseSuggestions(content);
        if (parsed.isEmpty()) {
            String snippet = content.length() > 200 ? content.substring(0, 200) : content;
            log.warn("smart-reply: provider {} produced no usable suggestions (raw output: {})", model, snippet);
        }
        return parsed;
    }

    /** Extract reply strings from the model output (object wrapping an array, bare array, or lines). */
    private List<String> parseSuggestions(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isBlank()) return out;

        // 1) Parse the whole thing as JSON (response_format guarantees valid JSON).
        try {
            collectStrings(objectMapper.readTree(content), out);
        } catch (Exception ignored) {
            // not pure JSON — fall through
        }
        // 2) Otherwise pull the first [...] array out of noisy text.
        if (out.isEmpty()) {
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start >= 0 && end > start) {
                try {
                    collectStrings(objectMapper.readTree(content.substring(start, end + 1)), out);
                } catch (Exception ignored) { /* fall through */ }
            }
        }
        // 3) Last resort: treat each line as a suggestion.
        if (out.isEmpty()) {
            for (String line : content.split("\\r?\\n")) {
                addClean(out, line.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", "").replaceAll("^[\"']|[\"']$", ""));
            }
        }
        return out.size() > suggestionCount ? out.subList(0, suggestionCount) : out;
    }

    /** Walk a JSON node collecting string leaves (handles array, or object-wrapping-array). */
    private void collectStrings(JsonNode node, List<String> out) {
        if (node == null) return;
        if (node.isTextual()) {
            addClean(out, node.asText());
        } else if (node.isArray() || node.isObject()) {
            for (JsonNode child : node) collectStrings(child, out);
        }
    }

    private void addClean(List<String> out, String s) {
        if (s == null) return;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 120) return;
        if (out.stream().noneMatch(e -> e.equalsIgnoreCase(t))) out.add(t);
    }
}
