package com.figuard.service;

import com.figuard.domain.entity.AgentBudget;
import com.figuard.domain.entity.SpendEvent;
import com.figuard.domain.entity.SpendReceipt;
import com.figuard.domain.entity.Tenant;
import com.figuard.domain.enums.SpendDecision;
import com.figuard.domain.repository.AgentBudgetRepository;
import com.figuard.domain.repository.SpendEventRepository;
import com.figuard.domain.repository.SpendReceiptRepository;
import com.figuard.exception.BudgetNotFoundException;
import com.figuard.exception.ReceiptExpiredException;
import com.figuard.exception.ReceiptNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SpendReceiptService {

    private static final int TOKEN_LENGTH = 32;
    private static final String TOKEN_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int EXPIRY_DAYS = 90;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentBudgetRepository budgetRepository;
    private final SpendReceiptRepository receiptRepository;
    private final SpendEventRepository spendEventRepository;

    @Value("${figuard.base-url:https://api.figuard.io}")
    private String baseUrl;

    /**
     * Returns the receipt URL for a budget, creating the receipt row if it doesn't exist yet.
     * Idempotent — calling twice returns the same URL.
     */
    @Transactional
    public String getOrCreateReceiptUrl(UUID budgetId, Tenant tenant) {
        AgentBudget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new BudgetNotFoundException(budgetId));

        if (!budget.getTenant().getId().equals(tenant.getId())) {
            throw new BudgetNotFoundException(budgetId);
        }

        SpendReceipt receipt = receiptRepository.findByBudgetId(budgetId)
                .orElseGet(() -> createReceipt(budget));

        return baseUrl + "/receipts/" + receipt.getReceiptToken();
    }

    /**
     * Loads a receipt by token for the public receipt page.
     *
     * @throws ReceiptNotFoundException for unknown tokens (HTTP 404)
     * @throws ReceiptExpiredException  for expired receipts (HTTP 410)
     */
    @Transactional(readOnly = true)
    public ReceiptView getReceiptByToken(String token) {
        SpendReceipt receipt = receiptRepository.findByReceiptToken(token)
                .orElseThrow(() -> new ReceiptNotFoundException(token));

        if (receipt.isExpired()) {
            throw new ReceiptExpiredException(token);
        }

        AgentBudget budget = receipt.getBudget();

        List<SpendEvent> confirmedEvents = spendEventRepository
                .findByBudgetIdAndDecisionOrderByCreatedAtDesc(
                        budget.getId(), SpendDecision.CONFIRMED);

        return new ReceiptView(budget, confirmedEvents, receipt.getGeneratedAt());
    }

    private SpendReceipt createReceipt(AgentBudget budget) {
        SpendReceipt receipt = new SpendReceipt();
        receipt.setBudget(budget);
        receipt.setReceiptToken(generateToken());
        receipt.setExpiresAt(OffsetDateTime.now().plusDays(EXPIRY_DAYS));
        return receiptRepository.save(receipt);
    }

    private static String generateToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_CHARS.charAt(RANDOM.nextInt(TOKEN_CHARS.length())));
        }
        return sb.toString();
    }

    /** View object passed to the Thymeleaf template. */
    public record ReceiptView(
            AgentBudget budget,
            List<SpendEvent> confirmedEvents,
            OffsetDateTime generatedAt
    ) {}
}
