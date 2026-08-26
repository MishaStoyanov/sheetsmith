package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class OllamaModelService {

    private final RestClient restClient = RestClient.create();

    public List<String> listModels(String baseUrl) {
        String tagsUrl = baseUrl.replaceAll("/+$", "") + "/api/tags";
        try {
            JsonNode response = restClient.get()
                    .uri(tagsUrl)
                    .retrieve()
                    .body(JsonNode.class);

            List<String> models = new ArrayList<>();
            if (response != null && response.has("models")) {
                for (JsonNode model : response.get("models")) {
                    models.add(model.get("name").asText());
                }
            }
            models.sort(String::compareTo);
            return models;
        } catch (Exception e) {
            log.warn("Failed to fetch Ollama models from {}: {}", tagsUrl, e.getMessage());
            throw new ApiException(ErrorCode.OLLAMA_UNREACHABLE,
                    "Could not reach Ollama at " + baseUrl + ": " + e.getMessage());
        }
    }
}
