package com.ap0stole.sheetsmith.services.chat;

import com.ap0stole.sheetsmith.services.DocumentSessionService;
import com.ap0stole.sheetsmith.domain.dto.chat.CellEditsRequest;
import com.ap0stole.sheetsmith.domain.dto.chat.CellEditsRequest.CellEdit;
import com.ap0stole.sheetsmith.domain.entity.DocumentSession;
import com.ap0stole.sheetsmith.domain.enums.ChatRole;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.services.SessionLockRegistry;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Manual grid edits used to live only in the browser and vanish on the next refresh. These tests
 * pin down that they now become a revision like anything else.
 */
/**
 * java:S2925: the sleep in here is the fixture — it is the window in which a reader outside
 * the lock can observe a half-written revision, which is the thing being measured.
 */
@SuppressWarnings("java:S2925")
class ManualEditServiceTest {

    private DocumentSessionService sessionService;
    private ManualEditService service;
    private DocumentSession session;
    private Path current;
    private Path committed;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        current = tempDir.resolve("rev-0.xlsx");
        committed = tempDir.resolve("rev-1.xlsx");
        writeWorkbook(current);

        session = DocumentSession.create("sales.xlsx", tempDir.toString());
        sessionService = mock(DocumentSessionService.class);

        when(sessionService.require(anyString())).thenReturn(session);
        when(sessionService.currentPath(session)).thenReturn(current);
        // Stand in for the real commit: write the edited workbook where rev-1 would go.
        when(sessionService.commitRevision(eq(session), any())).thenAnswer(call -> {
            XSSFWorkbook edited = call.getArgument(1);
            try (FileOutputStream out = new FileOutputStream(committed.toFile())) {
                edited.write(out);
            }
            return 1;
        });

