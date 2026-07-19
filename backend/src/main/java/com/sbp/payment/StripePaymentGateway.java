package com.sbp.payment;

import java.util.Map;

public interface StripePaymentGateway {
    String createCustomer(String email);
    CheckoutSessionResult createCheckoutSession(String stripeCustomerId, String stripePriceId,
                                                 String clientReferenceId, String successUrl, String cancelUrl,
                                                 String idempotencyKey);
    void updateSubscriptionPrice(String stripeSubscriptionId, String newStripePriceId);
    void cancelSubscription(String stripeSubscriptionId, boolean atPeriodEnd);
    PayInvoiceResult payInvoice(String stripeInvoiceId);

    record CheckoutSessionResult(String sessionId, String sessionUrl) {}
    record PayInvoiceResult(String paymentIntentId, String status, String failureCode, String failureMessage) {}
}
