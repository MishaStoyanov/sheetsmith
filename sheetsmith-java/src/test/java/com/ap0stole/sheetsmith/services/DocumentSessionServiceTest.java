package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.FileStorageConfig;
import com.ap0stole.sheetsmith.domain.dto.ExcelSchemaDto;
import com.ap0stole.sheetsmith.domain.dto.SheetSchemaDto;
import com.ap0stole.sheetsmith.domain.dto.DocumentSessionDto;
import com.ap0stole.sheetsmith.domain.entity.DocumentSession;
import com.ap0stole.sheetsmith.domain.enums.ChatRole;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.repository.ChatMessageRepository;
import com.ap0stole.sheetsmith.repository.DocumentSessionRepository;
import com.ap0stole.sheetsmith.repository.ChatStepRepository;
import com.ap0stole.sheetsmith.services.SchemaExtractorService;
import com.ap0stole.sheetsmith.services.SessionSchemaCache;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The working copy is the chat's safety net: the upload is never edited and history is
 * append-only, so these tests pin that behaviour down.
 */
class DocumentSessionServiceTest {

    private DocumentSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private SchemaExtractorService schemaExtractorService;
    private DocumentSessionService service;
    private Path sessionRoot;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        sessionRoot = tempDir.resolve("sessions");

        FileStorageConfig storageConfig = new FileStorageConfig();
        storageConfig.setUploadDir(tempDir.resolve("uploads").toString());
        storageConfig.setResultDir(tempDir.resolve("results").toString());
        storageConfig.setSessionDir(sessionRoot.toString());

        sessionRepository = mock(DocumentSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        ChatStepRepository stepRepository = mock(ChatStepRepository.class);
        schemaExtractorService = mock(SchemaExtractorService.class);

        when(sessionRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(schemaExtractorService.extract(anyString())).thenReturn(ExcelSchemaDto.builder()
                .sheets(List.of(SheetSchemaDto.builder()
                        .sheetName("Sales")
                        .headerRange("A1:B1")
                        .dataRange("A2:B2")
                        .columns(List.of("Product", "Revenue"))
                        .existingFormulas(List.of())
                        .build()))
                .charts(List.of())
                .build());

        service = new DocumentSessionService(storageConfig, sessionRepository, messageRepository,
                stepRepository, new SessionSchemaCache(schemaExtractorService));
    }

    @Test
    @DisplayName("a new session starts from a copy, leaving the upload untouched")
    void createsWorkingCopy() throws Exception {
        DocumentSessionDto dto = service.create(upload("sales.xlsx"));

        assertThat(dto.revision()).isZero();
        assertThat(dto.filename()).isEqualTo("sales.xlsx");
        assertThat(dto.sheets()).containsExactly("Sales");
        assertThat(sessionRoot.resolve(dto.sessionId()).resolve("rev-0.xlsx")).exists();
    }

    @Test
    @DisplayName("editing the workbook writes the next revision and leaves the previous one intact")
    void commitsNextRevision() throws Exception {
        DocumentSession session = openSession();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Edited");
            int revision = service.commitRevision(session, workbook);

            assertThat(revision).isEqualTo(1);
            assertThat(session.getCurrentRevision()).isEqualTo(1);
            assertThat(service.revisionPath(session, 0)).exists();
            assertThat(service.revisionPath(session, 1)).exists();
        }
    }

    @Test
    @DisplayName("undo copies an old revision forward instead of deleting history")
    void revertIsAppendOnly() throws Exception {
        DocumentSession session = openSession();
        long originalSize = Files.size(service.revisionPath(session, 0));

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Edited");
            service.commitRevision(session, workbook);
        }

        int revision = service.revert(session.getId(), 0);

        assertThat(revision).isEqualTo(2);
        assertThat(session.getCurrentRevision()).isEqualTo(2);
        assertThat(service.revisionPath(session, 1)).exists();
        assertThat(Files.size(service.revisionPath(session, 2))).isEqualTo(originalSize);
        verify(messageRepository).save(argThat(message -> message.getRole() == ChatRole.SYSTEM));
    }

    @Test
    @DisplayName("a revision an improve job wrote itself becomes current and is announced in the transcript")
    void commitsExternalRevision() throws Exception {
        DocumentSession session = openSession();
        Files.copy(service.revisionPath(session, 0), service.nextRevisionPath(session));

        int revision = service.commitExternalRevision(session, "Applied outside the chat.");

        assertThat(revision).isEqualTo(1);
        assertThat(session.getCurrentRevision()).isEqualTo(1);
        assertThat(service.revisionPath(session, 0)).exists();
        verify(messageRepository).save(argThat(message ->
                message.getRole() == ChatRole.SYSTEM && message.getRevisionAfter() == 1));
    }

    @Test
    @DisplayName("a revision nobody wrote is refused rather than pointing the session at nothing")
    void refusesToCommitARevisionThatWasNeverWritten() throws Exception {
        DocumentSession session = openSession();

        assertThatThrownBy(() -> service.commitExternalRevision(session, "Applied outside the chat."))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Nothing was written");
        assertThat(session.getCurrentRevision()).isZero();
    }

    @Test
    @DisplayName("reverting to a revision that never existed is rejected")
    void rejectsUnknownRevision() throws Exception {
        DocumentSession session = openSession();

        assertThatThrownBy(() -> service.revert(session.getId(), 7))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No such revision");
    }

    @Test
    @DisplayName("only .xlsx files can open a session")
    void rejectsNonXlsx() {
        MockMultipartFile csv = new MockMultipartFile("file", "sales.csv", "text/csv", new byte[]{1, 2});

        assertThatThrownBy(() -> service.create(csv))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only .xlsx");
    }

    @Test
    @DisplayName("a revision's schema is read from the file once, however often it is asked for")
    void readsARevisionOnlyOnce() throws Exception {
        DocumentSession session = openSession();

        service.describe(session.getId());
        service.describe(session.getId());
        service.tableContext(session);

        verify(schemaExtractorService, times(1)).extract(anyString());
    }

    @Test
    @DisplayName("a new revision busts the cached schema — the chat must never see the old sheet")
    void newRevisionBustsTheCachedSchema() throws Exception {
        DocumentSession session = openSession();
        service.describe(session.getId());

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Edited");
            service.commitRevision(session, workbook);
        }
        service.describe(session.getId());

        verify(schemaExtractorService).extract(endsWith("rev-0.xlsx"));
        verify(schemaExtractorService).extract(endsWith("rev-1.xlsx"));
    }

    @Test
    @DisplayName("deleting a session drops its cached schemas along with its files")
    void deleteEvictsTheCachedSchema() throws Exception {
        DocumentSession session = openSession();
        service.describe(session.getId());

        service.delete(session.getId());
        service.describe(session.getId());

        verify(schemaExtractorService, times(2)).extract(anyString());
    }

    /** Creates a session on disk and wires the repository to hand it back by id. */
    private DocumentSession openSession() throws Exception {
        DocumentSessionDto dto = service.create(upload("sales.xlsx"));
        DocumentSession session = DocumentSession.create("sales.xlsx",
                sessionRoot.resolve(dto.sessionId()).toString());
        setId(session, dto.sessionId());
        when(sessionRepository.findById(dto.sessionId())).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderByIdAsc(dto.sessionId())).thenReturn(List.of());
        return session;
    }

    private void setId(DocumentSession session, String id) throws Exception {
        var field = DocumentSession.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(session, id);
    }

    private MockMultipartFile upload(String name) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("Sales");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Product");
            header.createCell(1).setCellValue("Revenue");
            workbook.write(out);
            return new MockMultipartFile("file", name,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }
}
