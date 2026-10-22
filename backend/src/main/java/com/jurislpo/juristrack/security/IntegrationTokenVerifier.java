package com.jurislpo.juristrack.security;

/**
 * Verifies the token JurisOne sends when JurisTrack runs embedded in it (no implementation yet; it comes with the auth phase).
 *
 * <p>In embedded mode the browser talks only to JurisOne; the JurisOne API is the gateway
 * ({@code /api/juris-track/**} there, forwarded to this API) and adds {@code Authorization: Bearer <jwt>}: a short-lived
 * JWT (about 5 minutes) signed with JurisOne's <b>private</b> key (asymmetric: RS256 or ES256). JurisTrack holds only the
 * public key ({@code juristrack.integration.public-key}) and never a shared secret. Only checked when
 * {@code juristrack.integration.enabled=true}; the standalone sign-in (emailed code, own cookies) is unaffected.
 *
 * <p>The implementation will:
 * <ul>
 *   <li>check the signature against the configured public key, and refuse {@code alg=none} and symmetric algorithms;</li>
 *   <li>require {@code iss} = {@code juristrack.integration.issuer} and {@code aud} to contain
 *       {@code juristrack.integration.audience} ({@code juristrack});</li>
 *   <li>require {@code exp} (and honour {@code nbf} / {@code iat} when present) within
 *       {@code juristrack.integration.clock-skew-seconds};</li>
 *   <li>require the claims {@code sub} (the JurisOne employee id), {@code email}, {@code name}, {@code businessId},
 *       {@code businessName}; {@code managerId} (the JurisOne id of the employee's manager, or null) and {@code trackRole}
 *       (ADMIN only; MANAGER follows from the reporting lines) are optional;</li>
 *   <li>check {@code jti} against replays within the token's lifetime (optional);</li>
 *   <li>hand back the verified claims, from which the business ({@code businesses.jurisone_business_id}) and the user are
 *       provisioned just in time: matched by {@code users.jurisone_employee_id} first, then by email (globally unique); the
 *       user's {@code manager_id} is refreshed from {@code managerId} on every token.</li>
 * </ul>
 * Any failure means "not signed in" (401 problem+json); the reason is logged, never returned.
 *
 * <p>Besides the per-user token, a roster-sync service (a later phase: {@code PUT /api/integration/employees}, a JurisOne
 * service token with {@code scope=employees:sync}) will create, update and deactivate a business's users from JurisOne's
 * employee list, using the same columns ({@code jurisone_business_id}, {@code jurisone_employee_id}, {@code manager_id}),
 * so a manager sees all reports, including those who never opened JurisTrack.
 */
public interface IntegrationTokenVerifier {
}
