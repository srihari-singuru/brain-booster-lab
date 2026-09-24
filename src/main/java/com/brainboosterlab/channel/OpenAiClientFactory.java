package com.brainboosterlab.channel;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.time.Duration;

/** Creates clients from either the shell environment or the app's ignored local .env properties. */
public final class OpenAiClientFactory {
    private OpenAiClientFactory() {}

    public static OpenAIClient create(Duration timeout) {
        var builder = OpenAIOkHttpClient.builder().timeout(timeout).maxRetries(0);
        String apiKey = System.getProperty("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("OPENAI_API_KEY");
        return apiKey == null || apiKey.isBlank() ? builder.fromEnv().build() : builder.apiKey(apiKey).build();
    }
}
