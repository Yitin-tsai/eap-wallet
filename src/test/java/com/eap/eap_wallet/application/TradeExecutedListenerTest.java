package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeExecutedListenerTest {

    @Mock
    private WalletTradeSettlementAppender settlementAppender;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private WalletMetrics walletMetrics;

    private TradeExecutedListener listener;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        TransactionStatus txStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        listener = new TradeExecutedListener(
                settlementAppender,
                objectMapper,
                transactionManager,
                walletMetrics);
    }

    @Test
    void handleTradeExecuted_shouldAppendSettlementAndOutboxPayload() throws Exception {
        TradeExecutedEvent event = event();
        when(settlementAppender.append(eq(event), anyString(), eq(event.getOccurredAt())))
                .thenReturn(new WalletTradeSettlementAppender.SettlementOutcome(
                        1, 1, 1, 1, 1200, 100, 1100, event.getOccurredAt()));

        listener.handleTradeExecuted(event);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(settlementAppender).append(eq(event), payloadCaptor.capture(), eq(event.getOccurredAt()));
        WalletTradeSettledEvent payload =
                objectMapper.readValue(payloadCaptor.getValue(), WalletTradeSettledEvent.class);
        assertEquals(event.getTradeId(), payload.getTradeId());
        assertEquals(1200, payload.getBuyerLockedCurrency());
        assertEquals(100, payload.getBuyerRefundCurrency());
        assertEquals(1100, payload.getSellerReceivedCurrency());
    }

    @Test
    void handleTradeExecuted_duplicateTrade_shouldSkipAsNoop() {
        TradeExecutedEvent event = event();
        when(settlementAppender.append(eq(event), anyString(), eq(event.getOccurredAt())))
                .thenReturn(new WalletTradeSettlementAppender.SettlementOutcome(
                        0, 0, 0, 0, 1200, 100, 1100, event.getOccurredAt()));

        listener.handleTradeExecuted(event);

        verify(settlementAppender).append(eq(event), anyString(), eq(event.getOccurredAt()));
    }

    private TradeExecutedEvent event() {
        return TradeExecutedEvent.builder()
                .tradeId("trade-1")
                .legacyMatchId(1001)
                .buyerId(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .buyerOrderId(UUID.randomUUID())
                .sellerOrderId(UUID.randomUUID())
                .originBuyerPrice(120)
                .originSellerPrice(100)
                .dealPrice(110)
                .quantity(10)
                .occurredAt(LocalDateTime.now())
                .build();
    }
}
