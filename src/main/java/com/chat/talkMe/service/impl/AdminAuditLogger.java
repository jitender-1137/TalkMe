package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.AdminAuditLog;
import com.chat.talkMe.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes admin audit rows in their OWN transaction, isolated from the caller.
 *
 * <p>Why a separate bean + {@code REQUIRES_NEW}: many admin reads are
 * {@code @Transactional(readOnly = true)} but still record a VIEW_* audit row. An INSERT
 * on a read-only connection makes Postgres abort the ENTIRE transaction
 * ("cannot execute INSERT in a read-only transaction" → "current transaction is aborted"),
 * which then fails the actual query. Running the insert in a suspended, fresh read-write
 * transaction means the audit write succeeds independently and can never poison the
 * caller's transaction. Self-invoked {@code @Transactional} wouldn't apply — it must be a
 * call through a distinct proxied bean.
 */
@Component
@RequiredArgsConstructor
public class AdminAuditLogger {

    private final AdminAuditLogRepository auditRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String admin, String action, String targetType, String targetId, String detail) {
        auditRepository.save(AdminAuditLog.builder()
                .adminUsername(admin != null ? admin : "unknown")
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .detail(detail)
                .build());
    }
}
