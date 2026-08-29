package com.ap0stole.sheetsmith.services.chat;

import com.ap0stole.sheetsmith.domain.dto.chat.CellEditsRequest;
import com.ap0stole.sheetsmith.domain.entity.DocumentSession;
import com.ap0stole.sheetsmith.domain.enums.ChatRole;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.services.DocumentSessionService;
import com.ap0stole.sheetsmith.services.SessionLockRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Commits edits the user typed into the preview grid.
 * <p>
 * They used to live only in the browser and were silently dropped the moment anything refreshed the
 * sheet. Going through the revision chain makes them undoable and visible to the chat and the
 * improve flow like any other change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualEditService {

    private final DocumentSessionService sessionService;
    private final SessionLockRegistry sessionLocks;

    public int apply(String sessionId, CellEditsRequest request) {
        if (request.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "No edits to save");
        }

        ReentrantLock lock = sessionLocks.acquire(sessionId);
        try {
            // The session is read INSIDE the lock on purpose. Reading first and locking second lets
            // two flushes queued behind a slow write both see the same current revision and derive
            // the same "next" one — the second then overwrites the first instead of appending.
            DocumentSession session = sessionService.require(sessionId);

            try (FileInputStream in = new FileInputStream(sessionService.currentPath(session).toFile());
                 XSSFWorkbook workbook = new XSSFWorkbook(in)) {

                renameSheets(workbook, request.safeRenames());
                for (CellEditsRequest.CellEdit edit : request.safeCells()) {
                    applyCell(workbook, edit);
                }

                int revision = sessionService.commitRevision(session, workbook);
                sessionService.record(session, ChatRole.SYSTEM, summarise(request), revision);
                log.info("Session {} took {} manual cell edit(s) as revision {}",
                        sessionId, request.safeCells().size(), revision);
                return revision;
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Manual edits failed for session {}", sessionId, e);
            throw new ApiException(ErrorCode.PROCESSING_ERROR, "Could not save the edits: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private void renameSheets(XSSFWorkbook workbook, Map<String, String> renames) {
        Set<String> taken = new LinkedHashSet<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            taken.add(workbook.getSheetName(i));
        }

        for (Map.Entry<String, String> rename : renames.entrySet()) {
            int index = sheetIndex(rename.getKey());
            requireSheet(workbook, index);

            String current = workbook.getSheetName(index);
            String target = rename.getValue() == null ? "" : rename.getValue().trim();
            if (target.equals(current)) continue;
            if (target.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "A sheet name cannot be empty", "sheetRenames");
            }
            if (taken.contains(target)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "A sheet named \"" + target + "\" already exists", "sheetRenames");
            }

            taken.remove(current);
            taken.add(target);
            workbook.setSheetName(index, target);
        }
    }

    private void applyCell(XSSFWorkbook workbook, CellEditsRequest.CellEdit edit) {
        requireSheet(workbook, edit.sheetIndex());
        XSSFSheet sheet = workbook.getSheetAt(edit.sheetIndex());

        Row row = sheet.getRow(edit.row());
        if (row == null) row = sheet.createRow(edit.row());
        Cell cell = row.getCell(edit.column());
        if (cell == null) cell = row.createCell(edit.column());

        // Blanking first drops any formula the cell held — the grid shows values, so an edit
        // replaces the formula rather than feeding it a cached result.
        cell.setBlank();

        String value = edit.value() == null ? "" : edit.value().trim();
        if (value.isEmpty()) return;

        try {
            cell.setCellValue(Double.parseDouble(value.replaceAll("[,$\\s]", "")));
        } catch (NumberFormatException _) {
            cell.setCellValue(value);
        }
    }

    private int sheetIndex(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException _) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Sheet index must be a number, got \"" + raw + "\"", "sheetRenames");
        }
    }

    private void requireSheet(XSSFWorkbook workbook, int index) {
        if (index < 0 || index >= workbook.getNumberOfSheets()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "The workbook has no sheet " + index + " (it has " + workbook.getNumberOfSheets() + ")");
        }
    }

    private String summarise(CellEditsRequest request) {
        int cells = request.safeCells().size();
        int renames = request.safeRenames().size();
        StringBuilder note = new StringBuilder("Edited by hand: ");
        if (cells > 0) note.append(cells).append(cells == 1 ? " cell" : " cells");
        if (cells > 0 && renames > 0) note.append(", ");
        if (renames > 0) note.append(renames).append(renames == 1 ? " sheet renamed" : " sheets renamed");
        return note.append('.').toString();
    }
}
