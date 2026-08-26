package com.ap0stole.sheetsmith.domain.dto.chat;

import com.ap0stole.sheetsmith.domain.entity.ChatMessage;

import java.time.LocalDateTime;
import java.util.List;

public record ChatMessageDto(
        Long id,
        String role,
        String content,
        List<ChatStepDto> steps,
        Integer revisionAfter,
        LocalDateTime createdAt
) {

    public static ChatMessageDto from(ChatMessage message, List<ChatStepDto> steps) {
        return new ChatMessageDto(
                message.getId(),
                message.getRole().name(),
                message.getContent(),
                steps,
                message.getRevisionAfter(),
                message.getCreatedAt());
    }
}
