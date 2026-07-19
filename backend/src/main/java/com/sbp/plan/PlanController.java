package com.sbp.plan;

import com.sbp.plan.dto.PlanRequest;
import com.sbp.plan.dto.PlanResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping("/api/v1/plans")
    public List<PlanResponse> listPlans() {
        return planService.listActivePlans().stream().map(PlanResponse::from).toList();
    }

    @PostMapping("/api/v1/admin/plans")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PlanResponse> createPlan(@Valid @RequestBody PlanRequest request) {
        var plan = planService.createPlan(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(PlanResponse.from(plan));
    }

    @PatchMapping("/api/v1/admin/plans/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PlanResponse updatePlan(@PathVariable UUID id, @Valid @RequestBody PlanRequest request) {
        return PlanResponse.from(planService.updatePlan(id, request));
    }

    @DeleteMapping("/api/v1/admin/plans/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> archivePlan(@PathVariable UUID id) {
        planService.archivePlan(id);
        return ResponseEntity.noContent().build();
    }
}
