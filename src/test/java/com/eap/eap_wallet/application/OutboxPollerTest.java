package com.eap.eap_wallet.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.ArrayList;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private WalletMetrics walletMetrics;

    private OutboxPoller outboxPoller;

    @BeforeEach
    void setUp() {
        outboxPoller = new OutboxPoller(
                outboxRepository,
                rabbitTemplate,
                new ObjectMapper(),
                walletMetrics,
                25,
                1000,
                3,
                1000,
                8000
        );
        lenient().when(outboxRepository.markPendingAsSent(any(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
    }

    @Test
    void confirmedEvent_shouldBeMarkedSent() throws Exception {
        OutboxEntity entry = pendingEntry(1L);
        when(outboxRepository.findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any(), any(CorrelationData.class));

        outboxPoller.pollAndPublish();

        assertEquals("SENT", entry.getStatus());
        assertNull(entry.getNextRetryAt());
        verify(outboxRepository).markPendingAsSent(eq(List.of(entry.getId())), any(LocalDateTime.class));
        verify(walletMetrics).outboxPublished();
        verify(walletMetrics, never()).outboxPublishFailed();
    }

    @Test
    void nackedEvent_shouldRemainPendingForRetry() throws Exception {
        OutboxEntity entry = pendingEntry(2L);
        when(outboxRepository.findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "broker rejected publish"));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any(), any(CorrelationData.class));

        outboxPoller.pollAndPublish();

        assertEquals("PENDING", entry.getStatus());
        assertEquals(1, entry.getAttemptCount());
        assertNotNull(entry.getNextRetryAt());
        assertNotNull(entry.getLastError());
        verify(outboxRepository).save(entry);
        verify(walletMetrics).outboxPublishFailed();
        verify(walletMetrics).outboxRetryScheduled();
        verify(walletMetrics, never()).outboxPublished();
    }

    @Test
    void maxAttemptsReached_shouldMarkEventFailed() throws Exception {
        OutboxEntity entry = pendingEntry(5L);
        entry.setAttemptCount(2);
        when(outboxRepository.findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "still rejected"));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any(), any(CorrelationData.class));

        outboxPoller.pollAndPublish();

        assertEquals("FAILED", entry.getStatus());
        assertEquals(3, entry.getAttemptCount());
        assertNull(entry.getNextRetryAt());
        verify(outboxRepository).save(entry);
        verify(walletMetrics, never()).outboxRetryScheduled();
    }

    @Test
    void poll_shouldUseConfiguredBatchSize() {
        when(outboxRepository.findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        outboxPoller.pollAndPublish();

        verify(outboxRepository).findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.argThat(pageable -> pageable.getPageSize() == 25));
    }

    @Test
    void fullBatch_shouldImmediatelyFetchNextBatch() throws Exception {
        OutboxPoller smallBatchPoller = new OutboxPoller(
                outboxRepository,
                rabbitTemplate,
                new ObjectMapper(),
                walletMetrics,
                2,
                1000,
                3,
                1000,
                8000
        );
        OutboxEntity first = pendingEntry(3L);
        OutboxEntity second = pendingEntry(4L);
        when(outboxRepository.findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(first, second), List.of());
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any(), any(CorrelationData.class));

        smallBatchPoller.pollAndPublish();

        verify(outboxRepository, times(2))
                .findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                        eq("PENDING"), any(LocalDateTime.class), any(Pageable.class));
        verify(outboxRepository).markPendingAsSent(
                eq(List.of(first.getId(), second.getId())), any(LocalDateTime.class));
    }

    @Test
    void batch_shouldPublishEveryMessageBeforeWaitingForConfirms() throws Exception {
        OutboxPoller smallBatchPoller = new OutboxPoller(
                outboxRepository,
                rabbitTemplate,
                new ObjectMapper(),
                walletMetrics,
                2,
                1000,
                3,
                1000,
                8000
        );
        OutboxEntity first = pendingEntry(6L);
        OutboxEntity second = pendingEntry(7L);
        when(outboxRepository.findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                eq("PENDING"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(first, second), List.of());

        List<CorrelationData> published = new ArrayList<>();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            published.add(correlationData);
            if (published.size() == 2) {
                published.forEach(data -> data.getFuture()
                        .complete(new CorrelationData.Confirm(true, null)));
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), (Object) any(), any(CorrelationData.class));

        smallBatchPoller.pollAndPublish();

        assertEquals(2, published.size());
        assertEquals("SENT", first.getStatus());
        assertEquals("SENT", second.getStatus());
        verify(walletMetrics, times(2)).outboxPublished();
    }

    private OutboxEntity pendingEntry(Long id) throws Exception {
        OrderConfirmedEvent event = new OrderConfirmedEvent();
        OutboxEntity entry = new OutboxEntity(
                "OrderConfirmedEvent",
                "order.confirmed",
                new ObjectMapper().writeValueAsString(event)
        );
        setId(entry, id);
        return entry;
    }

    private void setId(OutboxEntity entry, Long id) throws Exception {
        var field = OutboxEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entry, id);
    }
}
