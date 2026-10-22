package com.jurislpo.juristrack.engine;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** What an employee asks the time tracker to do: start (or reopen) the workday, take a break, resume work, end the day. */
public enum TimeEventType {
    START,
    BREAK,
    RESUME,
    END;

    /** The event named {@code name} (trimmed, any case); empty for anything else. */
    public static Optional<TimeEventType> fromName(String name) {
        if (name == null) return Optional.empty();
        String upperCaseName = name.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.name().equals(upperCaseName)).findFirst();
    }
}
