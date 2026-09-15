package com.eap.eap_wallet.configuration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class WalletSchedulerConfig {

    public static final String DEFAULT_SCHEDULER = "taskScheduler";
    public static final String DURABLE_DEBT_SCHEDULER = "durableDebtTaskScheduler";

    @Bean(name = DEFAULT_SCHEDULER)
    ThreadPoolTaskScheduler taskScheduler() {
        return singleThreadScheduler("wallet-scheduler-");
    }

    @Bean(name = DURABLE_DEBT_SCHEDULER)
    ThreadPoolTaskScheduler durableDebtTaskScheduler() {
        return singleThreadScheduler("wallet-durable-debt-");
    }

    private ThreadPoolTaskScheduler singleThreadScheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
