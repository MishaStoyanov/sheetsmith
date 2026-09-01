package com.ap0stole.sheetsmith.services.excel.actions.structure;

import com.ap0stole.sheetsmith.services.excel.actions.ActionDescriptions;
import com.ap0stole.sheetsmith.services.excel.model.structure.FilterConfig;
import com.ap0stole.sheetsmith.services.excel.ActionHandler;
import com.ap0stole.sheetsmith.services.excel.StepTense;
import com.ap0stole.sheetsmith.services.excel.SheetResolver;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.io.IOException;

@Slf4j
@Component
public class FilterDataHandler implements ActionHandler {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String getType() {
        return "FILTER_DATA";
    }

    @Override
    public String execute(XSSFWorkbook workbook, Map<String, Object> properties) throws IOException {
        FilterConfig cfg = mapper.convertValue(properties, FilterConfig.class);

        XSSFSheet sheet = SheetResolver.resolve(workbook, cfg.getSheetName(), cfg.getSheetIndex());

        CellRangeAddress range = CellRangeAddress.valueOf(cfg.getRange());
        sheet.setAutoFilter(range);
        log.info("AutoFilter set on range {}", cfg.getRange());

        return null;
    }

    @Override
    public String describe(Map<String, Object> properties, StepTense tense) {
        String range = ActionDescriptions.range(properties, "range");
        return ActionDescriptions.verb(tense, "Add", "Added") + " a filter"
                + (range == null ? "" : " to " + range)
                + ActionDescriptions.sheetSuffix(properties);
    }
}
