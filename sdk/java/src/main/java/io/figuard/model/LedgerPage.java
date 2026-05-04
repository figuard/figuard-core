package io.figuard.model;

import java.util.List;

public record LedgerPage(
        List<SpendEventResponse> events,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
    /** True if there are more pages after this one. */
    public boolean hasNext() {
        return page < totalPages - 1;
    }
}
