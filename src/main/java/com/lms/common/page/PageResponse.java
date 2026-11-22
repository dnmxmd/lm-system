package com.lms.common.page;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The paged shape every list endpoint returns.
 *
 * <p>Spring Data's own {@code Page} is deliberately not serialised to clients: its JSON carries
 * internal fields and its structure has changed between versions, which would make it an accidental
 * part of the public API.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages,
                              boolean first, boolean last) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                page.isFirst(), page.isLast());
    }
}
