package com.ap0stole.sheetsmith.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Where the spreadsheets are kept and how many of them to keep — one row for the whole instance.
 * <p>
 * Every field is nullable, and null means unset rather than zero: no root of its own means the
 * directories the instance was started with, and no cap means keep everything until the TTL takes
 * it. Zero would mean "keep nothing", which is not something anybody should be able to say by
 * leaving a box empty.
 */
@Entity
@Table(name = "storage_settings")
@Getter
@Setter
@NoArgsConstructor
public class StorageSettingsEntity {

    /** One instance, one answer to "where do the files go". The database enforces it too. */
    public static final Short GLOBAL_ID = 1;

    @Id
    private Short id;

    @Column(name = "root_dir")
    private String rootDir;

    @Column(name = "max_files")
    private Integer maxFiles;

    @Column(name = "max_bytes")
    private Long maxBytes;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
