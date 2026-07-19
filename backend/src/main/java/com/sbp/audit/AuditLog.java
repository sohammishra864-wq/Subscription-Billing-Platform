package com.sbp.audit;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(nullable = false)
    private String action;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AuditLog() {}

    public AuditLog(String entityType, UUID entityId, String action, UUID actorUserId, String metadata) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.actorUserId = actorUserId;
        this.metadata = metadata;
    }

    public UUID getId() { return id; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public String getAction() { return action; }
    public UUID getActorUserId() { return actorUserId; }
    public String getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
}
