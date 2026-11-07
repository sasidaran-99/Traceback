package com.traceback.core.agent.bifrost;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Chat message representation for the OpenAI-compatible Bifrost API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BifrostMessage(
        String role,
        String content
) {
    public static BifrostMessage system(String content) {
        return new BifrostMessage("system", content);
    }

    public static BifrostMessage user(String content) {
        return new BifrostMessage("user", content);
    }

    public static BifrostMessage assistant(String content) {
        return new BifrostMessage("assistant", content);
    }
}
