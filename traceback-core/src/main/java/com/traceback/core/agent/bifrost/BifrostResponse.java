package com.traceback.core.agent.bifrost;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Standard OpenAI-compatible chat completion response schema from Bifrost.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BifrostResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            BifrostMessage message,
            String finish_reason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            int prompt_tokens,
            int completion_tokens,
            int total_tokens
    ) {}

    public String firstContent() {
        if (choices != null && !choices.isEmpty() && choices.getFirst().message() != null) {
            String text = choices.getFirst().message().content();
            return text != null ? text : "";
        }
        return "";
    }
}
