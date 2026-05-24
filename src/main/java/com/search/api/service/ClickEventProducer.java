// Copyright (c) 2026 Anup Ranjan. Licensed under Apache 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
package com.search.api.service;

import com.search.api.model.ClickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class ClickEventProducer {

    private static final Logger log = LoggerFactory.getLogger(ClickEventProducer.class);
    private static final String TOPIC = "search-clicks";

    private final KafkaTemplate<String, ClickEvent> kafkaTemplate;

    public ClickEventProducer(KafkaTemplate<String, ClickEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(ClickEvent event) {
        kafkaTemplate.send(TOPIC, event.sessionId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish click event for query='{}' productId='{}'",
                                event.query(), event.productId(), ex);
                    } else {
                        log.debug("Click event published: query='{}' productId='{}' position={}",
                                event.query(), event.productId(), event.position());
                    }
                });
    }
}
