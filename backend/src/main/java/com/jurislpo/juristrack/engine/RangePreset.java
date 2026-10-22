package com.jurislpo.juristrack.engine;

import java.util.Arrays;
import java.util.Optional;

/** The date-range choices of the history and report screens; {@link #key()} is the value of the {@code range} query parameter. */
public enum RangePreset {
    TODAY("today"),
    YESTERDAY("yesterday"),
    WEEK("week"),
    MONTH("month"),
    CUSTOM("custom");

    private final String key;

    RangePreset(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** The preset whose key is exactly {@code key} (case-sensitive, as in the old app); empty for anything else. */
    public static Optional<RangePreset> fromKey(String key) {
        return Arrays.stream(values()).filter(preset -> preset.key.equals(key)).findFirst();
    }
}
