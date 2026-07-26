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

@Service
@RequiredArgsConstructor
@Transactional
public class GameServiceImpl implements GameService {

    private final GameSessionRepository gameSessionRepository;
    private final ReputationRecorder reputationRecorder;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;

    /** IDOR guard: the caller must be a member of the chat the game runs in. */
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

    @Override
    public GameSessionResponse end(User user, String gameSessionUuid) {
        GameSession session = load(gameSessionUuid);
        requireChatMember(user, session.getChatId());
        session.setState(GameState.ENDED);
        session = gameSessionRepository.save(session);
        return GameSessionResponse.from(session);
    }

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
