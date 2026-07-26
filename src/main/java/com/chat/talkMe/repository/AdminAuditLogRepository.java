package com.chat.talkMe.repository;

import com.chat.talkMe.domain.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminAuditLogRepository
        extends JpaRepository<AdminAuditLog, Long>, JpaSpecificationExecutor<AdminAuditLog> {
    Page<AdminAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
