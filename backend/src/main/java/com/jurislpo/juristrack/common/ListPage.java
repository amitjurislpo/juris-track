package com.jurislpo.juristrack.common;

import java.util.List;

/**
 * One page of a list endpoint: {@code {rows, total, page, pageSize}}.
 *
 * @param rows     the rows of this page
 * @param total    how many rows match the filters on all pages
 * @param page     this page's number, from 1
 * @param pageSize rows per page
 */
public record ListPage<T>(List<T> rows, long total, int page, int pageSize) {}
