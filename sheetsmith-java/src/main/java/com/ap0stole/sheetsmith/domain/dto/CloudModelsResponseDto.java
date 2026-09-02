package com.ap0stole.sheetsmith.domain.dto;

import java.util.List;

/**
 * What a cloud vendor says it will answer to, filtered to the models that can hold a conversation.
 *
 * @param models model names exactly as the vendor spells them, which is how they have to be sent back
 */
public record CloudModelsResponseDto(List<String> models) {}
