package com.sbp.customer;

import com.sbp.payment.StripePaymentGateway;
import com.sbp.user.UserRepository;
import com.sbp.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final StripePaymentGateway stripeGateway;

    public CustomerService(CustomerRepository customerRepository, UserRepository userRepository,
                           StripePaymentGateway stripeGateway) {
        this.customerRepository = customerRepository;
        this.userRepository = userRepository;
        this.stripeGateway = stripeGateway;
    }

    @Transactional
    public Customer getOrCreateForUser(UUID userId) {
        return customerRepository.findByUserId(userId).orElseGet(() -> {
            var user = userRepository.findById(userId)
                    .orElseThrow(() -> new NotFoundException("User not found"));
            String stripeCustomerId = stripeGateway.createCustomer(user.getEmail());
            return customerRepository.save(new Customer(userId, stripeCustomerId));
        });
    }
}
