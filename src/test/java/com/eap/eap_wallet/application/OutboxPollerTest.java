package com.eap.eap_wallet.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private JdbcTemplate jdbcTemplate;

    @Mock
    private NamedParameterJdbcTemplate namedJdbcTemplate;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private WalletMetrics walletMetrics;

    private OutboxPoller outboxPoller;

    @BeforeEach
    void setUp() {
        outboxPoller = new OutboxPoller(
                outboxRepository,
                jdbcTemplate,
                namedJdbcTemplate,
                rabbitTemplate,
                walletMetrics,
                25,
                1,
                1000,
                3,
                1000,
                8000
        );
        lenient().when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(invocation -> {
                    MapSqlParameterSource params = invocation.getArgument(1);
                    if (params.hasValue("ids")) {
                        return ((List<?>) params.getValue("ids")).size();
                    }
                    return 1;
                });
    }

    @Test
    void confirmedEvent_shouldBeMarkedSent() throws Exception {
        OutboxPoller.OutboxRow entry = pendingEntry(1L);
        stubPending(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        outboxPoller.pollAndPublish();

        verify(namedJdbcTemplate).update(anyString(), org.mockito.ArgumentMatchers.<MapSqlParameterSource>argThat(params ->
                params.hasValue("ids") && params.getValue("ids").equals(List.of(entry.id()))));
        verify(walletMetrics).outboxPublished();
        verify(walletMetrics, never()).outboxPublishFailed();
    }

    @Test
    void nackedEvent_shouldRemainPendingForRetry() throws Exception {
        OutboxPoller.OutboxRow entry = pendingEntry(2L);
        stubPending(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "broker rejected publish"));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        outboxPoller.pollAndPublish();

        verify(namedJdbcTemplate).update(anyString(), org.mockito.ArgumentMatchers.<MapSqlParameterSource>argThat(params ->
                params.hasValue("attemptCount")
                        && params.getValue("attemptCount").equals(1)
                        && params.hasValue("nextRetryAt")
                        && params.hasValue("lastError")));
        verify(walletMetrics).outboxPublishFailed();
        verify(walletMetrics).outboxRetryScheduled();
        verify(walletMetrics, never()).outboxPublished();
    }

    @Test
    void maxAttemptsReached_shouldMarkEventFailed() throws Exception {
        OutboxPoller.OutboxRow entry = pendingEntry(5L, 2);
        stubPending(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "still rejected"));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        outboxPoller.pollAndPublish();

        verify(namedJdbcTemplate).update(anyString(), org.mockito.ArgumentMatchers.<MapSqlParameterSource>argThat(params ->
                params.hasValue("attemptCount")
                        && params.getValue("attemptCount").equals(3)
                        && params.hasValue("lastError")
                        && params.hasValue("id")
                        && params.getValue("id").equals(entry.id())));
        verify(walletMetrics, never()).outboxRetryScheduled();
    }

    @Test
    void poll_shouldUseConfiguredBatchSize() {
        stubPending(List.of());

        outboxPoller.pollAndPublish();

        verify(jdbcTemplate).query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<OutboxPoller.OutboxRow>>any(),
                eq(25));
    }

    @Test
    void fullBatch_shouldImmediatelyFetchNextBatch() throws Exception {
        OutboxPoller smallBatchPoller = new OutboxPoller(
                outboxRepository,
                jdbcTemplate,
                namedJdbcTemplate,
                rabbitTemplate,
                walletMetrics,
                2,
                1,
                1000,
                3,
                1000,
                8000
        );
        OutboxPoller.OutboxRow first = pendingEntry(3L);
        OutboxPoller.OutboxRow second = pendingEntry(4L);
        stubPending(List.of(first, second), List.of());
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        smallBatchPoller.pollAndPublish();

        verify(jdbcTemplate, times(2)).query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<OutboxPoller.OutboxRow>>any(),
                eq(2));
        verify(namedJdbcTemplate).update(anyString(), org.mockito.ArgumentMatchers.<MapSqlParameterSource>argThat(params ->
                params.hasValue("ids") && params.getValue("ids").equals(List.of(first.id(), second.id()))));
    }

    @Test
    void batch_shouldPublishEveryMessageBeforeWaitingForConfirms() throws Exception {
        OutboxPoller smallBatchPoller = new OutboxPoller(
                outboxRepository,
                jdbcTemplate,
                namedJdbcTemplate,
                rabbitTemplate,
                walletMetrics,
                2,
                1,
                1000,
                3,
                1000,
                8000
        );
        OutboxPoller.OutboxRow first = pendingEntry(6L);
        OutboxPoller.OutboxRow second = pendingEntry(7L);
        stubPending(List.of(first, second), List.of());

        List<CorrelationData> published = new ArrayList<>();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            published.add(correlationData);
            if (published.size() == 2) {
                published.forEach(data -> data.getFuture()
                        .complete(new CorrelationData.Confirm(true, null)));
            }
            return null;
        }).when(rabbitTemplate).send(
                anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        smallBatchPoller.pollAndPublish();

        assertEquals(2, published.size());
        verify(walletMetrics, times(2)).outboxPublished();
    }

    private void stubPending(List<OutboxPoller.OutboxRow> first, List<OutboxPoller.OutboxRow>... rest) {
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<OutboxPoller.OutboxRow>>any(),
                anyInt()))
                .thenReturn(first, rest);
    }

    private OutboxPoller.OutboxRow pendingEntry(Long id) throws Exception {
        return pendingEntry(id, 0);
    }

    private OutboxPoller.OutboxRow pendingEntry(Long id, int attemptCount) throws Exception {
        OrderConfirmedEvent event = new OrderConfirmedEvent();
        return new OutboxPoller.OutboxRow(
                id,
                "OrderConfirmedEvent",
                "order.confirmed",
                new ObjectMapper().writeValueAsString(event),
                attemptCount);
    }
}
