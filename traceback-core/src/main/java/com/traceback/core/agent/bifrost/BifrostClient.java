package com.traceback.core.agent.bifrost;

/**
 * Client interface for interacting with the Bifrost AI Gateway.
 */
public interface BifrostClient {
    /**
     * Sends a chat completion request to the Bifrost AI Gateway.
     *
     * @param request the chat completion request
     * @return the gateway's completion response
     * @throws BifrostException if gateway call fails or returns an error status
     */
    BifrostResponse chatCompletion(BifrostRequest request);

    /**
     * Checks if Bifrost is properly configured with an API key.
     *
     * @return true if ready to handle requests
     */
    boolean isAvailable();

    /**
     * Returns the active Bifrost configuration.
     */
    BifrostConfig getConfig();
}
