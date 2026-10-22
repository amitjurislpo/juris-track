package com.jurislpo.juristrack.tenancy;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The business the current thread works for. Set from the signed-in user for every request (by the sign-in filters) and
 * by code that acts inside one business; {@code null} is system mode (sign-in, provisioning, commands).
 * {@link TenantDataSource} applies it to every database connection, and PostgreSQL row-level security does the rest.
 *
 * <p>Switch only outside a transaction: a connection already bound to a transaction keeps the business it started with.
 */
public final class TenantContext {
    private TenantContext() {}

    private static final ThreadLocal<UUID> CURRENT_BUSINESS_ID = new ThreadLocal<>();

    public static UUID current() {
        return CURRENT_BUSINESS_ID.get();
    }

    public static void set(UUID businessId) {
        if (businessId == null) CURRENT_BUSINESS_ID.remove();
        else CURRENT_BUSINESS_ID.set(businessId);
    }

    public static void clear() {
        CURRENT_BUSINESS_ID.remove();
    }

    /** Runs {@code work} inside {@code businessId} (null = system mode), then restores the previous business. */
    public static <T> T call(UUID businessId, Supplier<T> work) {
        UUID previousBusinessId = CURRENT_BUSINESS_ID.get();
        set(businessId);
        try {
            return work.get();
        } finally {
            set(previousBusinessId);
        }
    }

    public static void run(UUID businessId, Runnable work) {
        call(businessId, () -> {
            work.run();
            return null;
        });
    }
}
