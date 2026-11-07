package com.traceback.core.agent.bifrost;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Chat completion request payload for Bifrost gateway.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BifrostRequest(
        String model,
        List<BifrostMessage> messages,
        Double temperature,
        Integer max_tokens
) {
    public static BifrostRequest of(String model, List<BifrostMessage> messages) {
        return new BifrostRequest(model, messages, 0.2, 2048);
    }
}
