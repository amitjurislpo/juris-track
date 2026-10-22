package com.jurislpo.juristrack.engine;

import java.time.LocalDate;

/** A resolved date range: the preset it came from and its first and last date (both included, dates in the business time zone). */
public record DateRange(RangePreset preset, LocalDate from, LocalDate to) {
}
