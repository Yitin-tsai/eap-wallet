package com.eap.eap_wallet.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WalletInboxInspectionServiceTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final WalletInboxInspectionService service = new WalletInboxInspectionService(jdbc);

    @Test
    void unsupportedStatus_shouldBeRejectedBeforeQuery() {
        assertThrows(IllegalArgumentException.class,
                () -> service.list("UNKNOWN", null, 10));

        verifyNoInteractions(jdbc);
    }

    @Test
    void unsupportedMessageType_shouldBeRejectedBeforeQuery() {
        assertThrows(IllegalArgumentException.class,
                () -> service.list(null, "UNKNOWN", 10));

        verifyNoInteractions(jdbc);
    }

    @Test
    void validFilters_shouldBeNormalizedAndLimitClampedWithoutSelectingPayload() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        service.list(" failed_retryable ", " trade_executed ", 1_000);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("status = :status", "message_type = :messageType", "LIMIT :limit")
                .doesNotContain("payload,")
                .doesNotContain("conflicting_payload");
        assertThat(params.getValue().getValue("status")).isEqualTo("FAILED_RETRYABLE");
        assertThat(params.getValue().getValue("messageType")).isEqualTo("TRADE_EXECUTED");
        assertThat(params.getValue().getValue("limit")).isEqualTo(100);
    }
}
