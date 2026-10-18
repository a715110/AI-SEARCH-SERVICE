package com.dodaso.ecosystem.ai.config;

import com.dodaso.ecosystem.baseline.common.config.CompanySettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The company's AI settings from this instance's configuration (CompanySettings). */
@Configuration
public class CompanySettingsConfig {

    @Bean
    public CompanySettings companySettings(
            @Value("${dodaso.ai.mcp-access:off}") String mcpAccess,
            @Value("${dodaso.ai.in-app-answers:on}") String inAppAnswers) {
        return new CompanySettings(mcpAccess, inAppAnswers);
    }
}
