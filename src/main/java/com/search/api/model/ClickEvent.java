// Copyright (c) 2026 Anup Ranjan. Licensed under Apache 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
package com.search.api.model;

import java.time.Instant;

public record ClickEvent(
        String sessionId,
        String query,
        String productId,
        String productTitle,
        int position,
        Instant timestamp
) {
    public static ClickEvent of(String sessionId, String query, String productId,
                                String productTitle, int position) {
        return new ClickEvent(sessionId, query, productId, productTitle, position, Instant.now());
    }
}
