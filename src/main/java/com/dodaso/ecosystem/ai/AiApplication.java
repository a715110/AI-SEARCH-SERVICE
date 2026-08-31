package com.dodaso.ecosystem.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
