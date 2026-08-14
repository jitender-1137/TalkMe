package com.neo.chat.config;

import com.neo.chat.domain.Post;
import com.neo.chat.repository.PostRepository;
import com.neo.chat.util.ShortCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
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

    /**
     * Runs once at startup ({@link ApplicationRunner}, transactional): finds every post with a null
     * short code and assigns a freshly-generated unique code via
     * {@link com.neo.chat.util.ShortCodes#unique(java.util.function.Predicate)}, checking
     * uniqueness against the repository. Idempotent — a no-op once every post has a code.
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) {
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
