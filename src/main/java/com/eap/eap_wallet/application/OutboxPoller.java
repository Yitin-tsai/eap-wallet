package com.eap.eap_wallet.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.OrderFailedEvent;
import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 500)
    public void pollAndPublish() {
        List<OutboxEntity> pending = outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING");
        for (OutboxEntity entry : pending) {
            try {
                Object event = deserializeEvent(entry);
                rabbitTemplate.convertAndSend(
                        RabbitMQConstants.ORDER_EXCHANGE,
                        entry.getRoutingKey(),
                        event
                );
                entry.setStatus("SENT");
                outboxRepository.save(entry);
                log.debug("Outbox 事件已發布: id={}, type={}", entry.getId(), entry.getEventType());
            } catch (Exception e) {
                log.error("Outbox 事件發布失敗: id={}, error={}", entry.getId(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        outboxRepository.deleteByStatusAndCreatedAtBefore("SENT", cutoff);
        log.info("已清理 24 小時前的 SENT outbox 記錄");
    }

    private Object deserializeEvent(OutboxEntity entry) throws Exception {
        return switch (entry.getEventType()) {
            case "OrderConfirmedEvent" -> objectMapper.readValue(entry.getPayload(), OrderConfirmedEvent.class);
            case "OrderFailedEvent" -> objectMapper.readValue(entry.getPayload(), OrderFailedEvent.class);
            default -> throw new IllegalArgumentException("Unknown event type: " + entry.getEventType());
        };
    }
}
