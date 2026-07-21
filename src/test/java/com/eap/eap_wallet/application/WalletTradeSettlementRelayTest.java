package com.eap.eap_wallet.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.WalletTradeSettledEvent;
import com.eap.eap_wallet.configuration.observability.WalletMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletTradeSettlementRelayTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private NamedParameterJdbcTemplate namedJdbcTemplate;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private WalletMetrics walletMetrics;

    private ObjectMapper objectMapper;
    private WalletTradeSettlementRelay relay;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        relay = new WalletTradeSettlementRelay(
                jdbcTemplate,
                namedJdbcTemplate,
                rabbitTemplate,
                objectMapper,
                walletMetrics,
                25,
                1000,
                3,
                1000,
                8000);
        doAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(rabbitTemplate);
        }).when(rabbitTemplate).invoke(any(RabbitOperations.OperationsCallback.class));
    }

    @Test
    void confirmedSettlement_shouldPublishEventAndMarkSent() throws Exception {
        WalletTradeSettlementRelay.SettlementRelayRow row = pendingRow();
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<WalletTradeSettlementRelay.SettlementRelayRow>>any(),
                anyInt()))
                .thenReturn(List.of(row), List.of());
        when(namedJdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                eq(RabbitMQConstants.TRADE_EXCHANGE),
                eq(RabbitMQConstants.TRADE_WALLET_SETTLED_KEY),
                messageCaptor.capture(),
                any(CorrelationData.class));

        relay.pollAndPublish();

        WalletTradeSettledEvent published =
                objectMapper.readValue(messageCaptor.getValue().getBody(), WalletTradeSettledEvent.class);
        assertEquals(row.tradeId(), published.getTradeId());
        assertEquals(row.buyerLockedCurrency(), published.getBuyerLockedCurrency());
        assertEquals(row.buyerRefundCurrency(), published.getBuyerRefundCurrency());
        assertEquals(row.sellerReceivedCurrency(), published.getSellerReceivedCurrency());
        verify(namedJdbcTemplate).update(anyString(), org.mockito.ArgumentMatchers.<MapSqlParameterSource>argThat(params ->
                params.hasValue("tradeIds") && params.getValue("tradeIds").equals(List.of(row.tradeId()))));
        verify(walletMetrics).tradeSettlementRelayPublished();
    }

    private WalletTradeSettlementRelay.SettlementRelayRow pendingRow() {
        return new WalletTradeSettlementRelay.SettlementRelayRow(
                "trade-1",
                1001,
                LocalDateTime.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                110,
                10,
                1200,
                100,
                1100,
                0);
    }
}
