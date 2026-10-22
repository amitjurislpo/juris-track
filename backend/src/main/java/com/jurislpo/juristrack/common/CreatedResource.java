package com.jurislpo.juristrack.common;

import java.util.UUID;

/** What a {@code POST} that creates one record answers (201): {@code {id}}. */
public record CreatedResource(UUID id) {}
