package com.jurislpo.juristrack;

import org.testcontainers.containers.PostgreSQLContainer;

/** A Postgres 16 container (same image as docker-compose). */
public final class PostgresContainer {
    private PostgresContainer() {}

    public static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        return postgres;
    }
}
