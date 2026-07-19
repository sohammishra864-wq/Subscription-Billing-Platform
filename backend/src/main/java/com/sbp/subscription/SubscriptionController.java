package com.sbp.subscription;

import com.sbp.idempotency.IdempotencyService;
import com.sbp.proration.ProrationResult;
import com.sbp.subscription.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<CreateSubscriptionResponse> createSubscription(
            @Valid @RequestBody CreateSubscriptionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication auth) throws Exception {
        UUID userId = (UUID) auth.getPrincipal();
        String body = objectMapper.writeValueAsString(request);
        var claim = idempotencyService.claimOrRetrieve(idempotencyKey, userId, "/subscriptions", body);

        if (!claim.claimed()) {
            var stored = claim.existing();
            var cached = objectMapper.readValue(stored.getResponseBody(), CreateSubscriptionResponse.class);
            return ResponseEntity.status(stored.getResponseStatus()).body(cached);
        }

        try {
            var result = subscriptionService.createSubscription(userId, request.planId(), idempotencyKey);
            var response = new CreateSubscriptionResponse(result.subscriptionId(), result.checkoutUrl());
            idempotencyService.markCompleted(idempotencyKey, 201, objectMapper.writeValueAsString(response));
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            idempotencyService.release(idempotencyKey);
            throw e;
        }
    }

    @GetMapping("/me")
    public SubscriptionResponse getMySubscription(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return SubscriptionResponse.from(subscriptionService.getByCustomerUserId(userId));
    }

    @PostMapping("/me/preview-change")
    public ProrationResult previewChange(@Valid @RequestBody ChangePlanRequest request, Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return subscriptionService.previewChangePlan(userId, request.newPlanId());
    }

    @PostMapping("/me/change-plan")
    public ResponseEntity<ProrationResult> changePlan(
            @Valid @RequestBody ChangePlanRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication auth) throws Exception {
        UUID userId = (UUID) auth.getPrincipal();
        String body = objectMapper.writeValueAsString(request);
        var claim = idempotencyService.claimOrRetrieve(idempotencyKey, userId, "/subscriptions/me/change-plan", body);

        if (!claim.claimed()) {
            var stored = claim.existing();
            var cached = objectMapper.readValue(stored.getResponseBody(), ProrationResult.class);
            return ResponseEntity.status(stored.getResponseStatus()).body(cached);
        }

        try {
            var result = subscriptionService.changePlan(userId, request.newPlanId());
            idempotencyService.markCompleted(idempotencyKey, 200, objectMapper.writeValueAsString(result));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            idempotencyService.release(idempotencyKey);
            throw e;
        }
    }

    @PostMapping("/me/cancel")
    public ResponseEntity<Void> cancel(
            @RequestBody(required = false) CancelSubscriptionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        var claim = idempotencyService.claimOrRetrieve(idempotencyKey, userId, "/subscriptions/me/cancel", "cancel");

        if (!claim.claimed()) {
            return ResponseEntity.noContent().build();
        }

        try {
            boolean immediate = request != null && request.immediate();
            subscriptionService.cancelSubscription(userId, immediate);
            idempotencyService.markCompleted(idempotencyKey, 204, "");
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            idempotencyService.release(idempotencyKey);
            throw e;
        }
    }
}
