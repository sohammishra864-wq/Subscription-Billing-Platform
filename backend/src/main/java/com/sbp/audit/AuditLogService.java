package com.sbp.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void record(String entityType, UUID entityId, String action, UUID actorUserId, String metadata) {
        auditLogRepository.save(new AuditLog(entityType, entityId, action, actorUserId, metadata));
    }

    @Transactional
    public void record(String entityType, UUID entityId, String action) {
        record(entityType, entityId, action, null, null);
    }
}
