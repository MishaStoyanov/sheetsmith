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
        // Trimmed by hand rather than by regex: "/+$" is a pattern whose runtime grows with the
        // number of trailing slashes, and this is a two-line loop that cannot backtrack at all.
        String base = baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String tagsUrl = base + "/api/tags";
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
