package com.jurislpo.juristrack.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The success body of every JSON endpoint, {@code {ok:true, message?, data?}}. Its own empty fields are omitted; nulls
 * inside {@code data} are kept. Errors are not sent this way: they are {@code application/problem+json} (see exception.Problems).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResult<T>(boolean ok, String message, T data) {

    public static <T> ApiResult<T> success() { return new ApiResult<>(true, null, null); }
    public static <T> ApiResult<T> success(T data) { return new ApiResult<>(true, null, data); }
    public static <T> ApiResult<T> success(String message, T data) { return new ApiResult<>(true, message, data); }
    public static <T> ApiResult<T> message(String message) { return new ApiResult<>(true, message, null); }
}
