package com.anterka.closeauthbackend.common.web;

import java.util.List;

/**
 * A bounded, paginated response envelope for the admin list endpoints (§7.8) — no list endpoint returns an unbounded
 * collection. {@code page} is 0-based; {@code size} is the page size actually applied; {@code totalElements} /
 * {@code totalPages} describe the full result set.
 *
 * <p><b>Implementation note (flagged):</b> in 7b these are paged in the application layer over the existing
 * {@code List}-returning service methods (Stages 3–6). That bounds the RESPONSE; for very large tenants, pushing
 * {@code page/size} into repository queries is a mechanical follow-up. Documented in STAGE_7B_REPORT.md.
 */
public record PageView<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    /** Default page size when the caller omits {@code size}. */
    public static final int DEFAULT_SIZE = 20;
    /** Upper bound so a caller can't request an unbounded page. */
    public static final int MAX_SIZE = 100;

    /** Slices {@code all} into the requested page, clamping {@code page >= 0} and {@code size} to [1, MAX_SIZE]. */
    public static <T> PageView<T> of(List<T> all, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = Math.max(page, 0);
        long total = all.size();
        int totalPages = (int) Math.ceil((double) total / safeSize);
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return new PageView<>(List.copyOf(all.subList(from, to)), safePage, safeSize, total, totalPages);
    }
}
