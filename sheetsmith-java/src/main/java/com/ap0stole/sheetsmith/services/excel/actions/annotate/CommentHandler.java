package com.ap0stole.sheetsmith.services.excel.actions.annotate;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.annotate.CommentConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.io.IOException;

/**
 * Pins a note to a cell — the little red corner that explains where a number came from, or what
 * still needs checking, without spending a column on it.
 * <p>
 * The note box is anchored a couple of columns wide and a few rows tall because a comment with no
 * size is a comment Excel draws as a sliver. It lives in the sheet's drawing part rather than in the
 * cell, which is why {@code ActionRoundTripTest} reopens the file to check it is really there.
 */
@Slf4j
@Component
public class CommentHandler implements ActionHandler {

    private static final int BOX_WIDTH_COLUMNS = 3;
    private static final int BOX_HEIGHT_ROWS = 4;

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String getType() {
        return "COMMENT";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        CommentConfig cfg = mapper.convertValue(properties, CommentConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());
        CellRangeAddress at = CellStyles.area(cfg.getCell(), "cell");
        int rowIndex = at.getFirstRow();
        int columnIndex = at.getFirstColumn();

        if (Boolean.TRUE.equals(cfg.getRemove())) {
            XSSFRow row = sheet.getRow(rowIndex);
            XSSFCell existing = row == null ? null : row.getCell(columnIndex);
            if (existing == null || existing.getCellComment() == null) {
                return "there was no note on " + reference(rowIndex, columnIndex) + " to remove";
            }
            existing.removeCellComment();
            log.info("COMMENT removed the note on {} on '{}'",
                    reference(rowIndex, columnIndex), sheet.getSheetName());
            return null;
        }

        if (cfg.getText() == null || cfg.getText().isBlank()) {
            throw new IllegalArgumentException("A note needs something to say — give \"text\","
                    + " or \"remove\": true to take an existing note off.");
        }

        XSSFCell cell = cell(sheet, rowIndex, columnIndex);
        if (cell.getCellComment() != null) {
            // Replacing beats stacking: two notes cannot share a cell, and the second write is
            // plainly meant to say what the note says now.
            cell.removeCellComment();
        }

        CreationHelper helper = workbook.getCreationHelper();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(columnIndex);
        anchor.setCol2(columnIndex + BOX_WIDTH_COLUMNS);
        anchor.setRow1(rowIndex);
        anchor.setRow2(rowIndex + BOX_HEIGHT_ROWS);

        Drawing<?> drawing = sheet.createDrawingPatriarch();
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(helper.createRichTextString(cfg.getText().trim()));
        if (cfg.getAuthor() != null && !cfg.getAuthor().isBlank()) {
            comment.setAuthor(cfg.getAuthor().trim());
        }
        cell.setCellComment(comment);

        log.info("COMMENT put a note on {} on '{}'",
                reference(rowIndex, columnIndex), sheet.getSheetName());
        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String cell = ActionDescriptions.range(properties, "cell");
        String where = cell == null ? "the cell" : cell;

        if (ActionDescriptions.flag(properties, "remove", false)) {
            return ActionDescriptions.verb(tense, "Remove", "Removed") + " the note on " + where
                    + ActionDescriptions.sheetSuffix(properties);
        }
        String text = ActionDescriptions.text(properties, "text");
        return ActionDescriptions.verb(tense, "Note", "Noted") + " on " + where
                + (text == null ? "" : ": " + ActionDescriptions.quoted(text))
                + ActionDescriptions.sheetSuffix(properties);
    }

    private XSSFCell cell(XSSFSheet sheet, int rowIndex, int columnIndex) {
        XSSFRow row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        XSSFCell cell = row.getCell(columnIndex);
        return cell == null ? row.createCell(columnIndex) : cell;
    }

    private String reference(int rowIndex, int columnIndex) {
        return ActionDescriptions.columnLetter(columnIndex) + (rowIndex + 1);
    }
}
