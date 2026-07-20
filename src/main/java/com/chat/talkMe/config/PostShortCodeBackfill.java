package com.chat.talkMe.config;

import com.chat.talkMe.domain.Post;
import com.chat.talkMe.repository.PostRepository;
import com.chat.talkMe.util.ShortCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-time backfill: assigns a short code to every pre-existing post that doesn't
 * have one yet, so old posts also get shareable /post/{code} links. New posts get
 * a code at creation time, so after this runs once there's nothing left to do.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostShortCodeBackfill implements ApplicationRunner {

    private final PostRepository postRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Post> missing = postRepository.findByShortCodeIsNull();
        if (missing.isEmpty()) {
            return;
        }
        for (Post post : missing) {
            post.setShortCode(ShortCodes.unique(c -> !postRepository.existsByShortCode(c)));
        }
        postRepository.saveAll(missing);
        log.info("Backfilled short codes for {} existing post(s)", missing.size());
    }
}
