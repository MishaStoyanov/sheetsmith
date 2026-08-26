package com.ap0stole.sheetsmith.requests;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Data
public class ActionStep {
    // Поддерживаем оба варианта названия поля
    @JsonAlias({"action", "type"})
    private String type;

    // Сюда Jackson сложит всё остальное, что не разложилось по полям
    private Map<String, Object> properties = new HashMap<>();

    @JsonAnySetter
    public void addProperty(String key, Object value) {
        properties.put(key, value);
    }
}