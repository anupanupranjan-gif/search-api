// Copyright (c) 2026 Anup Ranjan. Licensed under Apache 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
package com.search.api.model;

import java.time.Instant;

// NR-32: added variantId — present when an A/B test was active for this search session
public record ClickEvent(
        String sessionId,
        String query,
        String productId,
        String productTitle,
        int position,
        Instant timestamp,
        String variantId   // nullable — format: "{abTestId}:{variant}" e.g. "abc-123:A"
) {
    public static ClickEvent of(String sessionId, String query, String productId,
                                String productTitle, int position) {
        return new ClickEvent(sessionId, query, productId, productTitle, position,
                Instant.now(), null);
    }

    public static ClickEvent of(String sessionId, String query, String productId,
                                String productTitle, int position, String variantId) {
        return new ClickEvent(sessionId, query, productId, productTitle, position,
                Instant.now(), variantId);
    }
}
