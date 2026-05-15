package io.figuard.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Request object for {@code FiGuardClient.createBudget()}.
 *
 * <p>Exactly one of {@code currency} or {@code unit} must be provided:
 * <ul>
 *   <li>Monetary budget: {@code .currency("USD")}
 *   <li>Resource budget: {@code .unit("tokens")}
 * </ul>
 *
 * <p>Exactly one of {@code expiresAt} or {@code expiresIn} must be provided.
 *
 * <pre>{@code
 * CreateBudgetRequest req = CreateBudgetRequest.builder()
 *     .userId("user_123")
 *     .totalLimit(new BigDecimal("500.00"))
 *     .currency("USD")
 *     .expiresIn("24h")
 *     .intentContext("travel booking session")
 *     .build();
 * }</pre>
 */
public final class CreateBudgetRequest {

    private final String userId;
    private final BigDecimal totalLimit;
    private final String expiresAt;
    private final String expiresIn;
    private final String currency;
    private final String unit;
    private final String intentContext;
    private final List<String> intentTags;
    private final String externalReference;
    private final BigDecimal softLimit;
    private final BigDecimal maxTransactionQuantity;
    private final Integer authorizationExpirySeconds;
    private final boolean anomalyDetectionEnabled;
    private final boolean autoPauseOnAnomaly;
    private final boolean entityDedupEnabled;
    private final List<Map<String, Object>> allocations;
    private final Map<String, Object> metadata;

    private CreateBudgetRequest(Builder b) {
        this.userId                    = b.userId;
        this.totalLimit                = b.totalLimit;
        this.expiresAt                 = b.expiresAt;
        this.expiresIn                 = b.expiresIn;
        this.currency                  = b.currency;
        this.unit                      = b.unit;
        this.intentContext             = b.intentContext;
        this.intentTags                = b.intentTags;
        this.externalReference         = b.externalReference;
        this.softLimit                 = b.softLimit;
        this.maxTransactionQuantity    = b.maxTransactionQuantity;
        this.authorizationExpirySeconds = b.authorizationExpirySeconds;
        this.anomalyDetectionEnabled   = b.anomalyDetectionEnabled;
        this.autoPauseOnAnomaly        = b.autoPauseOnAnomaly;
        this.entityDedupEnabled        = b.entityDedupEnabled;
        this.allocations               = b.allocations;
        this.metadata                  = b.metadata;
    }

    public String userId()                     { return userId; }
    public BigDecimal totalLimit()             { return totalLimit; }
    public String expiresAt()                  { return expiresAt; }
    public String expiresIn()                  { return expiresIn; }
    public String currency()                   { return currency; }
    public String unit()                       { return unit; }
    public String intentContext()              { return intentContext; }
    public List<String> intentTags()           { return intentTags; }
    public String externalReference()          { return externalReference; }
    public BigDecimal softLimit()              { return softLimit; }
    public BigDecimal maxTransactionQuantity() { return maxTransactionQuantity; }
    public Integer authorizationExpirySeconds(){ return authorizationExpirySeconds; }
    public boolean anomalyDetectionEnabled()   { return anomalyDetectionEnabled; }
    public boolean autoPauseOnAnomaly()        { return autoPauseOnAnomaly; }
    public boolean entityDedupEnabled()        { return entityDedupEnabled; }
    public List<Map<String, Object>> allocations() { return allocations; }
    public Map<String, Object> metadata()      { return metadata; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String userId;
        private BigDecimal totalLimit;
        private String expiresAt;
        private String expiresIn;
        private String currency;
        private String unit;
        private String intentContext;
        private List<String> intentTags;
        private String externalReference;
        private BigDecimal softLimit;
        private BigDecimal maxTransactionQuantity;
        private Integer authorizationExpirySeconds;
        private boolean anomalyDetectionEnabled = false;
        private boolean autoPauseOnAnomaly      = true;
        private boolean entityDedupEnabled      = false;
        private List<Map<String, Object>> allocations;
        private Map<String, Object> metadata;

        public Builder userId(String v)                    { userId = v;                    return this; }
        public Builder totalLimit(BigDecimal v)            { totalLimit = v;                return this; }
        public Builder expiresAt(String v)                 { expiresAt = v;                 return this; }
        public Builder expiresIn(String v)                 { expiresIn = v;                 return this; }
        public Builder currency(String v)                  { currency = v;                  return this; }
        public Builder unit(String v)                      { unit = v;                      return this; }
        public Builder intentContext(String v)             { intentContext = v;             return this; }
        public Builder intentTags(List<String> v)          { intentTags = v;                return this; }
        public Builder externalReference(String v)         { externalReference = v;         return this; }
        public Builder softLimit(BigDecimal v)             { softLimit = v;                 return this; }
        public Builder maxTransactionQuantity(BigDecimal v){ maxTransactionQuantity = v;    return this; }
        public Builder authorizationExpirySeconds(int v)   { authorizationExpirySeconds = v; return this; }
        public Builder anomalyDetectionEnabled(boolean v)  { anomalyDetectionEnabled = v;  return this; }
        public Builder autoPauseOnAnomaly(boolean v)       { autoPauseOnAnomaly = v;       return this; }
        public Builder entityDedupEnabled(boolean v)       { entityDedupEnabled = v;       return this; }
        public Builder allocations(List<Map<String, Object>> v) { allocations = v;         return this; }
        public Builder metadata(Map<String, Object> v)     { metadata = v;                 return this; }

        public CreateBudgetRequest build() {
            if (userId == null || userId.isBlank())
                throw new IllegalArgumentException("userId is required");
            if (totalLimit == null)
                throw new IllegalArgumentException("totalLimit is required");
            if (currency == null && unit == null)
                throw new IllegalArgumentException("Either currency or unit must be provided");
            if (currency != null && unit != null)
                throw new IllegalArgumentException("currency and unit are mutually exclusive");
            if (expiresAt == null && expiresIn == null)
                throw new IllegalArgumentException("Either expiresAt or expiresIn must be provided");
            return new CreateBudgetRequest(this);
        }
    }
}
