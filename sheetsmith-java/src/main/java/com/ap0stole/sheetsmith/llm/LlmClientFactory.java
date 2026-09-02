package com.ap0stole.sheetsmith.llm;

import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds a Spring AI {@link ChatModel} from the user's saved {@link LlmSettingsDto}
 * instead of the Spring-profile-selected bean. Caches the last-built model so repeated
 * plan/fix calls with unchanged settings don't re-create HTTP clients each time.
 */
@Component
public class LlmClientFactory {

    /** Room for a plan over a wide sheet; Anthropic refuses a request that does not name a ceiling. */
    private static final int ANTHROPIC_MAX_TOKENS = 8192;

    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    private record CacheEntry(String key, ChatModel model) {}

    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    public ChatModel getChatModel(LlmSettingsDto settings) {
        String key = cacheKey(settings);
        CacheEntry entry = cache.get();
        if (entry != null && entry.key().equals(key)) {
            return entry.model();
        }
        ChatModel model = build(settings);
        cache.set(new CacheEntry(key, model));
        return model;
    }

    private ChatModel build(LlmSettingsDto settings) {
        return "CLOUD".equals(settings.providerMode())
                ? buildCloud(settings.cloud())
                : buildLocal(settings.local());
    }

    private ChatModel buildLocal(LlmSettingsDto.LocalSettings local) {
        OllamaApi api = OllamaApi.builder().baseUrl(local.baseUrl()).build();
        OllamaChatOptions options = OllamaChatOptions.builder().model(local.model()).build();
        return OllamaChatModel.builder().ollamaApi(api).options(options).build();
    }

    private ChatModel buildCloud(LlmSettingsDto.CloudSettings cloud) {
        String provider = cloud.activeProvider();
        String apiKey = cloud.apiKeys() != null ? cloud.apiKeys().get(provider) : null;
        String model = cloud.models() != null ? cloud.models().get(provider) : null;

        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(ErrorCode.LLM_FAILURE, "No API key configured for provider " + provider);
        }
        if (model == null || model.isBlank()) {
            throw new ApiException(ErrorCode.LLM_FAILURE, "No model configured for provider " + provider);
        }

        return switch (provider) {
            case "OPENAI" -> openAiCompatible(null, apiKey, model);
            // Both speak the OpenAI wire protocol, so the only difference is where they live.
            case "GEMINI" -> openAiCompatible(GEMINI_BASE_URL, apiKey, model);
            case "DEEPSEEK" -> openAiCompatible(DEEPSEEK_BASE_URL, apiKey, model);
            case "CLAUDE" -> anthropic(apiKey, model);
            default -> throw new ApiException(ErrorCode.LLM_FAILURE, "Unknown cloud provider: " + provider);
        };
    }

    /**
     * A null {@code baseUrl} leaves the SDK's own default, which is OpenAI's.
     * <p>
     * Both the synchronous and the asynchronous client have to be supplied. Given only one, the
     * model builder constructs the other itself from ambient configuration — which here has no
     * credential at all, so building the model throws "At least one credential source must be
     * specified" even though a key was passed. The message names the thing that was provided,
     * which is what makes it slow to read.
     */
    private ChatModel openAiCompatible(String baseUrl, String apiKey, String model) {
        OpenAIOkHttpClient.Builder sync = OpenAIOkHttpClient.builder().apiKey(apiKey);
        OpenAIOkHttpClientAsync.Builder async = OpenAIOkHttpClientAsync.builder().apiKey(apiKey);
        if (baseUrl != null) {
            sync.baseUrl(baseUrl);
            async.baseUrl(baseUrl);
        }
        return OpenAiChatModel.builder()
                .openAiClient(sync.build())
                .openAiClientAsync(async.build())
                .options(OpenAiChatOptions.builder().model(model).build())
                .build();
    }

    /**
     * Both clients, for the reason given on {@link #openAiCompatible}, and two options the other
     * providers do not need.
     * <p>
     * Anthropic is the only one of the four whose API <em>requires</em> a token ceiling, so leaving
     * it to whatever the library defaults to is leaving the size of an answer to a stranger. A plan
     * for a wide sheet is a long piece of JSON and a low ceiling truncates it into nothing.
     * <p>
     * Thinking is off because this call wants one JSON object back. With it on, the reply leads
     * with a reasoning block and the text this code reads can come back empty — a failure that
     * arrives as "the AI returned an empty response" with no error anywhere to explain it.
     */
    private ChatModel anthropic(String apiKey, String model) {
        AnthropicChatOptions options = (AnthropicChatOptions) AnthropicChatOptions.builder()
                .model(model)
                .thinkingDisabled()
                .maxTokens(ANTHROPIC_MAX_TOKENS)
                .build();
        return AnthropicChatModel.builder()
                .anthropicClient(AnthropicOkHttpClient.builder().apiKey(apiKey).build())
                .anthropicClientAsync(AnthropicOkHttpClientAsync.builder().apiKey(apiKey).build())
                .options(options)
                .build();
    }

    private String cacheKey(LlmSettingsDto settings) {
        if ("CLOUD".equals(settings.providerMode())) {
            LlmSettingsDto.CloudSettings cloud = settings.cloud();
            String provider = cloud.activeProvider();
            String apiKey = cloud.apiKeys() != null ? cloud.apiKeys().get(provider) : null;
            String model = cloud.models() != null ? cloud.models().get(provider) : null;
            return "CLOUD:" + provider + ":" + apiKey + ":" + model;
        }
        return "LOCAL:" + settings.local().baseUrl() + ":" + settings.local().model();
    }
}
