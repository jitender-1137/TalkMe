-- ============================================================================
-- Remove the (deleted) AI-bot feature from the database.
--
-- The bot feature was dropped from the code; this purges its data + schema.
-- Schema is managed by Hibernate ddl-auto=update, which NEVER drops columns,
-- so the `users.is_bot` / `users.bot_persona` columns must be dropped by hand
-- (this script), and any leftover bot accounts + their data removed.
--
-- Bots were ordinary `users` rows (is_bot = true). Deleting them cascades into
-- the human↔bot DM chats they belonged to (whole chat removed, incl. the human's
-- side) and any content they interacted with. All FKs are NO ACTION, so children
-- are deleted explicitly, leaf-to-root, inside one transaction.
--
-- Idempotent: re-running after the columns are dropped is a no-op (guarded).
-- Run:  psql -U <user> -d <db> -f remove_bots.sql
-- ============================================================================

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'users' AND column_name = 'is_bot'
  ) THEN
    RAISE NOTICE 'is_bot column already gone — nothing to do.';
    RETURN;
  END IF;

  -- Working sets -------------------------------------------------------------
  CREATE TEMP TABLE _bot_ids   ON COMMIT DROP AS SELECT id FROM users WHERE is_bot = true;
  CREATE TEMP TABLE _bot_chats ON COMMIT DROP AS
      SELECT DISTINCT chat_id AS id FROM chat_members WHERE user_id IN (SELECT id FROM _bot_ids);

  -- 1. Whole chats a bot belonged to (human↔bot DMs) — messages + children ----
  DELETE FROM message_attachments   WHERE message_id IN (SELECT id FROM messages WHERE chat_id IN (SELECT id FROM _bot_chats));
  DELETE FROM message_deleted_for   WHERE message_id IN (SELECT id FROM messages WHERE chat_id IN (SELECT id FROM _bot_chats));
  DELETE FROM message_mentions      WHERE message_id IN (SELECT id FROM messages WHERE chat_id IN (SELECT id FROM _bot_chats));
  DELETE FROM message_reactions     WHERE message_id IN (SELECT id FROM messages WHERE chat_id IN (SELECT id FROM _bot_chats));
  DELETE FROM message_read_receipts WHERE message_id IN (SELECT id FROM messages WHERE chat_id IN (SELECT id FROM _bot_chats));
  DELETE FROM message_stars         WHERE message_id IN (SELECT id FROM messages WHERE chat_id IN (SELECT id FROM _bot_chats));
  DELETE FROM messages              WHERE chat_id IN (SELECT id FROM _bot_chats);
  DELETE FROM chat_explicit_consent WHERE chat_id IN (SELECT id FROM _bot_chats);
  DELETE FROM chat_members          WHERE chat_id IN (SELECT id FROM _bot_chats);
  DELETE FROM chat_tags             WHERE chat_id IN (SELECT id FROM _bot_chats);
  DELETE FROM chats                 WHERE id      IN (SELECT id FROM _bot_chats);

  -- 2. Bot interactions on OTHER users' messages (outside the deleted chats) --
  DELETE FROM message_reactions     WHERE user_id          IN (SELECT id FROM _bot_ids);
  DELETE FROM message_read_receipts WHERE user_id          IN (SELECT id FROM _bot_ids);
  DELETE FROM message_stars         WHERE user_id          IN (SELECT id FROM _bot_ids);
  DELETE FROM message_deleted_for   WHERE user_id          IN (SELECT id FROM _bot_ids);
  DELETE FROM message_mentions      WHERE mentioned_user_id IN (SELECT id FROM _bot_ids);

  -- 3. Feed content: bot-owned posts/polls/stories, then bot interactions -----
  DELETE FROM poll_votes    WHERE poll_id IN (SELECT id FROM polls WHERE post_id IN (SELECT id FROM posts WHERE user_id IN (SELECT id FROM _bot_ids)));
  DELETE FROM poll_options  WHERE poll_id IN (SELECT id FROM polls WHERE post_id IN (SELECT id FROM posts WHERE user_id IN (SELECT id FROM _bot_ids)));
  DELETE FROM polls         WHERE post_id IN (SELECT id FROM posts WHERE user_id IN (SELECT id FROM _bot_ids));
  DELETE FROM post_comment_likes WHERE comment_id IN (SELECT id FROM post_comments WHERE post_id IN (SELECT id FROM posts WHERE user_id IN (SELECT id FROM _bot_ids)));
  DELETE FROM post_comments WHERE post_id IN (SELECT id FROM posts WHERE user_id IN (SELECT id FROM _bot_ids));
  DELETE FROM post_likes    WHERE post_id IN (SELECT id FROM posts WHERE user_id IN (SELECT id FROM _bot_ids));
  DELETE FROM post_bookmarks WHERE post_id IN (SELECT id FROM posts WHERE user_id IN (SELECT id FROM _bot_ids));
  DELETE FROM post_media    WHERE post_id IN (SELECT id FROM posts WHERE user_id IN (SELECT id FROM _bot_ids));
  DELETE FROM posts         WHERE user_id IN (SELECT id FROM _bot_ids);

  DELETE FROM story_views   WHERE story_id IN (SELECT id FROM stories WHERE user_id IN (SELECT id FROM _bot_ids));
  DELETE FROM stories       WHERE user_id IN (SELECT id FROM _bot_ids);

  DELETE FROM post_comment_likes WHERE user_id IN (SELECT id FROM _bot_ids);
  DELETE FROM post_comments WHERE parent_id IN (SELECT id FROM post_comments WHERE user_id IN (SELECT id FROM _bot_ids));
  DELETE FROM post_comments WHERE user_id  IN (SELECT id FROM _bot_ids);
  DELETE FROM post_likes    WHERE user_id  IN (SELECT id FROM _bot_ids);
  DELETE FROM post_bookmarks WHERE user_id IN (SELECT id FROM _bot_ids);
  DELETE FROM poll_votes    WHERE user_id  IN (SELECT id FROM _bot_ids);
  DELETE FROM story_views   WHERE user_id  IN (SELECT id FROM _bot_ids);

  -- 4. Match / stranger tables (bots don't matchmake; kept for completeness) --
  DELETE FROM match_messages         WHERE sender_id   IN (SELECT id FROM _bot_ids);
  DELETE FROM match_reports          WHERE reporter_id IN (SELECT id FROM _bot_ids) OR reported_id IN (SELECT id FROM _bot_ids);
  DELETE FROM match_requests         WHERE user_id     IN (SELECT id FROM _bot_ids);
  DELETE FROM match_rooms            WHERE user1_id    IN (SELECT id FROM _bot_ids) OR user2_id    IN (SELECT id FROM _bot_ids);
  DELETE FROM match_sessions         WHERE host_id     IN (SELECT id FROM _bot_ids) OR peer_id     IN (SELECT id FROM _bot_ids);
  DELETE FROM stranger_messages      WHERE sender_id   IN (SELECT id FROM _bot_ids);
  DELETE FROM stranger_rooms         WHERE user1_id    IN (SELECT id FROM _bot_ids) OR user2_id    IN (SELECT id FROM _bot_ids);
  DELETE FROM stranger_talk_messages WHERE sender_id   IN (SELECT id FROM _bot_ids);
  DELETE FROM stranger_talk_reports  WHERE reporter_id IN (SELECT id FROM _bot_ids) OR reported_id IN (SELECT id FROM _bot_ids);
  DELETE FROM stranger_talk_rooms    WHERE user1_id    IN (SELECT id FROM _bot_ids) OR user2_id    IN (SELECT id FROM _bot_ids);

  -- 5. Per-user tables --------------------------------------------------------
  DELETE FROM user_roles      WHERE user_id     IN (SELECT id FROM _bot_ids);
  DELETE FROM user_settings   WHERE user_id     IN (SELECT id FROM _bot_ids);
  DELETE FROM user_interests  WHERE user_id     IN (SELECT id FROM _bot_ids);
  DELETE FROM user_presences  WHERE user_id     IN (SELECT id FROM _bot_ids);
  DELETE FROM user_follows    WHERE follower_id IN (SELECT id FROM _bot_ids) OR following_id IN (SELECT id FROM _bot_ids);
  DELETE FROM friends         WHERE user_id     IN (SELECT id FROM _bot_ids) OR friend_id   IN (SELECT id FROM _bot_ids);
  DELETE FROM friend_requests WHERE sender_id   IN (SELECT id FROM _bot_ids) OR receiver_id IN (SELECT id FROM _bot_ids);
  DELETE FROM blocked_users   WHERE user_id     IN (SELECT id FROM _bot_ids) OR blocked_id  IN (SELECT id FROM _bot_ids);
  DELETE FROM devices             WHERE user_id IN (SELECT id FROM _bot_ids);
  DELETE FROM sessions            WHERE user_id IN (SELECT id FROM _bot_ids);
  DELETE FROM refresh_tokens      WHERE user_id IN (SELECT id FROM _bot_ids);
  DELETE FROM push_subscriptions  WHERE user_id IN (SELECT id FROM _bot_ids);
  DELETE FROM notifications       WHERE user_id IN (SELECT id FROM _bot_ids);
  DELETE FROM profile_views   WHERE viewer_id   IN (SELECT id FROM _bot_ids) OR viewed_id     IN (SELECT id FROM _bot_ids);
  DELETE FROM discover_likes  WHERE user_id     IN (SELECT id FROM _bot_ids) OR liked_user_id IN (SELECT id FROM _bot_ids);
  DELETE FROM audit_logs      WHERE actor_id    IN (SELECT id FROM _bot_ids);

  -- 6. The bot accounts themselves -------------------------------------------
  DELETE FROM users WHERE id IN (SELECT id FROM _bot_ids);
END $$;

-- 7. Drop the bot columns (outside the DO block; DDL is transactional in PG) --
ALTER TABLE users DROP COLUMN IF EXISTS is_bot;
ALTER TABLE users DROP COLUMN IF EXISTS bot_persona;
