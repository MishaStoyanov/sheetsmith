package com.ap0stole.sheetsmith.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "llm_settings")
@Getter
@Setter
@NoArgsConstructor
public class LlmSettingsEntity {

    // Fixed id: one global settings row until per-user auth exists (id will become a user id then).
    public static final Long GLOBAL_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String settingsJson;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static LlmSettingsEntity of(Long id, String settingsJson) {
        LlmSettingsEntity entity = new LlmSettingsEntity();
        entity.id = id;
        entity.settingsJson = settingsJson;
        entity.updatedAt = LocalDateTime.now();
        return entity;
    }
}
