package com.ap0stole.sheetsmith.requests;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AutomationRequest {
    @JsonAlias({"steps", "actions", "commands"})
    private List<ActionStep> actions = new ArrayList<>();
}