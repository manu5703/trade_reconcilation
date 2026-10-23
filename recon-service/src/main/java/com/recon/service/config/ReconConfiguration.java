package com.recon.service.config;

import com.recon.core.model.AssetClass;
import com.recon.core.tolerance.ToleranceConfig;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import java.util.Properties;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class ReconConfiguration {

    private static final String[] TOLERANCE_KEYS = {"price.abs", "price.bps", "netAmount.abs", "netAmount.bps", "commission.abs"};

    /** Injected everywhere "now" matters, so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Tolerances from the {@code tolerance.*} properties (see spring.config.import). A missing key
     * fails startup with the key's name rather than at the first run.
     */
    @Bean
    ToleranceConfig toleranceConfig(Environment environment) {
        Properties properties = new Properties();
        for (AssetClass assetClass : AssetClass.values()) {
            for (String key : TOLERANCE_KEYS) {
                String name = "tolerance." + assetClass.name() + "." + key;
                String value = environment.getProperty(name);
                if (value != null) {
                    properties.setProperty(name, value);
                }
            }
        }
        return ToleranceConfig.fromProperties(properties);
    }

    /** Runs execute off the request thread; the POST returns 202 straight away. */
    @Bean
    Executor runExecutor(ReconProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("recon-run-");
        executor.setCorePoolSize(properties.runThreads());
        executor.setMaxPoolSize(properties.runThreads());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Trade Reconciliation API")
                .version("1.0")
                .description("Matches internal trade bookings against broker and clearing confirmations and manages the resulting break queue."));
    }
}
