package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.format.AlignCellsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.view.AutosizeColumnsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.format.ColorScaleHandler;
import com.ap0stole.sheetsmith.services.excel.actions.annotate.CommentHandler;
import com.ap0stole.sheetsmith.services.excel.actions.table.CreateTableHandler;
import com.ap0stole.sheetsmith.services.excel.actions.format.DataBarsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.table.DataValidationHandler;
import com.ap0stole.sheetsmith.services.excel.actions.structure.DeleteColumnsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.view.FreezePanesHandler;
import com.ap0stole.sheetsmith.services.excel.actions.formula.GroupByHandler;
import com.ap0stole.sheetsmith.services.excel.actions.view.GroupRowsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.annotate.HyperlinkHandler;
import com.ap0stole.sheetsmith.services.excel.actions.structure.InsertRowsHandler;
import com.ap0stole.sheetsmith.services.excel.actions.formula.LookupFromSheetHandler;
import com.ap0stole.sheetsmith.services.excel.actions.format.NumberFormatHandler;
import com.ap0stole.sheetsmith.services.excel.actions.view.PageSetupHandler;
import com.ap0stole.sheetsmith.services.excel.actions.sheet.ProtectSheetHandler;
import com.ap0stole.sheetsmith.services.excel.actions.format.SetBordersHandler;
import com.ap0stole.sheetsmith.services.excel.actions.cell.SetCellValueHandler;
import com.ap0stole.sheetsmith.services.excel.model.format.StyleConfig;
import com.ap0stole.sheetsmith.services.excel.actions.format.StyleHandler;
import com.ap0stole.sheetsmith.configs.ProcessingConfig;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.FontUnderline;
import org.apache.poi.ss.usermodel.ColorScaleFormatting;
import org.apache.poi.ss.usermodel.ConditionType;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.ConditionalFormattingThreshold.RangeType;
import org.apache.poi.ss.usermodel.DataBarFormatting;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableColumn;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What survives a save. Every assertion here runs against a workbook that has been written to a real
 * {@code .xlsx} and reopened, because the object graph and the file disagree in exactly the places
 * this batch touches: a pane is an XML element, a cloned style is a styles.xml entry, and a cell's
 * type is decided by how POI serialised it rather than by which setter was called.
 * <p>
 * Deliberately not a second copy of the per-handler tests — only the effects whose persistence is in
 * question are re-checked here.
 */
class ActionRoundTripTest {

    @TempDir
    Path tempDir;

    private final List<XSSFWorkbook> open = new ArrayList<>();
    private int saved;

    @AfterEach
    void tearDown() throws IOException {
        for (XSSFWorkbook workbook : open) {
            workbook.close();
        }
    }

    // ── FREEZE_PANES: the pane is an element in sheet XML ─────────────────────

