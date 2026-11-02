package com.jurislpo.juristrack.security;

import java.util.UUID;

/**
 * What a verified JurisOne <b>service token</b> says (see {@link IntegrationTokenVerifier#verifyServiceToken}): JurisOne itself calls the
 * roster sync; no person is behind the call, so the token has no {@code sub} and no {@code email}. It is also the request's principal
 * on {@code PUT /api/v1/integration/employees}, and only there.
 *
 * @param jurisoneBusinessId {@code businessId}: the one JurisOne business this call is about
 * @param businessName       {@code businessName}
 * @param scope              {@code scope}: what this token may do (roster sync, or setting the business status), checked per endpoint
 */
public record IntegrationServiceClaims(UUID jurisoneBusinessId, String businessName, String scope) {}
