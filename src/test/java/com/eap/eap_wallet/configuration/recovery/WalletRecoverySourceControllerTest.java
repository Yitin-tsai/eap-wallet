package com.eap.eap_wallet.configuration.recovery;

import com.eap.common.recovery.BrokerReplayPreflightDecision;
import com.eap.common.recovery.BrokerReplayPreflightResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WalletRecoverySourceControllerTest {

    @Test
    void sourceTokenShouldFailClosed() throws Exception {
        WalletRecoveryCaseService service = mock(WalletRecoveryCaseService.class);
        WalletBrokerReplayPreflightService preflight = mock(WalletBrokerReplayPreflightService.class);
        WalletRecoverySourceController controller = new WalletRecoverySourceController(
                service, preflight, "source-secret");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(service.list(10)).thenReturn(List.of());

        mvc.perform(get("/internal/recovery/v1/cases").param("limit", "10"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/recovery/v1/cases")
                        .param("limit", "10")
                        .header(WalletRecoverySourceController.TOKEN_HEADER, "source-secret"))
                .andExpect(status().isOk());
    }

    @Test
    void brokerPreflightShouldRequireSourceToken() throws Exception {
        WalletRecoveryCaseService service = mock(WalletRecoveryCaseService.class);
        WalletBrokerReplayPreflightService preflight = mock(WalletBrokerReplayPreflightService.class);
        WalletRecoverySourceController controller = new WalletRecoverySourceController(
                service, preflight, "source-secret");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(preflight.inspect(any())).thenReturn(new BrokerReplayPreflightResult(
                BrokerReplayPreflightDecision.ELIGIBLE,
                true,
                "safe",
                "trade-1",
                null));
        String body = """
                {"sourceQueue":"wallet.tradeExecuted.queue",
                 "originalExchange":"trade.exchange",
                 "originalRoutingKey":"trade.executed",
                 "payload":"{}"}
                """;

        mvc.perform(post("/internal/recovery/v1/broker-dead-letters/preflight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/recovery/v1/broker-dead-letters/preflight")
                        .header(WalletRecoverySourceController.TOKEN_HEADER, "source-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
