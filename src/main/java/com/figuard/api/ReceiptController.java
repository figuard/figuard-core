package com.figuard.api;

import com.figuard.exception.ReceiptExpiredException;
import com.figuard.exception.ReceiptNotFoundException;
import com.figuard.security.TenantContext;
import com.figuard.service.SpendReceiptService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Two separate controllers to keep the response types clean:
 * <ul>
 *   <li>{@link AuthenticatedReceiptController} — REST, requires API key, returns JSON</li>
 *   <li>{@link PublicReceiptController} — no auth, returns Thymeleaf HTML</li>
 * </ul>
 */
public class ReceiptController {

    /**
     * Authenticated — requires X-Agent-Budget-Key header.
     * Returns the receipt URL for the given budget.
     */
    @RestController
    @RequiredArgsConstructor
    public static class AuthenticatedReceiptController {

        private final SpendReceiptService receiptService;

        @GetMapping("/api/v1/budgets/{budgetId}/receipt")
        public ResponseEntity<Map<String, String>> getReceiptUrl(@PathVariable UUID budgetId) {
            String url = receiptService.getOrCreateReceiptUrl(budgetId, TenantContext.get());
            return ResponseEntity.ok(Map.of("receiptUrl", url));
        }
    }

    /**
     * Public — no API key required.
     * Renders an HTML receipt page from a Thymeleaf template.
     * Exceptions are handled here (not in GlobalExceptionHandler) because this controller
     * returns HTML, not JSON.
     */
    @Controller
    @RequiredArgsConstructor
    public static class PublicReceiptController {

        private final SpendReceiptService receiptService;

        @GetMapping("/receipts/{token}")
        public String showReceipt(@PathVariable String token,
                                  Model model,
                                  HttpServletResponse response) {
            try {
                SpendReceiptService.ReceiptView view = receiptService.getReceiptByToken(token);
                model.addAttribute("budget", view.budget());
                model.addAttribute("events", view.confirmedEvents());
                model.addAttribute("generatedAt", view.generatedAt());
                return "receipt";
            } catch (ReceiptExpiredException e) {
                response.setStatus(HttpServletResponse.SC_GONE); // 410
                return "receipt-expired";
            } catch (ReceiptNotFoundException e) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND); // 404
                return "receipt-not-found";
            }
        }
    }
}
