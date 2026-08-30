package com.ap0stole.sheetsmith.domain.dto;


import java.util.List;

/**
 * What the frontend needs to open a chat: the session handle, which revision of the working
 * copy is current, and the sheet names so the panel can show what it is talking about.
 * <p>
 * {@code charts} rides along because the browser parses the workbook with SheetJS, which cannot
 * see embedded charts at all. It is read from the current revision, so it follows an improve run,
 * a chat turn and an undo through the same refetch the frontend already does.
 *
 * @param sessionId the handle every other call in this group takes
 * @param filename  the name the workbook was uploaded under
 * @param revision  which revision of the working copy is current. Every change commits the next
 *                  one, and reverting moves this back
 * @param sheets    the sheet names, in workbook order
 * @param charts    charts embedded in the current revision, which the browser's own parser cannot
 *                  see
 */
public record DocumentSessionDto(
        String sessionId,
        String filename,
        int revision,
        List<String> sheets,
        List<ChartDefinitionDto> charts
) {
}
