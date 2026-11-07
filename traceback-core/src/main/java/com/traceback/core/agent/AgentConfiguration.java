package com.traceback.core.agent;

import com.traceback.core.agent.bifrost.BifrostClient;
import com.traceback.core.agent.bifrost.BifrostConfig;
import com.traceback.core.agent.bifrost.DefaultBifrostClient;
import com.traceback.core.agent.tavily.DefaultTavilyResearchService;
import com.traceback.core.agent.tavily.TavilyConfig;
import com.traceback.core.agent.tavily.TavilyResearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing default Bifrost and Tavily client beans.
 */
@Configuration
public class AgentConfiguration {

    @Bean
    @ConditionalOnMissingBean(BifrostClient.class)
    public BifrostClient bifrostClient() {
        return new DefaultBifrostClient(BifrostConfig.fromEnvironment());
    }

    @Bean
    @ConditionalOnMissingBean(TavilyResearchService.class)
    public TavilyResearchService tavilyResearchService() {
        return new DefaultTavilyResearchService(TavilyConfig.fromEnvironment());
    }
}
