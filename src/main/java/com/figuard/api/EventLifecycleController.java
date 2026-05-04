package com.figuard.api;

import com.figuard.api.dto.request.ConfirmEventRequest;
import com.figuard.api.dto.request.FailEventRequest;
import com.figuard.api.dto.request.VoidEventRequest;
import com.figuard.api.dto.response.SpendEventResponse;
import com.figuard.security.TenantContext;
import com.figuard.service.PaymentLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventLifecycleController {

    private final PaymentLifecycleService lifecycleService;

    @PostMapping("/{id}/confirm")
    public ResponseEntity<SpendEventResponse> confirmEvent(
            @PathVariable UUID id,
            @Valid @RequestBody ConfirmEventRequest request) {
        return ResponseEntity.ok(lifecycleService.confirmEvent(id, request, TenantContext.get()));
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<SpendEventResponse> failEvent(
            @PathVariable UUID id,
            @Valid @RequestBody FailEventRequest request) {
        return ResponseEntity.ok(lifecycleService.failEvent(id, request, TenantContext.get()));
    }

    @PostMapping("/{id}/void")
    public ResponseEntity<SpendEventResponse> voidEvent(
            @PathVariable UUID id,
            @Valid @RequestBody VoidEventRequest request) {
        return ResponseEntity.ok(lifecycleService.voidEvent(id, request, TenantContext.get()));
    }
}
