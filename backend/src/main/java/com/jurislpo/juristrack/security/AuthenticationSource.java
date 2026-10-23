package com.jurislpo.juristrack.security;

/** How the caller of a request was authenticated. Business logic never branches on it; only the request filters do. */
public enum AuthenticationSource {
    /** JurisTrack's own sign-in: the {@code jt_at} session cookie (emailed code). */
    STANDALONE,
    /** Embedded in JurisOne: a JWT signed by JurisOne in the {@code Authorization: Bearer} header. */
    INTEGRATION
}
