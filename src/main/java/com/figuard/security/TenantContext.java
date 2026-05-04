package com.figuard.security;

import com.figuard.domain.entity.Tenant;

public final class TenantContext {

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Tenant tenant) {
        CURRENT.set(tenant);
    }

    public static Tenant get() {
        return CURRENT.get();
    }

    // Must be called in a finally block after every request — threads are pooled
    // and will carry stale tenant data into the next request if not cleared.
    public static void clear() {
        CURRENT.remove();
    }
}
