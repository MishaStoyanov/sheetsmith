package com.ap0stole.sheetsmith.services.excel.actions;

import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.CellStyles;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.model.ProtectSheetConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Stops a finished sheet being typed over by accident — the last step of a cleanup, after the
 * formulas are right and the formats are set.
 * <p>
 * The thing to know before using it is Excel's, and it catches everyone: <em>every</em> cell is
 * marked locked already, and that flag does nothing until the sheet is protected — at which point
 * the whole sheet freezes at once. "Protect the formulas but let people fill in the data" is
 * therefore two steps in one: unlock the cells that should stay editable, then protect. That is what
 * {@code unlockedRange} is, and unlocking goes through {@link CellStyles} so the cells keep every
 * other thing about their appearance.
 * <p>
 * The password is worth being honest about: sheet protection is a guard rail against a slip of the
 * hand, not a secret. Excel removes it on request and so does any of a dozen tools, so the step says
 * as much rather than letting a user believe otherwise.
 */
@Slf4j
@Component
public class ProtectSheetHandler implements ActionHandler {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String getType() {
        return "PROTECT_SHEET";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws Exception {
        ProtectSheetConfig cfg = mapper.convertValue(properties, ProtectSheetConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());

        if (Boolean.TRUE.equals(cfg.getUnprotect())) {
            if (!sheet.getProtect()) {
                return "the sheet was not protected, so there was nothing to unlock";
            }
            sheet.protectSheet(null);
            log.info("PROTECT_SHEET removed protection from '{}'", sheet.getSheetName());
            return null;
        }

        int unlocked = 0;
        if (cfg.getUnlockedRange() != null && !cfg.getUnlockedRange().isBlank()) {
            CellRangeAddress area = CellStyles.area(cfg.getUnlockedRange(), "unlockedRange");
            unlocked = CellStyles.apply(workbook, sheet, area, new CellStyles.StyleEdit() {
                @Override
                public String key(int row, int column) {
                    return "unlocked";
                }

                @Override
                public void apply(XSSFCellStyle style, int row, int column) {
                    style.setLocked(false);
                }
            });
        }

        sheet.protectSheet(cfg.getPassword() == null || cfg.getPassword().isBlank()
                ? "" : cfg.getPassword());

        log.info("PROTECT_SHEET protected '{}' with {} cell(s) left editable",
                sheet.getSheetName(), unlocked);

        String caveat = "Excel's protection stops accidents, not people — it can be removed without"
                + " the password";
        return unlocked == 0
                ? "every cell is now read-only. " + caveat
                : unlocked + (unlocked == 1 ? " cell stays" : " cells stay") + " editable. " + caveat;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        if (ActionDescriptions.flag(properties, "unprotect", false)) {
            return ActionDescriptions.verb(tense, "Unprotect", "Unprotected") + " the sheet"
                    + ActionDescriptions.sheetSuffix(properties);
        }
        String editable = ActionDescriptions.range(properties, "unlockedRange");
        boolean withPassword = ActionDescriptions.text(properties, "password") != null;

        return ActionDescriptions.verb(tense, "Protect", "Protected") + " the sheet from edits"
                + (editable == null ? "" : ", leaving " + editable + " editable")
                + (withPassword ? ", with a password" : "")
                + ActionDescriptions.sheetSuffix(properties);
    }
}
