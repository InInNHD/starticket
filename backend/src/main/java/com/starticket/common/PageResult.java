package com.starticket.common;

import java.util.List;

public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResult<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
        return new PageResult<>(content, page, size, totalElements, totalPages);
    }
}
