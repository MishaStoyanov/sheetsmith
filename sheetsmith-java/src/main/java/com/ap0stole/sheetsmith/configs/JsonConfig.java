package com.ap0stole.sheetsmith.configs;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfig {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_YAML_COMMENTS, true) // Для # комментариев
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
}
