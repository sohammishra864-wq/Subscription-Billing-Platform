package com.sbp.idempotency;

import com.sbp.common.exception.BadRequestException;
import com.sbp.common.exception.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    public record ClaimResult(boolean claimed, IdempotencyKey existing) {}

    @Transactional
    public ClaimResult claimOrRetrieve(String key, UUID userId, String endpoint, String requestBody) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }

        String hash = hashBody(requestBody);
        Optional<IdempotencyKey> existing = repository.findByIdemKey(key);

        if (existing.isPresent()) {
            var stored = existing.get();
            if (!stored.getRequestHash().equals(hash)) {
                throw new ConflictException("Idempotency key reused with a different request body");
            }
            if ("IN_PROGRESS".equals(stored.getStatus())) {
                throw new ConflictException("A request with this idempotency key is already being processed");
            }
            return new ClaimResult(false, stored);
        }

        var entity = new IdempotencyKey(key, userId, endpoint, hash,
                Instant.now().plus(24, ChronoUnit.HOURS));
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // Race: another thread inserted first — re-fetch
            var raced = repository.findByIdemKey(key)
                    .orElseThrow(() -> new ConflictException("Concurrent idempotency key conflict"));
            if (!raced.getRequestHash().equals(hash)) {
                throw new ConflictException("Idempotency key reused with a different request body");
            }
            if ("IN_PROGRESS".equals(raced.getStatus())) {
                throw new ConflictException("A request with this idempotency key is already being processed");
            }
            return new ClaimResult(false, raced);
        }
        return new ClaimResult(true, entity);
    }

    @Transactional
    public void markCompleted(String key, int status, String responseBody) {
        repository.findByIdemKey(key).ifPresent(k -> {
            k.markCompleted(status, responseBody);
            repository.save(k);
        });
    }

    @Transactional
    public void release(String key) {
        repository.findByIdemKey(key).ifPresent(repository::delete);
    }

    @Transactional
    public int deleteExpired() {
        return repository.deleteExpired(Instant.now());
    }

    private String hashBody(String body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
