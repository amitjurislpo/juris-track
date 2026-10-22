package com.jurislpo.juristrack.common;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shared query parameters of a list endpoint: {@code search}, {@code sort}, {@code sortDirection} ({@code asc} or
 * {@code desc}), {@code page} (from 1) and {@code pageSize} (1 to {@value #MAXIMUM_PAGE_SIZE}, default
 * {@value #DEFAULT_PAGE_SIZE}). Unlike JurisOne's lenient ListParams, an unknown sort key or direction, or a page that is not a
 * positive whole number, is a 400 (reported through the {@link InputValidator}).
 *
 * @param search        trimmed search text, cut to 100 characters; empty for none
 * @param sort          one of the endpoint's sort keys
 * @param isAscending   the sort direction
 * @param page          the page number, from 1
 * @param pageSize      rows per page
 */
public record ListQuery(String search, String sort, boolean isAscending, int page, int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAXIMUM_PAGE_SIZE = 100;
    private static final int MAXIMUM_SEARCH_LENGTH = 100;
    private static final int MAXIMUM_PAGE = 100_000;

    /** Reads the shared parameters; problems go to {@code validator} (the caller throws once every parameter was read). */
    public static ListQuery of(Map<String, String> queryParameters, List<String> sortKeys, String defaultSort, InputValidator validator) {
        String search = queryParameters.getOrDefault("search", "").trim();
        if (search.length() > MAXIMUM_SEARCH_LENGTH) search = search.substring(0, MAXIMUM_SEARCH_LENGTH);
        String sort = queryParameters.getOrDefault("sort", defaultSort).trim();
        if (sort.isEmpty()) sort = defaultSort;
        if (!sortKeys.contains(sort)) validator.reject("sort", "Sort by one of: " + String.join(", ", sortKeys) + ".");
        String sortDirection = queryParameters.getOrDefault("sortDirection", "asc").trim().toLowerCase(Locale.ROOT);
        if (!sortDirection.equals("asc") && !sortDirection.equals("desc")) validator.reject("sortDirection", "The sort direction is asc or desc.");
        int page = wholeNumber(queryParameters.get("page"), 1, "page", 1, MAXIMUM_PAGE, validator);
        int pageSize = wholeNumber(queryParameters.get("pageSize"), DEFAULT_PAGE_SIZE, "pageSize", 1, MAXIMUM_PAGE_SIZE, validator);
        return new ListQuery(search, sort, !sortDirection.equals("desc"), page, pageSize);
    }

    /** The rows to skip before this page. */
    public long offset() {
        return (long) (page - 1) * pageSize;
    }

    /** {@code ILIKE} pattern for the search text with {@code %}, {@code _} and {@code \} taken literally; null without a search. */
    public String searchPattern() {
        if (search.isEmpty()) return null;
        return "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static int wholeNumber(String rawValue, int defaultValue, String field, int minimum, int maximum, InputValidator validator) {
        if (rawValue == null || rawValue.isBlank()) return defaultValue;
        try {
            int value = Integer.parseInt(rawValue.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException e) {
            // reported below
        }
        validator.reject(field, field + " must be a whole number between " + minimum + " and " + maximum + ".");
        return defaultValue;
    }
}
