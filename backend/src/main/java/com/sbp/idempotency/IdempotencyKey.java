package com.sbp.idempotency;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idem_key", nullable = false, unique = true)
    private String idemKey;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String endpoint;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(nullable = false)
    private String status = "IN_PROGRESS";

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyKey() {}

    public IdempotencyKey(String idemKey, UUID userId, String endpoint, String requestHash, Instant expiresAt) {
        this.idemKey = idemKey;
        this.userId = userId;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public String getIdemKey() { return idemKey; }
    public UUID getUserId() { return userId; }
    public String getEndpoint() { return endpoint; }
    public String getRequestHash() { return requestHash; }
    public String getStatus() { return status; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getExpiresAt() { return expiresAt; }

    public void markCompleted(int responseStatus, String responseBody) {
        this.status = "COMPLETED";
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }
}
