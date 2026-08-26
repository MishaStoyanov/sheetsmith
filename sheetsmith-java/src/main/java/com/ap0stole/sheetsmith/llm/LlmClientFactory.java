package com.ap0stole.sheetsmith.llm;

import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds a Spring AI {@link ChatModel} from the user's saved {@link LlmSettingsDto}
 * instead of the Spring-profile-selected bean. Caches the last-built model so repeated
 * plan/fix calls with unchanged settings don't re-create HTTP clients each time.
 */
@Component
public class LlmClientFactory {

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
        OllamaOptions options = OllamaOptions.builder().model(local.model()).build();
        return OllamaChatModel.builder().ollamaApi(api).defaultOptions(options).build();
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
            case "CLAUDE" -> AnthropicChatModel.builder()
                    .anthropicApi(AnthropicApi.builder().apiKey(apiKey).build())
                    .defaultOptions(AnthropicChatOptions.builder().model(model).build())
                    .build();
            default -> throw new ApiException(ErrorCode.LLM_FAILURE, "Unknown cloud provider: " + provider);
        };
    }

    /** A null {@code baseUrl} leaves the builder's own default, which is OpenAI's. */
    private ChatModel openAiCompatible(String baseUrl, String apiKey, String model) {
        OpenAiApi.Builder api = OpenAiApi.builder().apiKey(apiKey);
        if (baseUrl != null) {
            api.baseUrl(baseUrl);
        }
        return OpenAiChatModel.builder()
                .openAiApi(api.build())
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
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
