package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.AdminAuditLog;
import com.chat.talkMe.repository.AdminAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Pure Mockito unit test for {@link AdminAuditLogger}. The bean's whole job is a single
 * {@code REQUIRES_NEW} INSERT of an {@link AdminAuditLog} row; the transactional isolation
 * is a Spring proxy concern (not unit-testable), so we assert the persisted row's shape —
 * including the {@code null admin → "unknown"} defaulting — via an {@link ArgumentCaptor}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAuditLogger (unit)")
class AdminAuditLoggerTest {

    @Mock private AdminAuditLogRepository auditRepository;

    private AdminAuditLogger logger;

    @BeforeEach
    void setUp() {
        logger = new AdminAuditLogger(auditRepository);
    }

    @Nested
    @DisplayName("write")
    class Write {

        @Test
        @DisplayName("persists an audit row carrying every field verbatim")
        void persistsAllFields() {
            logger.write("root", "BAN_USER", "USER", "u-123", "@victim");

            ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
            verify(auditRepository).save(captor.capture());
            AdminAuditLog row = captor.getValue();
            assertThat(row.getAdminUsername()).isEqualTo("root");
            assertThat(row.getAction()).isEqualTo("BAN_USER");
            assertThat(row.getTargetType()).isEqualTo("USER");
            assertThat(row.getTargetId()).isEqualTo("u-123");
            assertThat(row.getDetail()).isEqualTo("@victim");
        }

        @Test
        @DisplayName("null admin is defaulted to \"unknown\"")
        void nullAdminDefaultsToUnknown() {
            logger.write(null, "VIEW_MESSAGES", "CHAT", "c-1", "page=0");

            ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
            verify(auditRepository).save(captor.capture());
            assertThat(captor.getValue().getAdminUsername()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("null optional fields (detail) are persisted as null, not defaulted")
        void nullOptionalFieldsPassThrough() {
            logger.write("mod", "DELETE_CHAT", "CHAT", "c-9", null);

            ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
            verify(auditRepository).save(captor.capture());
            AdminAuditLog row = captor.getValue();
            assertThat(row.getDetail()).isNull();
            assertThat(row.getAdminUsername()).isEqualTo("mod");
        }
    }
}
