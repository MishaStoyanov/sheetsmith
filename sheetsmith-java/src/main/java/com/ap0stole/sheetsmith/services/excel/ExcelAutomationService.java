package com.ap0stole.sheetsmith.services.excel;

import com.ap0stole.sheetsmith.domain.entity.ActionResult;
import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.requests.ActionStep;
import com.ap0stole.sheetsmith.requests.AutomationRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ExcelAutomationService {

    private final ActionRegistry actionRegistry;

    public ExcelAutomationService(ActionRegistry actionRegistry) {
        this.actionRegistry = actionRegistry;
    }

    public List<ActionResult> applyChanges(String inputPath, String outputPath,
                                           AutomationRequest request, JobRecord job) {
        List<ActionResult> results = new ArrayList<>();

        if (request.getActions() == null || request.getActions().isEmpty()) {
            log.warn("No actions provided for job {}", job.getId());
            return results;
        }

        try (FileInputStream fis = new FileInputStream(inputPath);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            int order = 0;
            for (ActionStep action : request.getActions()) {
                results.add(processAction(workbook, action, job, order++));
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
                workbook.write(fos);
            }
            log.info("Excel saved to {}", outputPath);

        } catch (Exception e) {
            log.error("Critical error during Excel processing for job {}", job.getId(), e);
        }

        return results;
    }

    private ActionResult processAction(XSSFWorkbook workbook, ActionStep action, JobRecord job, int order) {
        if (action.getType() == null) {
            return ActionResult.failure(job, "UNKNOWN", order, "Action type is null");
        }

        String type = action.getType().toUpperCase();
        ActionHandler handler = actionRegistry.find(type);

        if (handler == null) {
            log.warn("Unknown action type: {}", type);
            return ActionResult.failure(job, type, order, "Unknown action type: " + type);
        }

        String description = actionRegistry.describe(type, action.getProperties());
        try {
            String detail = handler.execute(workbook, action.getProperties());
            log.info("Action {} executed successfully (order={})", type, order);
            return ActionResult.success(job, type, order, withDetail(description, detail));
        } catch (Exception e) {
            log.warn("Action {} failed (order={}): {}", type, order, e.getMessage());
            return ActionResult.failure(job, type, order, e.getMessage(), description);
        }
    }

    /** History reads as sentences, so a handler's detail joins the description rather than replacing it. */
    private String withDetail(String description, String detail) {
        return (detail == null || detail.isBlank()) ? description : description + " — " + detail;
    }
}
