package com.traceback.api.config;

import com.traceback.api.remediation.RemediationSecurityInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web configuration registering security interceptors for remediation endpoints.
 */
@Configuration
public class RemediationWebConfig implements WebMvcConfigurer {

    private final RemediationSecurityInterceptor securityInterceptor;

    public RemediationWebConfig(RemediationSecurityInterceptor securityInterceptor) {
        this.securityInterceptor = securityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(securityInterceptor)
                .addPathPatterns("/api/remediations/**");
    }
}
