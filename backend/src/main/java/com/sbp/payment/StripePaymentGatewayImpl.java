package com.sbp.payment;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.InvoicePayParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StripePaymentGatewayImpl implements StripePaymentGateway {

    @Value("${app.stripe.secret-key}")
    private String secretKey;

    @PostConstruct
    void init() {
        Stripe.apiKey = secretKey;
    }

    @Override
    public String createCustomer(String email) {
        try {
            var params = CustomerCreateParams.builder().setEmail(email).build();
            return Customer.create(params).getId();
        } catch (StripeException e) {
            throw new RuntimeException("Stripe customer creation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CheckoutSessionResult createCheckoutSession(String stripeCustomerId, String stripePriceId,
                                                        String clientReferenceId, String successUrl,
                                                        String cancelUrl, String idempotencyKey) {
        try {
            var params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer(stripeCustomerId)
                    .setClientReferenceId(clientReferenceId)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setPrice(stripePriceId)
                            .setQuantity(1L)
                            .build())
                    .build();
            var session = Session.create(params);
            return new CheckoutSessionResult(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new RuntimeException("Stripe checkout session creation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateSubscriptionPrice(String stripeSubscriptionId, String newStripePriceId) {
        try {
            var sub = com.stripe.model.Subscription.retrieve(stripeSubscriptionId);
            var currentItemId = sub.getItems().getData().get(0).getId();
            var params = SubscriptionUpdateParams.builder()
                    .addItem(SubscriptionUpdateParams.Item.builder()
                            .setId(currentItemId)
                            .setPrice(newStripePriceId)
                            .build())
                    .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
                    .build();
            sub.update(params);
        } catch (StripeException e) {
            throw new RuntimeException("Stripe subscription update failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelSubscription(String stripeSubscriptionId, boolean atPeriodEnd) {
        try {
            var sub = com.stripe.model.Subscription.retrieve(stripeSubscriptionId);
            if (atPeriodEnd) {
                sub.update(SubscriptionUpdateParams.builder()
                        .setCancelAtPeriodEnd(true).build());
            } else {
                sub.cancel();
            }
        } catch (StripeException e) {
            throw new RuntimeException("Stripe subscription cancellation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public PayInvoiceResult payInvoice(String stripeInvoiceId) {
        try {
            var invoice = Invoice.retrieve(stripeInvoiceId);
            var paid = invoice.pay(InvoicePayParams.builder().build());
            return new PayInvoiceResult(
                    paid.getPaymentIntent(),
                    paid.getStatus(),
                    null, null
            );
        } catch (StripeException e) {
            return new PayInvoiceResult(null, "failed", e.getCode(), e.getMessage());
        }
    }
}
