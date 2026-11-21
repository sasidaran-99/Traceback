package com.traceback.core.agent;

import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.bifrost.BifrostConfig;
import com.traceback.core.agent.bifrost.DefaultBifrostClient;
import com.traceback.core.agent.tavily.DefaultTavilyResearchService;
import com.traceback.core.agent.tavily.TavilyConfig;
import com.traceback.core.agent.tavily.TavilyResearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Spring configuration providing default Bifrost and Tavily client beans.
 * Supports both Spring configuration properties (traceback.bifrost.*, traceback.tavily.*)
 * and direct environment variables.
 */
@Configuration
public class AgentConfiguration {

    @Value("${traceback.bifrost.base-url:}")
    private String bifrostBaseUrl;

    @Value("${traceback.bifrost.api-key:}")
    private String bifrostApiKey;

    @Value("${traceback.bifrost.model:}")
    private String bifrostModel;

    @Value("${traceback.bifrost.timeout-seconds:180}")
    private int bifrostTimeoutSeconds;

    @Value("${traceback.bifrost.max-retries:2}")
    private int bifrostMaxRetries;

    @Value("${traceback.tavily.base-url:}")
    private String tavilyBaseUrl;

    @Value("${traceback.tavily.api-key:}")
    private String tavilyApiKey;

    @Value("${traceback.tavily.max-searches:3}")
    private int tavilyMaxSearches;

    @Value("${traceback.tavily.timeout-seconds:10}")
    private int tavilyTimeoutSeconds;

    @Bean
    @ConditionalOnMissingBean(BifrostClient.class)
    public BifrostClient bifrostClient() {
        BifrostConfig envConfig = BifrostConfig.fromEnvironment();
        String url = (bifrostBaseUrl != null && !bifrostBaseUrl.isBlank()) ? bifrostBaseUrl : envConfig.baseUrl();
        String key = (bifrostApiKey != null && !bifrostApiKey.isBlank()) ? bifrostApiKey : envConfig.apiKey();
        String model = (bifrostModel != null && !bifrostModel.isBlank()) ? bifrostModel : envConfig.model();
        Duration timeout = (bifrostTimeoutSeconds > 0) ? Duration.ofSeconds(bifrostTimeoutSeconds) : envConfig.timeout();
        int retries = (bifrostMaxRetries >= 0) ? bifrostMaxRetries : envConfig.maxRetries();

        return new DefaultBifrostClient(new BifrostConfig(url, key, model, timeout, retries));
    }

    @Bean
    @ConditionalOnMissingBean(TavilyResearchService.class)
    public TavilyResearchService tavilyResearchService() {
        TavilyConfig envConfig = TavilyConfig.fromEnvironment();
        String url = (tavilyBaseUrl != null && !tavilyBaseUrl.isBlank()) ? tavilyBaseUrl : envConfig.baseUrl();
        String key = (tavilyApiKey != null && !tavilyApiKey.isBlank()) ? tavilyApiKey : envConfig.apiKey();
        int max = (tavilyMaxSearches > 0) ? tavilyMaxSearches : envConfig.maxSearchesPerInvestigation();
        Duration timeout = (tavilyTimeoutSeconds > 0) ? Duration.ofSeconds(tavilyTimeoutSeconds) : envConfig.timeout();

        return new DefaultTavilyResearchService(new TavilyConfig(url, key, max, timeout));
    }
}
