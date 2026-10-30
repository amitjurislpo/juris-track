package com.jurislpo.juristrack.common;

/**
 * The one place that names the API's URL prefix. Every controller maps below {@link #PREFIX}, and every piece of code that matches
 * request paths (the security rules, the CORS registration, the Origin check, the roster-sync path) builds on it, so a path can never
 * be versioned in one place and forgotten in another. {@code ApiVersionTest} fails the build when a handler lies outside it.
 */
public final class ApiPaths {

    /** The prefix of every API path; a new major version is a new prefix next to this one, never an edit of an old path. */
    public static final String PREFIX = "/api/v1";

    /** Ant-style pattern for everything below the prefix. */
    public static final String ALL = PREFIX + "/**";

    private ApiPaths() {}
}
