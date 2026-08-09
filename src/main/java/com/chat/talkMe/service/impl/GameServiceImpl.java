package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.GameSession;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.GameSessionResponse;
import com.chat.talkMe.enums.GameState;
import com.chat.talkMe.enums.GameType;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.GameSessionRepository;
import com.chat.talkMe.service.GamePromptBank;
import com.chat.talkMe.service.GameService;
import com.chat.talkMe.service.ReputationRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Conversation "Together games" engine. Serves prompts from the static {@link GamePromptBank} and
 * drives one live {@link GameSession} per chat over REST (start/next/end/active). Every operation
 * is authorized against chat membership (IDOR guard). Class-level {@code @Transactional}; reads are
 * overridden read-only. Starting a game records a CONVERSATION_STARTED reputation event.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class GameServiceImpl implements GameService {

    private final GameSessionRepository gameSessionRepository;
    private final ReputationRecorder reputationRecorder;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;

    /**
     * IDOR guard: assert the caller is a member of the chat the game runs in.
     *
     * @param user   the caller
     * @param chatId uuid string of the chat
     * @throws com.chat.talkMe.exception.BadRequestException if chatId is not a valid uuid
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a member
     */
    private void requireChatMember(User user, String chatId) {
        boolean member;
        try {
            member = chatRepository.findByUuid(UUID.fromString(chatId))
                    .flatMap(c -> chatMemberRepository.findByChatAndUser(c, user))
                    .isPresent();
        } catch (IllegalArgumentException badUuid) {
            throw new BadRequestException("Invalid chat id", "TM_400");
        }
        if (!member) {
            throw new ForbiddenException("You are not a member of this chat", "TM_103");
        }
    }

    /**
     * Start a new game in a chat: retire any existing live session, create an IN_PROGRESS session at
     * the first prompt, and record a CONVERSATION_STARTED reputation event keyed on the chat.
     *
     * @param user     the caller (must be a chat member)
     * @param chatId   uuid string of the chat
     * @param gameType the game to start
     * @return the new game session as a response DTO
     * @throws com.chat.talkMe.exception.BadRequestException if chatId/gameType are missing/invalid or
     *                                                       the game has no prompts
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a chat member
     */
    @Override
    public GameSessionResponse start(User user, String chatId, GameType gameType) {
        if (chatId == null || chatId.isBlank()) {
            throw new BadRequestException("chatId is required", "TM_400");
        }
        if (gameType == null) {
            throw new BadRequestException("gameType is required", "TM_400");
        }
        if (GamePromptBank.size(gameType) == 0) {
            throw new BadRequestException("No prompts available for this game", "TM_400");
        }
        requireChatMember(user, chatId);

        // Only one live game per chat — retire any existing non-ended session first.
        gameSessionRepository.findFirstByChatIdAndStateNotOrderByIdDesc(chatId, GameState.ENDED)
                .ifPresent(existing -> {
                    existing.setState(GameState.ENDED);
                    gameSessionRepository.save(existing);
                });

        GameSession session = GameSession.builder()
                .chatId(chatId)
                .gameType(gameType)
                .state(GameState.IN_PROGRESS)
                .currentRound(0)
                .currentPromptId(gameType.name() + "#0")
                .build();
        session = gameSessionRepository.save(session);

        // Starting a game is a genuine conversation-starter signal. Key the sourceRef on
        // the chat (not the session) so perSourceCap collapses repeated starts per chat.
        reputationRecorder.record(user.getId(), ReputationEventType.CONVERSATION_STARTED, "game:" + chatId);

        return GameSessionResponse.from(session);
    }

    /**
     * Advance a live game to the next prompt, ending the session once the prompt bank is exhausted.
     *
     * @param user            the caller (must be a chat member)
     * @param gameSessionUuid uuid string of the session
     * @return the updated game session as a response DTO
     * @throws com.chat.talkMe.exception.BadRequestException if the id is invalid or the game is not
     *                                                       in progress
     * @throws com.chat.talkMe.exception.NotFoundException   if the session does not exist
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a chat member
     */
    @Override
    public GameSessionResponse next(User user, String gameSessionUuid) {
        GameSession session = load(gameSessionUuid);
        requireChatMember(user, session.getChatId());
        if (session.getState() != GameState.IN_PROGRESS) {
            throw new BadRequestException("Game is not in progress", "TM_400");
        }

        int nextRound = session.getCurrentRound() + 1;
        if (nextRound >= GamePromptBank.size(session.getGameType())) {
            // Bank exhausted — end the game.
            session.setState(GameState.ENDED);
        } else {
            session.setCurrentRound(nextRound);
            session.setCurrentPromptId(session.getGameType().name() + "#" + nextRound);
        }
        session = gameSessionRepository.save(session);
        return GameSessionResponse.from(session);
    }

    /**
     * End a game session (mark it ENDED).
     *
     * @param user            the caller (must be a chat member)
     * @param gameSessionUuid uuid string of the session
     * @return the ended game session as a response DTO
     * @throws com.chat.talkMe.exception.BadRequestException if the id is invalid
     * @throws com.chat.talkMe.exception.NotFoundException   if the session does not exist
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a chat member
     */
    @Override
    public GameSessionResponse end(User user, String gameSessionUuid) {
        GameSession session = load(gameSessionUuid);
        requireChatMember(user, session.getChatId());
        session.setState(GameState.ENDED);
        session = gameSessionRepository.save(session);
        return GameSessionResponse.from(session);
    }

    /**
     * Return the chat's current live (non-ended) game session, or null if none.
     *
     * @param user   the caller (must be a chat member)
     * @param chatId uuid string of the chat
     * @return the active game session as a response DTO, or null
     * @throws com.chat.talkMe.exception.BadRequestException if chatId is missing/invalid
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a chat member
     */
    @Override
    @Transactional(readOnly = true)
    public GameSessionResponse active(User user, String chatId) {
        if (chatId == null || chatId.isBlank()) {
            throw new BadRequestException("chatId is required", "TM_400");
        }
        requireChatMember(user, chatId);
        Optional<GameSession> session =
                gameSessionRepository.findFirstByChatIdAndStateNotOrderByIdDesc(chatId, GameState.ENDED);
        return session.map(GameSessionResponse::from).orElse(null);
    }

    /**
     * Load a game session by uuid string.
     *
     * @param gameSessionUuid uuid string of the session
     * @return the session entity
     * @throws com.chat.talkMe.exception.BadRequestException if the id is not a valid uuid
     * @throws com.chat.talkMe.exception.NotFoundException   if no such session exists
     */
    private GameSession load(String gameSessionUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(gameSessionUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid game session id", "TM_400");
        }
        return gameSessionRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException("Game session not found"));
    }
}
