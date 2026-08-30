package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.actions.annotate.CommentHandler;
import com.ap0stole.sheetsmith.services.excel.actions.annotate.HyperlinkHandler;
import com.ap0stole.sheetsmith.services.excel.actions.sheet.ProtectSheetHandler;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.FontUnderline;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three small decorations: a cell that can be clicked, a note pinned beside one, and a sheet
 * that refuses to be typed over.
 */
class HyperlinkCommentProtectTest {

    private XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private final HyperlinkHandler hyperlink = new HyperlinkHandler();
    private final CommentHandler comment = new CommentHandler();
    private final ProtectSheetHandler protect = new ProtectSheetHandler();

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet("Data");
        sheet.createRow(0).createCell(0).setCellValue("Site");
        sheet.createRow(1).createCell(0).setCellValue("https://example.com/one");
        sheet.createRow(2).createCell(0).setCellValue("www.example.com/two");
        sheet.createRow(3).createCell(0).setCellValue("");
    }

    @AfterEach
    void tearDown() throws IOException {
        workbook.close();
    }

    private Map<String, Object> props(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.put((String) pairs[i], pairs[i + 1]);
        }
        return properties;
    }

    private XSSFCell cell(int row, int column) {
        XSSFRow r = sheet.getRow(row);
        return r == null ? null : r.getCell(column);
    }

    // ── HYPERLINK ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("one cell links to the address it was given, and shows it")
    void linksOneCell() throws Exception {
        String detail = hyperlink.execute(workbook, props(
                "cell", "C1", "address", "https://example.com/report"));

        XSSFCell linked = cell(0, 2);
        assertThat(linked.getHyperlink()).isNotNull();
        assertThat(linked.getHyperlink().getType()).isEqualTo(HyperlinkType.URL);
        assertThat(linked.getHyperlink().getAddress()).isEqualTo("https://example.com/report");
        assertThat(linked.getStringCellValue())
                .as("a link on a cell showing nothing is a link nobody can click")
                .isEqualTo("https://example.com/report");
        assertThat(detail).isNull();
    }

    @Test
    @DisplayName("a caption is shown instead of the address when one is given")
    void showsTheCaption() throws Exception {
        hyperlink.execute(workbook, props(
                "cell", "C1", "address", "https://example.com/report", "text", "Q1 report"));

        assertThat(cell(0, 2).getStringCellValue()).isEqualTo("Q1 report");
        assertThat(cell(0, 2).getHyperlink().getAddress()).isEqualTo("https://example.com/report");
    }

    @Test
    @DisplayName("a link is blue and underlined, as Excel's own are")
    void looksLikeALink() throws Exception {
        hyperlink.execute(workbook, props("cell", "C1", "address", "https://example.com"));

        assertThat(cell(0, 2).getCellStyle().getFont().getUnderline())
                .isEqualTo(FontUnderline.SINGLE.getByteValue());
        assertThat(cell(0, 2).getCellStyle().getFont().getXSSFColor().getARGBHex())
                .isEqualTo("FF0563C1");
    }

    @Test
    @DisplayName("an email address is recognised and stored with its mailto: scheme")
    void infersAnEmail() throws Exception {
        hyperlink.execute(workbook, props("cell", "C1", "address", "sales@example.com"));

        assertThat(cell(0, 2).getHyperlink().getType()).isEqualTo(HyperlinkType.EMAIL);
        assertThat(cell(0, 2).getHyperlink().getAddress()).isEqualTo("mailto:sales@example.com");
    }

    @Test
    @DisplayName("a bare www address gets the scheme it needs to be clickable")
    void completesABareWebAddress() throws Exception {
        hyperlink.execute(workbook, props("cell", "C1", "address", "www.example.com"));

        assertThat(cell(0, 2).getHyperlink().getAddress()).isEqualTo("https://www.example.com");
    }

    @Test
    @DisplayName("a range on its own turns each cell's own text into a link")
    void linkifiesAColumn() throws Exception {
        String detail = hyperlink.execute(workbook, props("range", "A2:A4"));

        assertThat(cell(1, 0).getHyperlink().getAddress()).isEqualTo("https://example.com/one");
        assertThat(cell(2, 0).getHyperlink().getAddress())
                .as("the same completion applies down the column")
                .isEqualTo("https://www.example.com/two");
        assertThat(cell(3, 0).getHyperlink()).as("an empty cell has nothing to link to").isNull();
        assertThat(detail).isEqualTo("1 cell held no address and was left alone");
    }

    @Test
    @DisplayName("styling a column of links does not create the empty cells between them")
    void leavesUnlinkedCellsAlone() throws Exception {
        hyperlink.execute(workbook, props("range", "A2:A20"));

        assertThat(sheet.getLastRowNum())
                .as("the link style must not materialise every row of the range")
                .isEqualTo(3);
        assertThat(cell(3, 0).getCellStyle().getFont().getUnderline())
                .as("the empty cell inside the range keeps its plain style")
                .isEqualTo((byte) 0);
    }

    @Test
    @DisplayName("a range holding no addresses at all says so")
    void reportsARangeWithNothingToLink() throws Exception {
        String detail = hyperlink.execute(workbook, props("range", "B1:B4"));

        assertThat(detail).contains("nothing was linked");
    }

    @Test
    @DisplayName("a link with nowhere to go is refused with both shapes named")
    void refusesAMissingAddress() {
        var properties = props("cell", "C1");
        assertThatThrownBy(() -> hyperlink.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address");
    }

    @Test
    @DisplayName("an unknown link type names the ones that work")
    void refusesAnUnknownLinkType() {
        var properties = props( "cell", "C1", "address", "https://example.com", "linkType", "telepathy");
        assertThatThrownBy(() -> hyperlink.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    // ── COMMENT ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a note is pinned to the cell, with its author")
    void addsANote() throws Exception {
        String detail = comment.execute(workbook, props(
                "cell", "B2", "text", "Checked against the invoice", "author", "Misha"));

        assertThat(cell(1, 1).getCellComment()).isNotNull();
        assertThat(cell(1, 1).getCellComment().getString().getString())
                .isEqualTo("Checked against the invoice");
        assertThat(cell(1, 1).getCellComment().getAuthor()).isEqualTo("Misha");
        assertThat(detail).isNull();
    }

    @Test
    @DisplayName("writing a second note replaces the first rather than stacking on it")
    void replacesAnExistingNote() throws Exception {
        comment.execute(workbook, props("cell", "B2", "text", "First"));

        comment.execute(workbook, props("cell", "B2", "text", "Second"));

        assertThat(cell(1, 1).getCellComment().getString().getString()).isEqualTo("Second");
    }

    @Test
    @DisplayName("a note can be taken off again")
    void removesANote() throws Exception {
        comment.execute(workbook, props("cell", "B2", "text", "Temporary"));

        comment.execute(workbook, props("cell", "B2", "remove", true));

        assertThat(cell(1, 1).getCellComment()).isNull();
    }

    @Test
    @DisplayName("removing a note that was never there says so instead of failing")
    void reportsAMissingNote() throws Exception {
        String detail = comment.execute(workbook, props("cell", "B2", "remove", true));

        assertThat(detail).isEqualTo("there was no note on B2 to remove");
    }

    @Test
    @DisplayName("a note with nothing to say is refused")
    void refusesAnEmptyNote() {
        var properties = props("cell", "B2");
        assertThatThrownBy(() -> comment.execute(workbook, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    // ── PROTECT_SHEET ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("protecting freezes the whole sheet, and the step says so")
    void protectsEverything() throws Exception {
        String detail = protect.execute(workbook, props());

        assertThat(sheet.getProtect()).isTrue();
        assertThat(detail).contains("every cell is now read-only")
                .contains("can be removed without the password");
    }

    @Test
    @DisplayName("an unlocked range stays editable while the rest of the sheet does not")
    void leavesTheDataCellsEditable() throws Exception {
        String detail = protect.execute(workbook, props("unlockedRange", "A2:A3"));

        assertThat(sheet.getProtect()).isTrue();
        assertThat(cell(1, 0).getCellStyle().getLocked()).isFalse();
        assertThat(cell(2, 0).getCellStyle().getLocked()).isFalse();
        assertThat(cell(0, 0).getCellStyle().getLocked())
                .as("every cell is locked until told otherwise").isTrue();
        assertThat(detail).contains("2 cells stay editable");
    }

    @Test
    @DisplayName("protection comes off again on request")
    void unprotects() throws Exception {
        protect.execute(workbook, props("password", "secret"));

        protect.execute(workbook, props("unprotect", true));

        assertThat(sheet.getProtect()).isFalse();
    }

    @Test
    @DisplayName("unprotecting a sheet that was never protected says so")
    void reportsAnUnprotectedSheet() throws Exception {
        String detail = protect.execute(workbook, props("unprotect", true));

        assertThat(detail).contains("was not protected");
    }
}
