package com.ap0stole.sheetsmith.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A conversation bound to one working copy of an uploaded workbook.
 * <p>
 * The chat never touches the file the user gave us: every session gets its own directory of
 * revisions ({@code rev-0.xlsx}, {@code rev-1.xlsx}, …), so any edit can be undone and the
 * original stays intact.
 */
@Entity
@Table(name = "chat_sessions")
@Getter
@Setter
@NoArgsConstructor
public class ChatSession {

    @Id
    private String id;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastActivityAt;

    @Column(nullable = false)
    private String originalFilename;

    /** Directory holding every revision of this session's working copy. */
    @Column(nullable = false)
    private String directory;

    /** Highest revision written so far; also the one currently shown to the user. */
    @Column(nullable = false)
    private int currentRevision;

    public static ChatSession create(String originalFilename, String directory) {
        ChatSession session = new ChatSession();
        session.id = UUID.randomUUID().toString();
        session.createdAt = LocalDateTime.now();
        session.lastActivityAt = session.createdAt;
        session.originalFilename = originalFilename;
        session.directory = directory;
        session.currentRevision = 0;
        return session;
    }

    public void touch() {
        this.lastActivityAt = LocalDateTime.now();
    }
}
