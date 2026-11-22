package com.lms.common.page;

/**
 * Paging request in plain terms. Values are clamped on construction, so no caller can ask for
 * page -3 or ten thousand rows.
 */
public record PageQuery(int page, int size) {

    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 20;

    public PageQuery {
        page = Math.max(page, 0);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    public static PageQuery of(Integer page, Integer size) {
        return new PageQuery(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }

    public org.springframework.data.domain.PageRequest toPageable(
            org.springframework.data.domain.Sort sort) {
        return org.springframework.data.domain.PageRequest.of(page, size, sort);
    }
}
