package com.anterka.closeauthbackend.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Standardized API response wrapper with factory methods for common response patterns.
 * Use static factory methods instead of builder for cleaner controller code.
 *
 * @param <T> The type of data payload
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CustomApiResponse<T> {
    private String message;
    private ResponseStatusEnum status;
    private LocalDateTime timestamp;
    private T data;

    // ========================================
    // Static Factory Methods
    // ========================================

    /**
     * Creates a success response with data.
     */
    public static <T> CustomApiResponse<T> success(String message, T data) {
        return CustomApiResponse.<T>builder()
                .status(ResponseStatusEnum.SUCCESS)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates a success response without data.
     */
    public static <T> CustomApiResponse<T> success(String message) {
        return CustomApiResponse.<T>builder()
                .status(ResponseStatusEnum.SUCCESS)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates an error response.
     */
    public static <T> CustomApiResponse<T> error(String message) {
        return CustomApiResponse.<T>builder()
                .status(ResponseStatusEnum.FAILED)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates an error response with data (e.g., validation errors).
     */
    public static <T> CustomApiResponse<T> error(String message, T data) {
        return CustomApiResponse.<T>builder()
                .status(ResponseStatusEnum.FAILED)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates a paginated success response.
     */
    public static <T> CustomApiResponse<T> paginated(String message, T data, PaginationInfo pagination) {
        return CustomApiResponse.<T>builder()
                .status(ResponseStatusEnum.SUCCESS)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Pagination metadata for paginated responses.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PaginationInfo {
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private boolean hasNext;
        private boolean hasPrevious;
    }
}
