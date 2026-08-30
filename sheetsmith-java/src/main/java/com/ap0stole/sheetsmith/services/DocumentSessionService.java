package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.FileStorageConfig;
import com.ap0stole.sheetsmith.domain.dto.ExcelSchemaDto;
import com.ap0stole.sheetsmith.domain.dto.SheetSchemaDto;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatMessageDto;
import com.ap0stole.sheetsmith.domain.dto.DocumentSessionDto;
import com.ap0stole.sheetsmith.domain.dto.chat.ChatStepDto;
import com.ap0stole.sheetsmith.domain.entity.ChatMessage;
import com.ap0stole.sheetsmith.domain.entity.DocumentSession;
import com.ap0stole.sheetsmith.domain.entity.ChatStep;
import com.ap0stole.sheetsmith.domain.enums.ChatRole;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.repository.ChatMessageRepository;
import com.ap0stole.sheetsmith.repository.DocumentSessionRepository;
import com.ap0stole.sheetsmith.repository.ChatStepRepository;
import com.ap0stole.sheetsmith.services.SessionSchemaCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Owns the session's working copy: the file the user uploaded is never edited, only a private copy
 * kept as an append-only chain of revisions, so any step can be undone.
 * <p>
 * Despite the name this is the shared document workspace for <em>both</em> flows — the chat commits
 * revisions here and so does the improve job (see {@code JobService}), which is why the revision
 * machinery below is public rather than an internal detail of the chat.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSessionService {

    private static final String REVISION_PREFIX = "rev-";

    private final FileStorageConfig storageConfig;
    private final DocumentSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatStepRepository stepRepository;
    private final SessionSchemaCache schemaCache;
    private final UsageRecorder usageRecorder;
    private final WorkVisibility visibility;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    // rollbackFor because the method throws a checked exception: without it Spring commits the
    // row and leaves the half-written upload on disk described by a session nobody can open.
    @Transactional(rollbackFor = IOException.class)
    public DocumentSessionDto create(MultipartFile file) throws IOException {
        validateXlsx(file);

        DocumentSession session = DocumentSession.create(originalName(file), "");
        // Read here, on the request thread, where the caller is still known.
        session.setUser(usageRecorder.caller());
        Path directory = Path.of(storageConfig.getSessionDir()).resolve(session.getId());
        Files.createDirectories(directory);
        session.setDirectory(directory.toAbsolutePath().toString());

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, revisionPath(session, 0), StandardCopyOption.REPLACE_EXISTING);
        }

        ExcelSchemaDto schema = schemaCache.get(session.getId(), 0, revisionPath(session, 0).toString());
        sessionRepository.save(session);
        log.info("Chat session {} created for '{}'", session.getId(), session.getOriginalFilename());

        return describe(session, 0, schema);
    }

    /**
     * The session, or a refusal naming it.
     * <p>
     * Annotated for callers in other beans; the methods in this class call {@link #sessionOf}
     * directly, because a call through {@code this} never reaches the proxy and an annotation that
     * does nothing where it is written is one the next reader will believe.
     */
    @Transactional(readOnly = true)
    public DocumentSession require(String sessionId) {
        return sessionOf(sessionId);
    }

    private DocumentSession sessionOf(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND,
                        "Chat session not found or expired: " + sessionId));
    }

    @Transactional(readOnly = true)
    public DocumentSessionDto describe(String sessionId) {
        DocumentSession session = sessionOf(sessionId);
        return describe(session, session.getCurrentRevision(), schema(session));
    }

    /** Keeps a session alive while it is being used — cleanup only removes idle ones. */
    @Transactional
    public void touch(DocumentSession session) {
        session.touch();
        sessionRepository.save(session);
    }

    /**
     * Removing anything at all is the superadmin's alone.
     * <p>
     * Deletion is the one action with no undo and no audit trail left behind to read afterwards —
     * the record that would say who did it is the record that went. Everything else on this
     * instance can be corrected by somebody with the right role; this cannot be corrected by
     * anybody, so it sits with the one account that cannot itself be deleted.
     */
    @Transactional
    public void delete(String sessionId) {
        DocumentSession session = sessionOf(sessionId);
        deleteQuietly(session);
        log.info("Chat session {} deleted", sessionId);
    }

    /** Used by the daily cleanup job to drop sessions nobody has touched in a while. */
    @Transactional
    public int deleteIdleSince(LocalDateTime cutoff) {
        List<DocumentSession> stale = sessionRepository.findByLastActivityAtBefore(cutoff);
        stale.forEach(this::deleteQuietly);
        return stale.size();
    }

    // ── Revisions ─────────────────────────────────────────────────────────────

    public Path revisionPath(DocumentSession session, int revision) {
        return Path.of(session.getDirectory()).resolve(REVISION_PREFIX + revision + ".xlsx");
    }

    public Path currentPath(DocumentSession session) {
        return revisionPath(session, session.getCurrentRevision());
    }

    /**
     * The slot the next revision goes into. Writers that produce the file themselves — the improve
     * job writes its result straight to disk — aim here and then call
     * {@link #commitExternalRevision}; nobody else works out the next number.
     */
    public Path nextRevisionPath(DocumentSession session) {
        return revisionPath(session, session.getCurrentRevision() + 1);
    }

    /**
     * Writes the edited workbook as the next revision. Formulas are evaluated first so the
     * preview grid — which reads cached values — shows numbers rather than blanks.
     */
    @Transactional(rollbackFor = IOException.class)
    public int commitRevision(DocumentSession session, XSSFWorkbook workbook) throws IOException {
        int next = session.getCurrentRevision() + 1;
        try {
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
        } catch (Exception e) {
            log.warn("Formula evaluation failed for session {} — writing unevaluated: {}",
                    session.getId(), e.getMessage());
        }
        try (FileOutputStream out = new FileOutputStream(nextRevisionPath(session).toFile())) {
            workbook.write(out);
        }
        session.setCurrentRevision(next);
        session.touch();
        sessionRepository.save(session);
        log.info("Chat session {} advanced to revision {}", session.getId(), next);
        return next;
    }

    /**
     * Publishes a file another writer already produced at {@link #nextRevisionPath} as the current
     * revision — the improve job writes the workbook itself, so only the pointer has to move. The
     * caller holds the session lock, and {@code note} lands in the transcript so the chat knows the
     * sheet moved under it.
     */
    @Transactional
    public int commitExternalRevision(DocumentSession session, String note) {
        int next = session.getCurrentRevision() + 1;
        if (!Files.exists(revisionPath(session, next))) {
            throw new ApiException(ErrorCode.FILE_NOT_FOUND,
                    "Nothing was written for revision " + next + " of session " + session.getId());
        }

        session.setCurrentRevision(next);
        session.touch();
        sessionRepository.save(session);

        write(session, ChatRole.SYSTEM, note, next);
        log.info("Chat session {} advanced to externally written revision {}", session.getId(), next);
        return next;
    }

    /** Undo is append-only: reverting copies an old revision forward instead of deleting history. */
    @Transactional(rollbackFor = IOException.class)
    public int revert(String sessionId, int revision) throws IOException {
        DocumentSession session = sessionOf(sessionId);
        if (revision < 0 || revision > session.getCurrentRevision()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "No such revision: " + revision, "revision");
        }
        Path source = revisionPath(session, revision);
        if (!Files.exists(source)) {
            throw new ApiException(ErrorCode.FILE_NOT_FOUND, "Revision file is gone: " + revision);
        }

        int next = session.getCurrentRevision() + 1;
        Files.copy(source, revisionPath(session, next), StandardCopyOption.REPLACE_EXISTING);
        session.setCurrentRevision(next);
        session.touch();
        sessionRepository.save(session);

        write(session, ChatRole.SYSTEM, "Undone — the sheet is back to the state it had at step " + revision + ".", next);
        log.info("Chat session {} reverted to revision {} (as {})", sessionId, revision, next);
        return next;
    }

    @Transactional(readOnly = true)
    public Resource currentFile(String sessionId) {
        DocumentSession session = sessionOf(sessionId);
        Path path = currentPath(session);
        if (!Files.exists(path)) {
            throw new ApiException(ErrorCode.FILE_NOT_FOUND, "Working copy missing for session " + sessionId);
        }
        return new FileSystemResource(path);
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    /**
     * Writes one message into the session's history.
     * <p>
     * Named {@code note} rather than {@code record}: the shorter name is a restricted identifier
     * now that the language has records, and this class declares none — which is exactly the kind
     * of collision that reads fine until somebody adds one.
     */
    @Transactional
    public ChatMessage note(DocumentSession session, ChatRole role, String content, Integer revisionAfter) {
        return write(session, role, content, revisionAfter);
    }

    private ChatMessage write(DocumentSession session, ChatRole role, String content, Integer revisionAfter) {
        ChatMessage message = ChatMessage.of(session, role, content);
        message.setRevisionAfter(revisionAfter);
        return messageRepository.save(message);
    }

    @Transactional
    public void saveSteps(List<ChatStep> steps) {
        if (!steps.isEmpty()) stepRepository.saveAll(steps);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageDto> history(String sessionId) {
        sessionOf(sessionId);
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
        if (messages.isEmpty()) return List.of();

        Map<Long, List<ChatStepDto>> stepsByMessage = stepRepository
                .findByMessageIdInOrderByMessageIdAscExecutionOrderAsc(messages.stream().map(ChatMessage::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(step -> step.getMessage().getId(),
                        Collectors.mapping(ChatStepDto::from, Collectors.toList())));

        return messages.stream()
                .map(m -> ChatMessageDto.from(m, stepsByMessage.getOrDefault(m.getId(), List.of())))
                .toList();
    }

    /** The last few turns, flattened for the prompt — enough for follow-ups like "and by region?". */
    @Transactional(readOnly = true)
    public String recentHistoryPrompt(String sessionId, int limit) {
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
        return messages.stream()
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .skip(Math.max(0, messages.size() - limit))
                .map(m -> switch (m.getRole()) {
                    case USER -> "User: " + m.getContent();
                    case ASSISTANT -> "You: " + m.getContent();
                    case SYSTEM -> "System: " + m.getContent();
                })
                .collect(Collectors.joining("\n"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public String tableContext(DocumentSession session) {
        return schema(session).toPromptString();
    }

    /**
     * The structure of the session's current revision. Every caller goes through here so the
     * workbook is opened once per revision rather than once per request.
     */
    public ExcelSchemaDto schema(DocumentSession session) {
        return schemaCache.get(session.getId(), session.getCurrentRevision(), currentPath(session).toString());
    }

    /**
     * One schema read serves both halves of the DTO — the charts have to come from the same
     * revision as the sheet names, or the preview would draw a chart the sheet no longer has.
     */
    private DocumentSessionDto describe(DocumentSession session, int revision, ExcelSchemaDto schema) {
        return new DocumentSessionDto(
                session.getId(),
                session.getOriginalFilename(),
                revision,
                schema.getSheets().stream().map(SheetSchemaDto::getSheetName).toList(),
                schema.getCharts());
    }

    private void deleteQuietly(DocumentSession session) {
        schemaCache.evictSession(session.getId());
        List<Long> messageIds = messageRepository.findBySessionIdOrderByIdAsc(session.getId())
                .stream().map(ChatMessage::getId).toList();
        if (!messageIds.isEmpty()) {
            stepRepository.deleteByMessageIdIn(messageIds);
            messageRepository.deleteBySessionId(session.getId());
        }
        sessionRepository.delete(session);

        try (var paths = Files.walk(Path.of(session.getDirectory()))) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean session directory {}: {}", session.getDirectory(), e.getMessage());
        }
    }

    private void validateXlsx(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_INVALID, "File is empty");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".xlsx")) {
            throw new ApiException(ErrorCode.FILE_INVALID, "Only .xlsx files are supported", "file");
        }
    }

    private String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return (name == null || name.isBlank()) ? "sheet.xlsx" : name;
    }
}
