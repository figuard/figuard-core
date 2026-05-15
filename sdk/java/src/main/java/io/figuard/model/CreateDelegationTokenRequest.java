package io.figuard.model;

import java.util.List;
import java.util.Map;

/**
 * Request object for {@code FiGuardClient.createDelegationToken()}.
 *
 * <pre>{@code
 * CreateDelegationTokenRequest req = CreateDelegationTokenRequest.builder()
 *     .budgetId(fleet.id())
 *     .sessionToken(fleet.sessionToken())
 *     .label("booking_agent")
 *     .addCap("flight", new BigDecimal("600.00"))
 *     .expiresIn("4h")
 *     .build();
 * }</pre>
 */
public final class CreateDelegationTokenRequest {

    private final String budgetId;
    private final String sessionToken;
    private final String label;
    private final List<Map<String, Object>> caps;
    private final String expiresIn;
    private final String expiresAt;

    private CreateDelegationTokenRequest(Builder b) {
        this.budgetId     = b.budgetId;
        this.sessionToken = b.sessionToken;
        this.label        = b.label;
        this.caps         = b.caps;
        this.expiresIn    = b.expiresIn;
        this.expiresAt    = b.expiresAt;
    }

    public String budgetId()                    { return budgetId; }
    public String sessionToken()                { return sessionToken; }
    public String label()                       { return label; }
    public List<Map<String, Object>> caps()     { return caps; }
    public String expiresIn()                   { return expiresIn; }
    public String expiresAt()                   { return expiresAt; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String budgetId;
        private String sessionToken;
        private String label;
        private List<Map<String, Object>> caps;
        private String expiresIn;
        private String expiresAt;

        public Builder budgetId(String v)                   { budgetId = v;     return this; }
        public Builder sessionToken(String v)               { sessionToken = v; return this; }
        public Builder label(String v)                      { label = v;        return this; }
        public Builder caps(List<Map<String, Object>> v)    { caps = v;         return this; }
        public Builder expiresIn(String v)                  { expiresIn = v;    return this; }
        public Builder expiresAt(String v)                  { expiresAt = v;    return this; }

        public CreateDelegationTokenRequest build() {
            if (budgetId == null || budgetId.isBlank())
                throw new IllegalArgumentException("budgetId is required");
            if (sessionToken == null || sessionToken.isBlank())
                throw new IllegalArgumentException("sessionToken is required");
            if (label == null || label.isBlank())
                throw new IllegalArgumentException("label is required");
            if (caps == null || caps.isEmpty())
                throw new IllegalArgumentException("at least one cap is required");
            return new CreateDelegationTokenRequest(this);
        }
    }
}
