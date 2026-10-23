package com.jurislpo.juristrack.common;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;

/**
 * Reads a {@code PATCH} body, which has three states per field (Jackson maps "left out" and {@code null} to the same value, so the
 * tree is read instead): a field that is left out gives {@code null} (keep the stored value), a field sent as {@code null} gives
 * {@code Optional.empty()} (clear it), and a value gives {@code Optional.of(value)}. A value of the wrong type is reported through
 * {@link #throwIfInvalid()} (400 with the field's name).
 */
public final class PatchBody {

    private final JsonNode body;
    private final ObjectMapper objectMapper;
    private final InputValidator validator = new InputValidator();

    private PatchBody(JsonNode body, ObjectMapper objectMapper) {
        this.body = body;
        this.objectMapper = objectMapper;
    }

    /** A missing body is an empty one (nothing changes); anything but a JSON object is a 400. */
    public static PatchBody of(JsonNode body, ObjectMapper objectMapper) {
        PatchBody patchBody = new PatchBody(body == null || body.isNull() || body.isMissingNode() ? objectMapper.createObjectNode() : body, objectMapper);
        if (!patchBody.body.isObject()) {
            patchBody.validator.reject("body", "Send a JSON object.");
            patchBody.validator.throwIfInvalid();
        }
        return patchBody;
    }

    public <T> Optional<T> field(String name, Class<T> type) {
        return field(name, objectMapper.constructType(type));
    }

    public Optional<List<String>> textListField(String name) {
        return field(name, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<T> field(String name, JavaType type) {
        if (!body.has(name)) return null;
        JsonNode value = body.get(name);
        if (value.isNull()) return Optional.empty();
        try {
            return Optional.of((T) objectMapper.convertValue(value, type));
        } catch (IllegalArgumentException e) {
            validator.reject(name, "The value of " + name + " is not valid.");
            return null;
        }
    }

    /** 400 when a field had a value of the wrong type. */
    public void throwIfInvalid() {
        validator.throwIfInvalid();
    }
}
