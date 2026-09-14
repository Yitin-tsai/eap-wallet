package com.eap.eap_wallet.application;

import com.eap.common.event.TradeExecutedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static com.eap.common.event.TradeExecutedEvent.MAX_TRADE_ID_LENGTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WalletMessageInboxTest {

    @Test
    void tradeIdAtContractLimit_shouldBeAccepted() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        WalletMessageInbox inbox = new WalletMessageInbox(jdbc, new ObjectMapper());

        WalletMessageInbox.ReceiveOutcome outcome = inbox.receiveTradeExecuted(
                TradeExecutedEvent.builder().tradeId("t".repeat(MAX_TRADE_ID_LENGTH)).build());

        assertEquals(WalletMessageInbox.ReceiveOutcome.ACCEPTED, outcome);
    }

    @Test
    void tradeIdBeyondContractLimit_shouldBeRejectedBeforeDatabaseWrite() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        WalletMessageInbox inbox = new WalletMessageInbox(jdbc, new ObjectMapper());
        TradeExecutedEvent event = TradeExecutedEvent.builder()
                .tradeId("t".repeat(MAX_TRADE_ID_LENGTH + 1))
                .build();

        assertThrows(IllegalArgumentException.class, () -> inbox.receiveTradeExecuted(event));

        verifyNoInteractions(jdbc);
    }
}
