package com.chat.talkMe.repository;

import com.chat.talkMe.domain.SecretCrush;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.SecretCrushStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link SecretCrush} (feature #9).
 *
 * <p>SECRECY INVARIANT — this repository must NEVER expose a "find crushes on this target"
 * query (e.g. {@code findByTarget} / {@code countByTarget}). Enumerating the crushers of a
 * target would reveal one-sided crushes and break the whole feature. Reciprocity is checked
 * only in the narrow, symmetric form {@link #findByCrusherAndTargetAndStatus} — i.e. "does
 * <em>this specific pair</em> crush back?" — which never lists who crushes on someone.
 */
@Repository
public interface SecretCrushRepository extends JpaRepository<SecretCrush, Long> {

    /** The (at most one) crush row for a given directed pair, any status. */
    Optional<SecretCrush> findByCrusherAndTarget(User crusher, User target);

    /** Directed pair lookup narrowed to a status — used for the reciprocity check. */
    Optional<SecretCrush> findByCrusherAndTargetAndStatus(User crusher, User target, SecretCrushStatus status);

    /** How many crushes THIS user currently holds in a given status (rate-limit / cap). */
    long countByCrusherAndStatus(User crusher, SecretCrushStatus status);

    /** THIS user's own outgoing crushes in a given status. Only ever the caller's own rows. */
    List<SecretCrush> findByCrusherAndStatus(User crusher, SecretCrushStatus status);
}
