package com.eap.eap_wallet.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WalletTradeSettlementAppenderTest {

    @Test
    void appendBatchSql_shouldHavePlaceholderForEachBoundArray() {
        long placeholders = WalletTradeSettlementAppender.APPEND_BATCH_SQL.chars()
                .filter(ch -> ch == '?')
                .count();

        assertEquals(9, placeholders);
    }
}
