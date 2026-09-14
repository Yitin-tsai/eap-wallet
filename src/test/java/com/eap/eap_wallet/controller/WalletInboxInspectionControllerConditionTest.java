package com.eap.eap_wallet.controller;

import com.eap.eap_wallet.application.WalletInboxInspectionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WalletInboxInspectionControllerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(WalletInboxInspectionService.class,
                    () -> mock(WalletInboxInspectionService.class))
            .withUserConfiguration(WalletInboxInspectionController.class);

    @Test
    void productionProfile_shouldNotRegisterControllerEvenWhenPropertyIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "eap.wallet.inbox-admin.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(WalletInboxInspectionController.class));
    }

    @Test
    void loadtestProfileAndExplicitProperty_shouldRegisterController() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=loadtest",
                        "eap.wallet.inbox-admin.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(WalletInboxInspectionController.class));
    }

    @Test
    void loadtestProfileWithoutProperty_shouldNotRegisterController() {
        contextRunner
                .withPropertyValues("spring.profiles.active=loadtest")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(WalletInboxInspectionController.class));
    }
}