    @Test
    @DisplayName("a freeze pane is still there, at the same split, after a save and reopen")
    void freezePaneSurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = workbookWithData();
        new FreezePanesHandler().execute(workbook, props("rows", 2, "columns", 1));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);

        PaneInformation pane = reopened.getPaneInformation();
        assertThat(pane).as("the <pane> element made it into the file").isNotNull();
        assertThat(pane.isFreezePane()).isTrue();
        assertThat(pane.getHorizontalSplitPosition()).as("rows").isEqualTo((short) 2);
        assertThat(pane.getVerticalSplitPosition()).as("columns").isEqualTo((short) 1);
    }

    @Test
    @DisplayName("unfreezing removes the pane from the file, not just from the object graph")
    void unfreezeSurvivesTheSave() throws Exception {
        // Round-tripped twice on purpose: the pane has to be really in a file before removing it
        // proves anything, since POI's unfreeze path unsets an element it has to find first.
        XSSFWorkbook first = workbookWithData();
        new FreezePanesHandler().execute(first, props("rows", 1, "columns", 1));
        XSSFWorkbook withPane = saveAndReopen(first);
        assertThat(withPane.getSheetAt(0).getPaneInformation()).isNotNull();

        new FreezePanesHandler().execute(withPane, props("rows", 0, "columns", 0));
        XSSFWorkbook withoutPane = saveAndReopen(withPane);

        assertThat(withoutPane.getSheetAt(0).getPaneInformation())
                .as("a pane left in the XML would come back on reopen")
                .isNull();
    }

    // ── SET_CELL_VALUE: a cloned style is a styles.xml entry ──────────────────

    @Test
    @DisplayName("a date keeps both its format and the cell's own fill through a save")
    void dateStyleSurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("Worksheet");
        CellStyle yellow = workbook.createCellStyle();
        yellow.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
        yellow.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        sheet.createRow(0).createCell(0).setCellStyle(yellow);

        new SetCellValueHandler().execute(workbook,
                props("cell", "A1", "value", "2026-01-31", "valueType", "date"));

        Cell reopened = saveAndReopen(workbook).getSheetAt(0).getRow(0).getCell(0);

        assertThat(DateUtil.isCellDateFormatted(reopened)).isTrue();
        assertThat(reopened.getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd");
        assertThat(reopened.getLocalDateTimeCellValue().toLocalDate()).hasToString("2026-01-31");
        assertThat(reopened.getCellStyle().getFillForegroundColor())
                .as("keeping the fill is the whole reason the style is cloned rather than replaced")
                .isEqualTo(IndexedColors.YELLOW.getIndex());
    }

    @Test
    @DisplayName("Excel's stored types agree with the round-trip rule: \"007\" is text, \"42\" is a number")
    void typeRulesSurviveTheSave() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        workbook.createSheet("Worksheet");
        SetCellValueHandler handler = new SetCellValueHandler();

        handler.execute(workbook, props("cell", "A1", "value", "007"));
        handler.execute(workbook, props("cell", "A2", "value", "42"));
        handler.execute(workbook, props("cell", "A3", "value", "1.50"));
        handler.execute(workbook, props("cell", "A4", "value", true));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);

        assertThat(cell(reopened, 0).getCellType()).isEqualTo(CellType.STRING);
        assertThat(cell(reopened, 0).getStringCellValue()).as("a part number keeps its zeros").isEqualTo("007");
        assertThat(cell(reopened, 1).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(cell(reopened, 1).getNumericCellValue()).isEqualTo(42.0);
        assertThat(cell(reopened, 2).getCellType()).isEqualTo(CellType.STRING);
        assertThat(cell(reopened, 2).getStringCellValue()).isEqualTo("1.50");
        assertThat(cell(reopened, 3).getCellType()).isEqualTo(CellType.BOOLEAN);
        assertThat(cell(reopened, 3).getBooleanCellValue()).isTrue();
    }

    @Test
    @DisplayName("a merged region still shows the value on its anchor and nothing in the cells it swallows")
    void mergedSkipSurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("Worksheet");
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        new SetCellValueHandler().execute(workbook, props("range", "A1:C1", "value", "Q1 2026"));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);

        assertThat(reopened.getNumMergedRegions()).isEqualTo(1);
        assertThat(reopened.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Q1 2026");
        for (int c = 1; c <= 2; c++) {
            Cell swallowed = reopened.getRow(0).getCell(c);
            assertThat(swallowed == null || swallowed.getCellType() == CellType.BLANK)
                    .as("column %d is inside the merge and was never written", c)
                    .isTrue();
        }
    }

    // ── AUTOSIZE_COLUMNS: a width is an attribute on <col> ────────────────────

    @Test
    @DisplayName("computed widths survive, and a capped column comes back at the ceiling")
    void columnWidthsSurviveTheSave() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("Worksheet");
        sheet.createRow(0).createCell(0).setCellValue("x".repeat(400));
        sheet.getRow(0).createCell(1).setCellValue("short");
        int unsized = sheet.getColumnWidth(1);

        String detail = new AutosizeColumnsHandler(new ProcessingConfig())
                .execute(workbook, props("range", "A:B", "maxWidth", 20));
        assertThat(detail).contains("capped at 20 characters");

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);

        assertThat(reopened.getColumnWidth(0))
                .as("the ceiling is what was written, not POI's measurement of 400 characters")
                .isEqualTo(20 * 256);
        assertThat(reopened.getColumnWidth(1))
                .as("a measured width has to persist too, or the action did nothing")
                .isNotEqualTo(unsized);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Writes the workbook to a real file and hands back what POI reads out of it again. */

    // ── The styling trio: a style is a styles.xml entry, not a field on a cell ─

    @Test
    @DisplayName("format, borders and alignment are all still on the same cell after a save")
    void theStylingActionsSurviveTogether() throws Exception {
        XSSFWorkbook workbook = workbookWithData();
        XSSFSheet sheet = workbook.getSheetAt(0);
        for (int r = 0; r < 5; r++) {
            sheet.getRow(r).getCell(1).setCellValue(1234.5678);
        }

        new NumberFormatHandler().execute(workbook, props("range", "B1:B5", "format", "currency"));
        new SetBordersHandler().execute(workbook, props("range", "B1:B5", "sides", "outline", "style", "medium"));
        new AlignCellsHandler().execute(workbook, props("range", "B1:B5", "horizontal", "right", "wrapText", true));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);
        CellStyle top = reopened.getRow(0).getCell(1).getCellStyle();

        assertThat(top.getDataFormatString()).isEqualTo("\"$\"#,##0.00");
        assertThat(top.getBorderTop()).isEqualTo(BorderStyle.MEDIUM);
        assertThat(top.getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
        assertThat(top.getWrapText()).isTrue();
        assertThat(reopened.getRow(0).getCell(1).getCellType())
                .as("a number format must not have turned the value into text")
                .isEqualTo(CellType.NUMERIC);
    }

    @Test
    @DisplayName("colouring after formatting keeps both, once the file has been through Excel's own schema")
    void colouringDoesNotStripTheFormatAcrossASave() throws Exception {
        XSSFWorkbook workbook = workbookWithData();
        new NumberFormatHandler().execute(workbook, props("range", "A1:A5", "format", "percent"));

        StyleConfig colours = new StyleConfig();
        colours.setRange("A1:A5");
        colours.setBackgroundColor("#FEF08A");
        new StyleHandler().execute(workbook, colours);

        CellStyle after = saveAndReopen(workbook).getSheetAt(0).getRow(0).getCell(0).getCellStyle();
        assertThat(after.getDataFormatString()).isEqualTo("0%");
        assertThat(after.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
    }

    @Test
    @DisplayName("a styled range of 500 cells leaves one style behind, not 500")
    void theStyleTableStaysSmallAcrossASave() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("Worksheet");
        for (int r = 0; r < 100; r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < 5; c++) {
                row.createCell(c).setCellValue(r * 100.0 + c);
            }
        }
        int before = workbook.getNumCellStyles();

        new NumberFormatHandler().execute(workbook, props("range", "A1:E100", "format", "thousands"));

        assertThat(saveAndReopen(workbook).getNumCellStyles() - before)
                .as("styles.xml is written in full, so a style per cell would be 500 entries in the file")
                .isEqualTo(1);
    }


    // ── The structural actions: a shift rewrites formula strings in the file ──

    @Test
    @DisplayName("rows moved by an insert are where the saved file says they are, formula included")
    void insertedRowsSurviveTheSave() throws Exception {
        XSSFWorkbook workbook = workbookWithData();
        XSSFSheet sheet = workbook.getSheetAt(0);
        sheet.getRow(0).getCell(0).setCellValue(10.0);
        sheet.createRow(6).createCell(0).setCellFormula("SUM(A1:A5)");

        new InsertRowsHandler().execute(workbook, props("at", 2, "count", 2));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);
        assertThat(reopened.getRow(0).getCell(0).getNumericCellValue()).isEqualTo(10.0);
        assertThat(reopened.getRow(3).getCell(0).getStringCellValue())
                .as("what was row 2 is row 4 in the file, not just in memory")
                .isEqualTo("r1c0");
        assertThat(reopened.getRow(8).getCell(0).getCellFormula()).isEqualTo("SUM(A1:A7)");
    }

    @Test
    @DisplayName("a deleted column is gone from the file and the letters after it have closed up")
    void deletedColumnsSurviveTheSave() throws Exception {
        XSSFWorkbook workbook = workbookWithData();

        new DeleteColumnsHandler().execute(workbook, props("at", "B"));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);
        assertThat(reopened.getRow(0).getCell(1).getStringCellValue())
                .as("what was C is B")
                .isEqualTo("r0c2");
        assertThat((Object) reopened.getRow(0).getCell(2)).isNull();
    }


    // ── Tables and validation are their own parts of the xlsx package ─────────

    @Test
    @DisplayName("a table is still a table, with its name and columns, after a save and reopen")
    void tablesSurviveTheSave() throws Exception {
        XSSFWorkbook workbook = workbookWithData();

        new CreateTableHandler().execute(workbook, props("range", "A1:C4", "name", "Sales"));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);
        assertThat(reopened.getTables()).hasSize(1);
        assertThat(reopened.getTables().getFirst().getName()).isEqualTo("Sales");
        assertThat(reopened.getTables().getFirst().getCTTable().getTableColumns().getTableColumnList())
                .extracting(CTTableColumn::getName)
                .as("a table whose columns are Column1, Column2 names nothing a formula can use")
                .containsExactly("r0c0", "r0c1", "r0c2");
    }

    @Test
    @DisplayName("a dropdown's options are in the saved file, not only in the object graph")
    void validationSurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = workbookWithData();

        new DataValidationHandler().execute(workbook, props(
                "range", "C2:C100", "type", "list", "values", "Open,Done"));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);
        assertThat(reopened.getDataValidations()).hasSize(1);
        assertThat(reopened.getDataValidations().getFirst()
                .getValidationConstraint().getExplicitListValues())
                .containsExactly("Open", "Done");
    }

    // ── COLOR_SCALE / DATA_BARS: rules live in their own part of the sheet XML ─

    @Test
    @DisplayName("a colour scale keeps its stops and its anchors after a save and reopen")
    void colorScaleSurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = numericWorkbook();

        new ColorScaleHandler().execute(workbook, props(
                "range", "A1:A5", "minColor", "#FFFFFF", "midColor", "#FEF08A", "maxColor", "#15803D"));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);
        SheetConditionalFormatting scf = reopened.getSheetConditionalFormatting();
        assertThat(scf.getNumConditionalFormattings()).isEqualTo(1);

        ConditionalFormattingRule rule = scf.getConditionalFormattingAt(0).getRule(0);
        assertThat(rule.getConditionType()).isEqualTo(ConditionType.COLOR_SCALE);

        ColorScaleFormatting scale = rule.getColorScaleFormatting();
        assertThat(scale.getNumControlPoints()).isEqualTo(3);
        assertThat(scale.getColors()).extracting(color -> ((XSSFColor) color).getARGBHex())
                .as("the colours are in the file, not only in the object graph")
                .containsExactly("FFFFFFFF", "FFFEF08A", "FF15803D");
        assertThat(scale.getThresholds()[0].getRangeType()).isEqualTo(RangeType.MIN);
        assertThat(scale.getThresholds()[2].getRangeType()).isEqualTo(RangeType.MAX);
    }

    @Test
    @DisplayName("data bars keep their colour and their hidden-number setting after a save and reopen")
    void dataBarsSurviveTheSave() throws Exception {
        XSSFWorkbook workbook = numericWorkbook();

        new DataBarsHandler().execute(workbook, props(
                "range", "A1:A5", "color", "#1E3A8A", "showValue", false));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);
        SheetConditionalFormatting scf = reopened.getSheetConditionalFormatting();
        assertThat(scf.getNumConditionalFormattings()).isEqualTo(1);
        assertThat(scf.getConditionalFormattingAt(0).getFormattingRanges()[0].formatAsString())
                .isEqualTo("A1:A5");

        ConditionalFormattingRule rule = scf.getConditionalFormattingAt(0).getRule(0);
        assertThat(rule.getConditionType()).isEqualTo(ConditionType.DATA_BAR);

        DataBarFormatting bar = rule.getDataBarFormatting();
        assertThat(((XSSFColor) bar.getColor()).getARGBHex()).isEqualTo("FF1E3A8A");
        assertThat(bar.isIconOnly()).as("\"show bar only\" is stored, not just set").isTrue();
        assertThat(bar.getMinThreshold().getRangeType()).isEqualTo(RangeType.MIN);
        assertThat(bar.getMaxThreshold().getRangeType()).isEqualTo(RangeType.MAX);
    }

    private XSSFWorkbook numericWorkbook() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("Worksheet");
        for (int r = 0; r < 5; r++) {
            sheet.createRow(r).createCell(0).setCellValue((r + 1) * 10.0);
        }
        return workbook;
    }

    // ── GROUP_ROWS / PAGE_SETUP: outline levels and workbook-level defined names ─

    @Test
    @DisplayName("an outline and its collapsed state survive a save and reopen")
    void groupingSurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = workbookWithData();

        new GroupRowsHandler().execute(workbook, props("range", "2:4", "collapsed", true));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);
        assertThat(reopened.getRow(1).getCTRow().getOutlineLevel()).isEqualTo((short) 1);
        assertThat(reopened.getRow(3).getCTRow().getOutlineLevel()).isEqualTo((short) 1);
        assertThat(reopened.getRow(1).getZeroHeight()).as("still folded away").isTrue();
    }

    @Test
    @DisplayName("print area and repeating rows survive, though neither is stored on the sheet")
    void pageSetupSurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = workbookWithData();

        new PageSetupHandler().execute(workbook, props(
                "orientation", "landscape", "fitToWidth", 1,
                "printArea", "A1:C4", "repeatHeaderRows", "1:1"));

        XSSFWorkbook reopenedBook = saveAndReopen(workbook);
        XSSFSheet reopened = reopenedBook.getSheetAt(0);

        assertThat(reopened.getPrintSetup().getLandscape()).isTrue();
        assertThat(reopened.getPrintSetup().getFitWidth()).isEqualTo((short) 1);
        assertThat(reopened.getFitToPage())
                .as("the flag Excel needs before it honours fitWidth at all")
                .isTrue();
        assertThat(reopenedBook.getPrintArea(0))
                .as("a defined name in the workbook, not a property of the sheet")
                .contains("A1", "C4");
        assertThat(reopened.getRepeatingRows()).isNotNull();
        assertThat(reopened.getRepeatingRows().getLastRow()).isZero();
    }

    // ── HYPERLINK / COMMENT / PROTECT_SHEET: three separate parts of the package ─

    @Test
    @DisplayName("a link is still clickable, and still styled as a link, after a save and reopen")
    void hyperlinkSurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = workbookWithData();

        new HyperlinkHandler().execute(workbook, props(
                "cell", "A1", "address", "https://example.com/report", "text", "Report"));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);
        Cell linked = cell(reopened, 0);
        assertThat(linked.getHyperlink()).as("the relationship is a part of its own in the package")
                .isNotNull();
        assertThat(linked.getHyperlink().getAddress()).isEqualTo("https://example.com/report");
        assertThat(linked.getStringCellValue()).isEqualTo("Report");
        assertThat(((XSSFCellStyle) linked.getCellStyle()).getFont().getUnderline())
                .isEqualTo(FontUnderline.SINGLE.getByteValue());
    }

    @Test
    @DisplayName("a note lives in the drawing part, and is still on its cell after a reopen")
    void commentSurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = workbookWithData();

        new CommentHandler().execute(workbook, props(
                "cell", "B2", "text", "Checked against the invoice", "author", "Misha"));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);
        Comment note = reopened.getRow(1).getCell(1).getCellComment();
        assertThat(note).isNotNull();
        assertThat(note.getString().getString()).isEqualTo("Checked against the invoice");
        assertThat(note.getAuthor()).isEqualTo("Misha");
    }

    @Test
    @DisplayName("protection and the cells left editable both survive a save and reopen")
    void protectionSurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = workbookWithData();

        new ProtectSheetHandler().execute(workbook, props("unlockedRange", "A2:A3"));

        XSSFSheet reopened = saveAndReopen(workbook).getSheetAt(0);
        assertThat(reopened.getProtect()).isTrue();
        assertThat(reopened.getRow(1).getCell(0).getCellStyle().getLocked())
                .as("the unlocked flag is a style facet, and styles are shared records")
                .isFalse();
        assertThat(reopened.getRow(0).getCell(0).getCellStyle().getLocked()).isTrue();
    }

    // ── LOOKUP_FROM_SHEET: a cross-sheet reference has to survive as a reference ─

    @Test
    @DisplayName("a lookup still resolves against the other sheet after a save and reopen")
    void lookupSurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet orders = workbook.createSheet("Orders");
        orders.createRow(0).createCell(0).setCellValue("A-1");
        XSSFSheet products = workbook.createSheet("Price list");
        XSSFRow priceRow = products.createRow(0);
        priceRow.createCell(0).setCellValue("A-1");
        priceRow.createCell(1).setCellValue(10.5);

        new LookupFromSheetHandler().execute(workbook, props(
                "range", "B1:B1", "keyRange", "A1:A1",
                "sourceRange", "'Price list'!A1:B1", "sourceColumn", "2"));

        XSSFWorkbook reopenedBook = saveAndReopen(workbook);
        XSSFSheet reopened = reopenedBook.getSheet("Orders");
        assertThat(reopened.getRow(0).getCell(1).getCellFormula())
                .as("the quoted sheet name has to come back as a reference, not as text")
                .contains("'Price list'!$A$1:$B$1");
        assertThat(reopenedBook.getCreationHelper().createFormulaEvaluator()
                .evaluate(reopened.getRow(0).getCell(1)).getNumberValue()).isEqualTo(10.5);
    }

    // ── GROUP_BY: the claim that makes it not a pivot table ──────────────────

    @Test
    @DisplayName("the summary is real cells in the reopened file, which a pivot table would not be")
    void groupBySurvivesTheSave() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet data = workbook.createSheet("Data");
        XSSFRow header = data.createRow(0);
        header.createCell(0).setCellValue("Region");
        header.createCell(1).setCellValue("Amount");
        String[] regions = {"North", "South", "North"};
        double[] amounts = {10, 20, 5};
        for (int i = 0; i < regions.length; i++) {
            XSSFRow row = data.createRow(i + 1);
            row.createCell(0).setCellValue(regions[i]);
            row.createCell(1).setCellValue(amounts[i]);
        }

        new GroupByHandler().execute(workbook, props(
                "range", "A1:B4", "groupBy", "A", "valueColumn", "B", "targetSheet", "Summary"));

        XSSFWorkbook reopenedBook = saveAndReopen(workbook);
        XSSFSheet summary = reopenedBook.getSheet("Summary");
        assertThat(summary).isNotNull();
        assertThat(summary.getRow(1).getCell(0).getStringCellValue())
                .as("a pivot table leaves this sheet empty until Excel refreshes it")
                .isEqualTo("North");
        assertThat(reopenedBook.getCreationHelper().createFormulaEvaluator()
                .evaluate(summary.getRow(1).getCell(1)).getNumberValue()).isEqualTo(15.0);
    }

    private XSSFWorkbook saveAndReopen(XSSFWorkbook workbook) throws Exception {
        File file = tempDir.resolve("book-" + saved++ + ".xlsx").toFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            workbook.write(out);
        }
        workbook.close();

        XSSFWorkbook reopened = new XSSFWorkbook(file);
        open.add(reopened);
        return reopened;
    }

    private XSSFWorkbook workbookWithData() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("Worksheet");
        for (int r = 0; r < 5; r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < 3; c++) {
                row.createCell(c).setCellValue("r" + r + "c" + c);
            }
        }
        return workbook;
    }

    private Cell cell(XSSFSheet sheet, int rowIdx) {
        return sheet.getRow(rowIdx).getCell(0);
    }

    private static Map<String, Object> props(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
