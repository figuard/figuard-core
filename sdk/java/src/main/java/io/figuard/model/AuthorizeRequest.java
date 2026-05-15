package io.figuard.model;

import java.math.BigDecimal;

/**
 * Request object for {@code FiGuardClient.authorize()}.
 *
 * <p>Build with the nested {@link Builder}:
 * <pre>{@code
 * AuthorizeRequest req = AuthorizeRequest.builder()
 *     .sessionToken(budget.sessionToken())
 *     .agentId("agent-123")
 *     .actionType("PURCHASE")
 *     .description("Buy GPU credits")
 *     .requestedQuantity(new BigDecimal("50.00"))
 *     .idempotencyKey(UUID.randomUUID().toString())
 *     .build();
 * }</pre>
 */
public final class AuthorizeRequest {

    private final String agentId;
    private final String agentType;
    private final String actionType;
    private final String description;
    private final BigDecimal requestedQuantity;
    private final String currency;
    private final String idempotencyKey;
    private final String intentContext;
    private final String entityId;
    private final String claimedCategory;
    private final String claimedItemType;
    private final String parentEventId;
    private final String sessionToken;

    private AuthorizeRequest(Builder b) {
        this.agentId           = b.agentId;
        this.agentType         = b.agentType;
        this.actionType        = b.actionType;
        this.description       = b.description;
        this.requestedQuantity = b.requestedQuantity;
        this.currency          = b.currency;
        this.idempotencyKey    = b.idempotencyKey;
        this.intentContext     = b.intentContext;
        this.entityId          = b.entityId;
        this.claimedCategory   = b.claimedCategory;
        this.claimedItemType   = b.claimedItemType;
        this.parentEventId     = b.parentEventId;
        this.sessionToken      = b.sessionToken;
    }

    public String agentId()              { return agentId; }
    public String agentType()            { return agentType; }
    public String actionType()           { return actionType; }
    public String description()          { return description; }
    public BigDecimal requestedQuantity(){ return requestedQuantity; }
    public String currency()             { return currency; }
    public String idempotencyKey()       { return idempotencyKey; }
    public String intentContext()        { return intentContext; }
    public String entityId()             { return entityId; }
    public String claimedCategory()      { return claimedCategory; }
    public String claimedItemType()      { return claimedItemType; }
    public String parentEventId()        { return parentEventId; }
    public String sessionToken()         { return sessionToken; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String agentId;
        private String agentType;
        private String actionType;
        private String description;
        private BigDecimal requestedQuantity;
        private String currency;
        private String idempotencyKey;
        private String intentContext;
        private String entityId;
        private String claimedCategory;
        private String claimedItemType;
        private String parentEventId;
        private String sessionToken;

        public Builder agentId(String v)               { agentId = v;            return this; }
        public Builder agentType(String v)             { agentType = v;          return this; }
        public Builder actionType(String v)            { actionType = v;         return this; }
        public Builder description(String v)           { description = v;        return this; }
        public Builder requestedQuantity(BigDecimal v) { requestedQuantity = v;  return this; }
        public Builder currency(String v)              { currency = v;           return this; }
        public Builder idempotencyKey(String v)        { idempotencyKey = v;     return this; }
        public Builder intentContext(String v)         { intentContext = v;      return this; }
        public Builder entityId(String v)              { entityId = v;           return this; }
        public Builder claimedCategory(String v)       { claimedCategory = v;   return this; }
        public Builder claimedItemType(String v)       { claimedItemType = v;   return this; }
        public Builder parentEventId(String v)         { parentEventId = v;     return this; }
        public Builder sessionToken(String v)          { sessionToken = v;      return this; }

        public AuthorizeRequest build() {
            if (agentId == null || agentId.isBlank())
                throw new IllegalArgumentException("agentId is required");
            if (actionType == null || actionType.isBlank())
                throw new IllegalArgumentException("actionType is required");
            if (requestedQuantity == null)
                throw new IllegalArgumentException("requestedQuantity is required");
            return new AuthorizeRequest(this);
        }
    }
}
