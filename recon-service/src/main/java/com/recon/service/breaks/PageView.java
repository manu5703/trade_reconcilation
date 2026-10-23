package com.recon.service.breaks;

import java.util.List;
import org.springframework.data.domain.Page;

/** A stable page shape for the API, rather than serializing Spring's {@code Page} internals. */
public record PageView<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    static <T> PageView<T> of(Page<T> page) {
        return new PageView<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
