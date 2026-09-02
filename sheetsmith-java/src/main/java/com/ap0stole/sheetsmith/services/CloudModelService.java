package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.domain.dto.LlmSettingsDto;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Asks a cloud vendor which models it will answer to, so the model name can be chosen rather than
 * typed.
 * <p>
 * Typing it is where this went wrong before: a name is exact, it changes when the vendor retires a
 * version, and a wrong one fails at the moment somebody applies a plan rather than at the moment
 * they configure the instance. The list is the vendor's own answer, so it is right by construction
 * and right again tomorrow.
 * <p>
 * The key is read from the stored settings and never accepted from the caller. A key travelling in
 * a query string would end up in access logs, and the one place it is allowed to live is the
 * settings row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudModelService {

    private final LlmSettingsService llmSettingsService;

    private final RestClient restClient = RestClient.create();

    /** Three of the four speak the OpenAI protocol, so only the address and the auth differ. */
    private record Endpoint(String url, String header, String value, String extraHeader, String extraValue) {

        static Endpoint bearer(String url, String key) {
            return new Endpoint(url, "Authorization", "Bearer " + key, null, null);
        }
    }

    /**
     * Names that are on the list but cannot hold a conversation.
     * <p>
     * OpenAI returns everything it sells through one endpoint — embeddings, speech, transcription,
     * images, moderation — and nothing in the response says which is which. Offering those in a
     * chat-model dropdown is offering a choice that fails later, so they are dropped by name. The
     * filter errs towards keeping: an unfamiliar model shown is a nuisance, one hidden is a model
     * the instance cannot use at all.
     */
    private static final List<String> NOT_CHAT = List.of(
            "embedding", "embed", "tts", "whisper", "transcribe", "dall-e", "image", "imagen",
            "moderation", "rerank", "audio", "speech", "sora", "codex-mini",
            // Google's catalogue carries these beside the chat models and claims generateContent
            // for them too, so the capability field does not separate them: lyria writes music,
            // veo and nano-banana draw, robotics drives a machine.
            "lyria", "veo-", "nano-banana", "robotics");

    public List<String> listModels(String provider) {
        LlmSettingsDto settings = llmSettingsService.active();
        LlmSettingsDto.CloudSettings cloud = settings.cloud();
        String key = cloud != null && cloud.apiKeys() != null ? cloud.apiKeys().get(provider) : null;
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.LLM_FAILURE,
                    "No API key is saved for " + provider + ". Save one first, then ask for its models.");
        }

        Endpoint endpoint = endpointFor(provider, key);
        try {
            var request = restClient.get().uri(endpoint.url()).header(endpoint.header(), endpoint.value());
            if (endpoint.extraHeader() != null) {
                request = request.header(endpoint.extraHeader(), endpoint.extraValue());
            }
            JsonNode body = request.retrieve().body(JsonNode.class);
            return "GEMINI".equals(provider) ? geminiChatModels(body) : chatModels(body);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not list models for {}: {}", provider, e.getMessage());
            throw new ApiException(ErrorCode.LLM_FAILURE,
                    "Could not ask " + provider + " for its models: " + e.getMessage());
        }
    }

    private Endpoint endpointFor(String provider, String key) {
        return switch (provider) {
            case "OPENAI" -> Endpoint.bearer("https://api.openai.com/v1/models", key);
            // Google's own endpoint rather than its OpenAI-compatible one, because only this one
            // says what each model can do. The compatible list mixes video, music and image models
            // in with the chat ones and nothing distinguishes them.
            case "GEMINI" -> new Endpoint("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200",
                    "x-goog-api-key", key, null, null);
            case "DEEPSEEK" -> Endpoint.bearer("https://api.deepseek.com/models", key);
            // Anthropic answers the same shape but authenticates its own way, and dates its API.
            // The limit is not optional politeness: this endpoint paginates and defaults to 20,
            // so without it the list comes back quietly cut short and looks complete.
            case "CLAUDE" -> new Endpoint("https://api.anthropic.com/v1/models?limit=1000",
                    "x-api-key", key, "anthropic-version", "2023-06-01");
            default -> throw new ApiException(ErrorCode.LLM_FAILURE, "Unknown cloud provider: " + provider);
        };
    }

    /**
     * All four answer {@code {"data":[{"id":...}]}}. Gemini's OpenAI-compatible endpoint prefixes
     * every id with {@code models/}, which its chat endpoint will not accept back, so that comes off.
     */
    private List<String> chatModels(JsonNode response) {
        List<String> models = new ArrayList<>();
        if (response == null || !response.has("data")) {
            return models;
        }
        for (JsonNode model : response.get("data")) {
            JsonNode id = model.get("id");
            if (id == null) {
                continue;
            }
            String name = id.asString();
            if (name.startsWith("models/")) {
                name = name.substring("models/".length());
            }
            if (!isChat(name)) {
                continue;
            }
            models.add(name);
        }
        models.sort(String::compareTo);
        return models;
    }

    /**
     * Google says outright which models answer a chat call, so nothing here is guessed.
     * <p>
     * {@code supportedGenerationMethods} carries {@code generateContent} for the models that can,
     * and the video, music, image and robotics models in the same catalogue simply do not have it.
     */
    private List<String> geminiChatModels(JsonNode response) {
        List<String> models = new ArrayList<>();
        if (response == null || !response.has("models")) {
            return models;
        }
        for (JsonNode model : response.get("models")) {
            JsonNode methods = model.get("supportedGenerationMethods");
            if (methods == null || !methods.isArray()) {
                continue;
            }
            boolean chats = false;
            for (JsonNode method : methods) {
                if ("generateContent".equals(method.asString())) {
                    chats = true;
                    break;
                }
            }
            JsonNode name = model.get("name");
            if (!chats || name == null) {
                continue;
            }
            String id = name.asString();
            String plain = id.startsWith("models/") ? id.substring("models/".length()) : id;
            // Both filters, because they catch different things. The capability field drops video,
            // music and robotics outright; the name check drops the models that do answer
            // generateContent but return a picture or a sound rather than words.
            if (isChat(plain)) {
                models.add(plain);
            }
        }
        models.sort(String::compareTo);
        return models;
    }

    private boolean isChat(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return NOT_CHAT.stream().noneMatch(lower::contains);
    }
}
