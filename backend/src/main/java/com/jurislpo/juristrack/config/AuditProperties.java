package com.jurislpo.juristrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the audit log ({@code juristrack.audit.*}).
 *
 * @param unauthenticatedEntriesPerIp the most audit entries one IP address may cause per 15-minute window while nobody is signed in
 *                                    (sign-in attempts, refused tokens and cookies). The log is append-only, so an anonymous caller must
 *                                    not be able to grow it without bound; what is over the cap is counted in the server log instead
 */
@ConfigurationProperties(prefix = "juristrack.audit")
public record AuditProperties(int unauthenticatedEntriesPerIp) {

    static final int DEFAULT_UNAUTHENTICATED_ENTRIES_PER_IP = 500;

    public AuditProperties {
        if (unauthenticatedEntriesPerIp <= 0) unauthenticatedEntriesPerIp = DEFAULT_UNAUTHENTICATED_ENTRIES_PER_IP;
    }
}
