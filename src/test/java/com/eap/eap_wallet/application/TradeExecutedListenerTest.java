package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_wallet.configuration.repository.OutboxRepository;
import com.eap.eap_wallet.configuration.repository.TradeSettlementRepository;
import com.eap.eap_wallet.configuration.repository.WalletRepository;
import com.eap.eap_wallet.domain.entity.OutboxEntity;
import com.eap.eap_wallet.domain.entity.TradeSettlementEntity;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeExecutedListenerTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private TradeSettlementRepository tradeSettlementRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TradeExecutedListener listener;

    @BeforeEach
    void setUp() {
        TransactionStatus txStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        listener = new TradeExecutedListener(
                walletRepository,
                tradeSettlementRepository,
                outboxRepository,
                objectMapper,
                transactionManager);
    }

    @Test
    void handleTradeExecuted_shouldSettleBuyerAndSellerAndWriteOutbox() {
        TradeExecutedEvent event = event();
        when(walletRepository.settleTradeForBuyer(event.getBuyerId(), 1200, 100, 10)).thenReturn(1);
        when(walletRepository.settleTradeForSeller(event.getSellerId(), 10, 1100)).thenReturn(1);

        listener.handleTradeExecuted(event);

        verify(walletRepository).settleTradeForBuyer(event.getBuyerId(), 1200, 100, 10);
        verify(walletRepository).settleTradeForSeller(event.getSellerId(), 10, 1100);
        verify(tradeSettlementRepository).save(any(TradeSettlementEntity.class));

        ArgumentCaptor<OutboxEntity> captor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository).save(captor.capture());
        assertEquals("WalletTradeSettledEvent", captor.getValue().getEventType());
        assertEquals("trade.wallet.settled", captor.getValue().getRoutingKey());
    }

    @Test
    void handleTradeExecuted_duplicateTrade_shouldSkipWalletUpdates() {
        TradeExecutedEvent event = event();
        when(tradeSettlementRepository.existsByTradeId(event.getTradeId())).thenReturn(true);

        listener.handleTradeExecuted(event);

        verify(walletRepository, never()).settleTradeForBuyer(any(), anyInt(), anyInt(), anyInt());
        verify(walletRepository, never()).settleTradeForSeller(any(), anyInt(), anyInt());
        verify(outboxRepository, never()).save(any());
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
