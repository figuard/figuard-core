package com.figuard.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class CounterfactualReplayRequest {

    /**
     * Inline hypothetical policy. Mutually exclusive with manifestVersion.
     * One of the two must be provided.
     */
    @Valid
    private HypotheticalPolicy hypotheticalPolicy;

    /**
     * Reference to a saved declared action manifest version.
     * Mutually exclusive with hypotheticalPolicy.
     * Accepted but treated as null until declared manifests land (V1-post Priority 2).
     */
    private String manifestVersion;

    private OffsetDateTime from;
    private OffsetDateTime until;

    @AssertTrue(message = "Exactly one of hypotheticalPolicy or manifestVersion must be provided")
    private boolean isPolicySourceValid() {
        boolean hasInline = hypotheticalPolicy != null;
        boolean hasManifest = manifestVersion != null && !manifestVersion.isBlank();
        return hasInline ^ hasManifest;
    }
}
