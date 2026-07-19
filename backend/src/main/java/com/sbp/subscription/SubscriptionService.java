package com.sbp.subscription;

import com.sbp.common.exception.BadRequestException;
import com.sbp.common.exception.ConflictException;
import com.sbp.common.exception.NotFoundException;
import com.sbp.customer.CustomerService;
import com.sbp.payment.StripePaymentGateway;
import com.sbp.plan.PlanService;
import com.sbp.proration.ProrationCalculator;
import com.sbp.proration.ProrationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final CustomerService customerService;
    private final PlanService planService;
    private final StripePaymentGateway stripeGateway;
    private final SubscriptionStateMachine stateMachine;
    private final ProrationCalculator prorationCalculator;

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String frontendUrl;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               CustomerService customerService, PlanService planService,
                               StripePaymentGateway stripeGateway, SubscriptionStateMachine stateMachine,
                               ProrationCalculator prorationCalculator) {
        this.subscriptionRepository = subscriptionRepository;
        this.customerService = customerService;
        this.planService = planService;
        this.stripeGateway = stripeGateway;
        this.stateMachine = stateMachine;
        this.prorationCalculator = prorationCalculator;
    }

    @Transactional
    public SubscriptionCreateResult createSubscription(UUID userId, UUID planId, String idempotencyKey) {
        var customer = customerService.getOrCreateForUser(userId);
        var plan = planService.getActivePlanById(planId);

        subscriptionRepository.findActiveByCustomerId(customer.getId()).ifPresent(existing -> {
            throw new ConflictException("Customer already has an active subscription");
        });

        var subscription = new Subscription(customer.getId(), plan.getId());
        subscription = subscriptionRepository.save(subscription);

        var session = stripeGateway.createCheckoutSession(
                customer.getStripeCustomerId(),
                plan.getStripePriceId(),
                subscription.getId().toString(),
                frontendUrl + "/checkout/success",
                frontendUrl + "/checkout/cancel",
                idempotencyKey
        );

        return new SubscriptionCreateResult(subscription.getId(), session.sessionUrl());
    }

    @Transactional(readOnly = true)
    public Subscription getByCustomerUserId(UUID userId) {
        var customer = customerService.getOrCreateForUser(userId);
        return subscriptionRepository.findActiveByCustomerId(customer.getId())
                .orElseThrow(() -> new NotFoundException("No active subscription found"));
    }

    @Transactional(readOnly = true)
    public ProrationResult previewChangePlan(UUID userId, UUID newPlanId) {
        var subscription = getByCustomerUserId(userId);
        validateChangePlan(subscription, newPlanId);
        var oldPlan = planService.getActivePlanById(subscription.getPlanId());
        var newPlan = planService.getActivePlanById(newPlanId);
        return prorationCalculator.calculate(oldPlan, newPlan,
                subscription.getCurrentPeriodStart(), subscription.getCurrentPeriodEnd(), Instant.now());
    }

    @Transactional
    public ProrationResult changePlan(UUID userId, UUID newPlanId) {
        var subscription = getByCustomerUserId(userId);
        validateChangePlan(subscription, newPlanId);
        var oldPlan = planService.getActivePlanById(subscription.getPlanId());
        var newPlan = planService.getActivePlanById(newPlanId);

        stripeGateway.updateSubscriptionPrice(subscription.getStripeSubscriptionId(), newPlan.getStripePriceId());

        var result = prorationCalculator.calculate(oldPlan, newPlan,
                subscription.getCurrentPeriodStart(), subscription.getCurrentPeriodEnd(), Instant.now());

        subscription.setPlanId(newPlan.getId());
        subscriptionRepository.save(subscription);
        return result;
    }

    @Transactional
    public void cancelSubscription(UUID userId, boolean immediate) {
        var subscription = getByCustomerUserId(userId);
        if (subscription.getStripeSubscriptionId() != null) {
            stripeGateway.cancelSubscription(subscription.getStripeSubscriptionId(), !immediate);
        }
        if (immediate) {
            stateMachine.transition(subscription, SubscriptionStatus.CANCELED);
            subscription.setCanceledAt(Instant.now());
        } else {
            subscription.setCancelAtPeriodEnd(true);
        }
        subscriptionRepository.save(subscription);
    }

    @Transactional
    public void transitionTo(Subscription subscription, SubscriptionStatus target) {
        stateMachine.transition(subscription, target);
        subscriptionRepository.save(subscription);
    }

    private void validateChangePlan(Subscription subscription, UUID newPlanId) {
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
            throw new BadRequestException("Can only change plan on an active subscription");
        }
        if (subscription.getPlanId().equals(newPlanId)) {
            throw new BadRequestException("Already on this plan");
        }
    }

    public record SubscriptionCreateResult(UUID subscriptionId, String checkoutUrl) {}
}