        service = new ManualEditService(sessionService, new SessionLockRegistry());
    }

    @Test
    @DisplayName("a batch of edits lands as exactly one revision, and says so in the transcript")
    void commitsOneRevisionForTheWholeBatch() throws Exception {
        int revision = service.apply("s1", new CellEditsRequest(List.of(
                new CellEdit(0, 1, 0, "Widget B"),
                new CellEdit(0, 1, 1, "99")), null));

        assertThat(revision).isEqualTo(1);
        verify(sessionService, times(1)).commitRevision(eq(session), any());
        verify(sessionService).note(eq(session), eq(ChatRole.SYSTEM), contains("2 cells"), eq(1));

        try (XSSFWorkbook result = open(committed)) {
            assertThat(cell(result, 1, 0).getStringCellValue()).isEqualTo("Widget B");
            assertThat(cell(result, 1, 1).getNumericCellValue()).isEqualTo(99);
        }
    }

    @Test
    @DisplayName("numeric-looking text becomes a number, the rest stays text — as the grid does it")
    void matchesTheGridsTypeRules() throws Exception {
        service.apply("s1", new CellEditsRequest(List.of(
                new CellEdit(0, 1, 0, "1,240"),
                new CellEdit(0, 1, 1, "N/A")), null));

        try (XSSFWorkbook result = open(committed)) {
            assertThat(cell(result, 1, 0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(cell(result, 1, 0).getNumericCellValue()).isEqualTo(1240);
            assertThat(cell(result, 1, 1).getCellType()).isEqualTo(CellType.STRING);
        }
    }

    @Test
    @DisplayName("an edit replaces a formula rather than feeding it a value")
    void editingAFormulaCellDropsTheFormula() throws Exception {
        service.apply("s1", new CellEditsRequest(List.of(new CellEdit(0, 2, 1, "7")), null));

        try (XSSFWorkbook result = open(committed)) {
            assertThat(cell(result, 2, 1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(cell(result, 2, 1).getNumericCellValue()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("a blank value empties the cell")
    void blankClearsTheCell() throws Exception {
        service.apply("s1", new CellEditsRequest(List.of(new CellEdit(0, 1, 0, "   ")), null));

        try (XSSFWorkbook result = open(committed)) {
            assertThat(cell(result, 1, 0).getCellType()).isEqualTo(CellType.BLANK);
        }
    }

    @Test
    @DisplayName("a cell in a row that does not exist yet is created")
    void createsMissingRows() throws Exception {
        service.apply("s1", new CellEditsRequest(List.of(new CellEdit(0, 40, 0, "late")), null));

        try (XSSFWorkbook result = open(committed)) {
            assertThat(cell(result, 40, 0).getStringCellValue()).isEqualTo("late");
        }
    }

    @Test
    @DisplayName("a sheet rename applies")
    void renamesSheets() throws Exception {
        service.apply("s1", new CellEditsRequest(null, Map.of("0", "Q3")));

        try (XSSFWorkbook result = open(committed)) {
            assertThat(result.getSheetName(0)).isEqualTo("Q3");
        }
    }

    @Test
    @DisplayName("a duplicate or empty sheet name is refused rather than silently ignored")
    void rejectsBadSheetNames() {
        var cellEditsRequest = new CellEditsRequest(null, Map.of("1", "Sales"));
        assertThatThrownBy(() -> service.apply("s1", cellEditsRequest))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");

        var cellEditsRequest2 = new CellEditsRequest(null, Map.of("0", " "));
        assertThatThrownBy(() -> service.apply("s1", cellEditsRequest2))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be empty");
    }

    @Test
    @DisplayName("an unknown sheet index is refused with a message that says what is wrong")
    void rejectsUnknownSheetIndex() {
        var cellEditsRequest = new CellEditsRequest(List.of(new CellEdit(9, 0, 0, "x")), null);
        assertThatThrownBy(() -> service.apply("s1",cellEditsRequest))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no sheet 9");
    }

    @Test
    @DisplayName("an empty payload is refused — there is nothing to commit")
    void rejectsEmptyPayload() {
        var cellEditsRequest = new CellEditsRequest(List.of(), Map.of());
        assertThatThrownBy(() -> service.apply("s1", cellEditsRequest))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No edits");

        verifyNoInteractions(sessionService);
    }

    private XSSFWorkbook open(Path path) throws Exception {
        return new XSSFWorkbook(new FileInputStream(path.toFile()));
    }

    private Cell cell(XSSFWorkbook workbook, int row, int column) {
        return workbook.getSheetAt(0).getRow(row).getCell(column);
    }

    private void writeWorkbook(Path path) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(path.toFile())) {
            XSSFSheet sheet = workbook.createSheet("Sales");
            workbook.createSheet("Notes");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Product");
            header.createCell(1).setCellValue("Revenue");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Widget A");
            data.createCell(1).setCellValue(1240);
            Row total = sheet.createRow(2);
            total.createCell(0).setCellValue("Total");
            total.createCell(1).setCellFormula("SUM(B2:B2)");
            workbook.write(out);
        }
    }

    @Test
    @DisplayName("two flushes at once append two revisions — neither overwrites the other")
    void concurrentFlushesDoNotCollide() throws Exception {
        // Models the database: require() snapshots whatever is committed at the moment it is called,
        // which is what makes reading outside the lock observable.
        java.util.concurrent.atomic.AtomicInteger committedRevision = new java.util.concurrent.atomic.AtomicInteger(0);
        when(sessionService.require(anyString())).thenAnswer(call -> {
            DocumentSession snapshot = DocumentSession.create("sales.xlsx", session.getDirectory());
            snapshot.setCurrentRevision(committedRevision.get());
            return snapshot;
        });
        when(sessionService.currentPath(any())).thenReturn(current);
        when(sessionService.commitRevision(any(), any())).thenAnswer(call -> {
            DocumentSession s = call.getArgument(0);
            // The sleep is the subject, not a workaround: a big workbook takes seconds to write,
            // and this is the window in which a reader outside the lock can observe a half-done
            // revision. Awaiting a condition instead would remove the very thing under test.
            @SuppressWarnings("java:S2925")
            long window = 120;
            Thread.sleep(window);
            int next = s.getCurrentRevision() + 1;
            committedRevision.set(next);
            return next;
        });

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Integer> revisions = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Runnable flush = () -> {
            try {
                go.await();
                revisions.add(service.apply("s1", new CellEditsRequest(
                        List.of(new CellEdit(0, 1, 0, "x")), null)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        Thread a = new Thread(flush);
        Thread b = new Thread(flush);
        a.start();
        b.start();
        go.countDown();
        a.join();
        b.join();

        assertThat(revisions).containsExactlyInAnyOrder(1, 2);
        assertThat(committedRevision.get()).isEqualTo(2);
    }
}
