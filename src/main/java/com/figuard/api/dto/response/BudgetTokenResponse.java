package com.figuard.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

/**
 * One entry in the {@code tokens} list on a {@link BudgetResponse}.
 *
 * <p>For simple budgets there is always exactly one entry with {@code category = "default"}.
 * For entitlement-backed budgets there is one entry per entitlement item
 * (e.g. "api_calls", "llm_tokens", "monetary").
 *
 * <p>{@code sessionToken} is populated ONCE — on budget creation only.
 * It is {@code null} on all subsequent reads.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Builder
public class BudgetTokenResponse {

    /** Entitlement category this token enforces. "default" for simple budgets. */
    private String category;

    /**
     * Raw session token — returned once at creation, never again.
     * The caller must store this immediately.
     */
    private String sessionToken;

    /** First 12 characters of the token — safe for logging and debugging. */
    private String sessionTokenPrefix;

    /** Resource unit label (e.g. "tokens", "api_calls"). Null for monetary tokens. */
    private String unit;

    /** ISO 4217 currency code. Null for resource tokens. */
    private String currency;
}
