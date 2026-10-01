package com.dodaso.ecosystem.ai;

import org.springframework.boot.SpringApplication;
import com.dodaso.ecosystem.baseline.common.config.DodasoInstanceProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {org.springframework.cloud.configuration.CompatibilityVerifierAutoConfiguration.class,
})
@ComponentScan(basePackages = {
    "com.dodaso.ecosystem.ai",
    "com.dodaso.ecosystem.baseline.common"
})
@EnableAsync
@EnableScheduling
// STEP1: binds and validates dodaso.instance.* at startup (was never enabled here, so a
// missing or malformed value went unnoticed). Also used by SimpleCORSFilter.
@EnableConfigurationProperties(DodasoInstanceProperties.class)
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}