package com.eap.eap_wallet.application;

import com.eap.common.event.OrderAssetReservationReleasedEvent;
import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.OrderSubmittedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.eap.common.constants.RabbitMQConstants.ORDER_ASSET_RESERVATION_RELEASED_KEY;

@Component
@RequiredArgsConstructor
public class WalletMessageProcessor {

    private final WalletOrderReservationProcessor reservationProcessor;
    private final WalletOrderCancellationAppender cancellationAppender;
    private final WalletMessageInbox inbox;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional
    public void process(WalletMessageInbox.InboxEntry entry, String owner) {
        switch (entry.messageType()) {
            case ORDER_SUBMITTED -> reservationProcessor.reserve(
                    deserialize(entry.payload(), OrderSubmittedEvent.class));
            case ORDER_CANCELLATION_RESULT -> processCancellation(
                    deserialize(entry.payload(), OrderCancellationResultEvent.class));
        }
        if (!inbox.markApplied(entry, owner)) {
            throw new IllegalStateException(
                    "Lost Wallet inbox lease before APPLIED: type=" + entry.messageType()
                            + ", id=" + entry.messageId());
        }
    }

    private void processCancellation(OrderCancellationResultEvent event) {
        if (OrderCancellationResultEvent.ALREADY_MATCHED.equals(event.getOutcome())
                || OrderCancellationResultEvent.NOT_OPEN.equals(event.getOutcome())) {
            return;
        }
        if (!event.cancelled()) {
            throw new IllegalArgumentException("Unknown cancellation outcome: " + event.getOutcome());
        }

        WalletOrderCancellationAppender.CancellationOutcome outcome = cancellationAppender.release(event);
        if (!outcome.completed() && !outcome.duplicate()) {
            throw new IllegalStateException("Wallet cancellation release did not converge: " + outcome);
        }
        publishReleaseFactOnce(event);
    }

    private void publishReleaseFactOnce(OrderCancellationResultEvent source) {
        UUID eventId = UUID.nameUUIDFromBytes(
                ("ORDER_ASSET_RESERVATION_RELEASED:" + source.getCancellationId())
                        .getBytes(StandardCharsets.UTF_8));
        int claimed = jdbc.update("""
                INSERT INTO wallet_service.order_asset_release_publications
                    (cancellation_id, event_id, order_id, recorded_at)
                VALUES (:cancellationId, :eventId, :orderId, CURRENT_TIMESTAMP)
                ON CONFLICT (cancellation_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("cancellationId", source.getCancellationId())
                .addValue("eventId", eventId)
                .addValue("orderId", source.getOrderId()));
        if (claimed == 0) {
            assertExistingPublicationMatches(source, eventId);
            return;
        }

        LocalDateTime releasedAt = LocalDateTime.now();
        OrderAssetReservationReleasedEvent released = OrderAssetReservationReleasedEvent.builder()
                .eventId(eventId)
                .cancellationId(source.getCancellationId())
                .orderId(source.getOrderId())
                .userId(source.getUserId())
                .orderType(source.getOrderType())
                .releasedQuantity(source.getCancelledAmount())
                .releasedAt(releasedAt)
                .build();
        int inserted = jdbc.update("""
                INSERT INTO wallet_service.outbox
                    (event_type, routing_key, payload, status,
                     created_at, attempt_count, next_retry_at, updated_at)
                VALUES
                    ('OrderAssetReservationReleasedEvent', :routingKey, :payload, 'PENDING',
                     CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource()
                .addValue("routingKey", ORDER_ASSET_RESERVATION_RELEASED_KEY)
                .addValue("payload", serialize(released)));
        if (inserted != 1) {
            throw new IllegalStateException("Wallet release did not insert exactly one outbox row");
        }
    }

    private void assertExistingPublicationMatches(
            OrderCancellationResultEvent source,
            UUID eventId) {
        Publication existing = jdbc.queryForObject("""
                SELECT event_id, order_id
                FROM wallet_service.order_asset_release_publications
                WHERE cancellation_id = :cancellationId
                """, new MapSqlParameterSource("cancellationId", source.getCancellationId()),
                (rs, rowNum) -> new Publication(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("order_id", UUID.class)));
        if (existing == null || !eventId.equals(existing.eventId())
                || !source.getOrderId().equals(existing.orderId())) {
            throw new WalletMessageIdentityConflictException(
                    "Wallet release publication identity conflict: cancellationId="
                            + source.getCancellationId());
        }
    }

    private <T> T deserialize(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot deserialize Wallet inbox payload", e);
        }
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize Wallet integration event", e);
        }
    }

    private record Publication(UUID eventId, UUID orderId) {
    }
}
