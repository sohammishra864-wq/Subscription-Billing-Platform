package com.sbp.plan;

import com.sbp.common.exception.NotFoundException;
import com.sbp.plan.dto.PlanRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PlanService {

    private final PlanRepository planRepository;

    public PlanService(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public List<Plan> listActivePlans() {
        return planRepository.findByIsActiveTrue();
    }

    @Transactional
    public Plan createPlan(PlanRequest request) {
        var plan = new Plan(
                request.name(),
                request.stripePriceId(),
                request.priceCents(),
                request.currency() != null ? request.currency() : "USD",
                request.billingInterval()
        );
        return planRepository.save(plan);
    }

    @Transactional
    public Plan updatePlan(UUID id, PlanRequest request) {
        var plan = planRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Plan not found"));
        plan.setName(request.name());
        plan.setPriceCents(request.priceCents());
        return planRepository.save(plan);
    }

    @Transactional
    public void archivePlan(UUID id) {
        var plan = planRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Plan not found"));
        plan.setActive(false);
        planRepository.save(plan);
    }

    public Plan getActivePlanById(UUID id) {
        var plan = planRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Plan not found"));
        if (!plan.isActive()) throw new NotFoundException("Plan not found");
        return plan;
    }
}
