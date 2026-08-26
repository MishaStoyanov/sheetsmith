package com.ap0stole.sheetsmith.llm;

/**
 * The prompt-facing description of every mutating action the engine can execute.
 * <p>
 * Shared by the one-shot planner ({@link AiPlanningService}) and the chat agent, so a newly
 * added action becomes available to both by editing this file only.
 */
public final class ActionCatalog {

    public static final String SHEET_TARGETING = """
            SHEET TARGETING (applies to all actions except ADD_SHEET):
            - "sheetName": target sheet by exact name (e.g. "Sales"). Takes priority over sheetIndex.
            - "sheetIndex": target sheet by 0-based index (e.g. 0 = first sheet, 1 = second sheet).
            - Omit both to target the first sheet (index 0).
            - The table context below lists all available sheets with their names and indices.
            """;

    public static final String MUTATING_ACTIONS = """
            1. FORMAT_CELLS
               Keys: "range" (e.g. "A1:E1"), "backgroundColor" (hex), "fontColor" (hex), "bold" (boolean)
               Optional: "fontSize" (points), "sheetName", "sheetIndex"
               Colours and font weight only. For how NUMBERS read use NUMBER_FORMAT, for lines use
               SET_BORDERS, for where a value sits use ALIGN_CELLS — each keeps what the others did,
               so a header can be coloured, bordered and centred by three steps in any order.
               Name at least one of the four keys; a step that changes nothing is an error.

            2. CREATE_CHART
               Keys: "sourceRange", "chartType" ("barChart" or "pieChart"), "title", "chartWidth" (6-15), "chartHeight" (12-14)
               Optional: "sheetName" (source sheet name), "sourceSheetIndex" (0-based), "targetSheet" (output sheet name)
               Rules: chartWidth 6-8 for small data, 10-12 medium, 15+ large; chartHeight always 12-14

            3. ADD_SHEET
               Keys: "name" (new sheet name)

            4. ADD_FORMULA
               Keys: "cell" (e.g. "B10"), "formula" (e.g. "SUM(B2:B9)"), "label" (short descriptive text placed in the cell to the left, e.g. "Total", "Average", "Max")
               ALWAYS include "label" so users understand what the formula computes.
               Optional: "sheetName", "sheetIndex"

            5. SORT_DATA
               Keys: "range" (e.g. "A2:D20"), "columnIndex" (0-based int), "ascending" (boolean, default true)
               Optional: "sheetName", "sheetIndex"

            6. FILTER_DATA
               Keys: "range" (e.g. "A1:D1" — header row range for autofilter)
               Optional: "sheetName", "sheetIndex"

            7. CONDITIONAL_FORMATTING
               Keys: "range" (e.g. "C2:C20"), "operator" (">", ">=", "<", "<=", "=", "!="), "value" (string), "backgroundColor" (hex)
               Optional: "sheetName", "sheetIndex", "fontColor" (hex), "bold" (boolean)

            8. MERGE_CELLS
               Keys: "range" (e.g. "A1:C1")
               Optional: "sheetName", "sheetIndex"

            9. CLEAR_CELLS
               Keys: "range" (e.g. "B12:B12") — wipes value, formula, and formatting from every cell in range
               Optional: "sheetName", "sheetIndex"
               The table context below may list "Existing formulas" already present in the sheet from earlier
               edits. If the user's instruction implies moving, replacing, undoing, or removing something that
               may already exist (e.g. "move the total down", "put the total in a different cell", "remove that
               formula", "change the header color"), first emit a CLEAR_CELLS step for the OLD location (found in
               "Existing formulas"), THEN emit the new action. Never leave both the old and new copies in place.

            10. RENAME_SHEET
                Keys: "newName" (the sheet's new name)
                Optional: "sheetName", "sheetIndex" (identifies which sheet to rename)
                Use when the user asks to rename a sheet/tab.

            11. RENAME_COLUMN
                Keys: "cell" (the header cell to rename, e.g. "B1"), "newName" (new column header text)
                Optional: "sheetName", "sheetIndex"
                Compute "cell" from the header range and the column's position (e.g. if Header range is A1:C1
                and you're renaming the 2nd column, use cell "B1"). Use when the user asks to rename a column.

            12. RENAME_CHART_TITLE
                Keys: "newTitle" (the chart's new title)
                Optional: "sheetName", "sheetIndex" (the sheet the chart lives on), "chartIndex" (0-based, default 0)
                Target the sheet/chartIndex from the "Existing charts" list below. Use when the user asks to
                rename/retitle a chart.

            13. RENAME_CHART_AXIS
                Keys: "axis" ("category" for the x-axis, "value" for the y-axis), "newTitle" (the axis label)
                Optional: "sheetName", "sheetIndex", "chartIndex" (default 0)
                Only charts listed with "has category axis" / "has value axis" in "Existing charts" below support
                this — pie charts have neither and this action will fail on them. Use when the user asks to
                label/rename a chart's axis (e.g. "label the y-axis as Revenue").

            14. SET_CELL_VALUE
                Keys: "cell" (e.g. "A1"), "value" (the literal to write, e.g. "Q1 2026" or 1250)
                Optional: "range" (e.g. "A1:C1" — the same value into every cell), "valueType"
                ("text" | "number" | "boolean" | "date"), "sheetName", "sheetIndex"
                The only action that can put a value into an EMPTY cell. One literal per step, and the
                cell's existing formatting is kept. TRANSFORM_COLUMN rewrites a whole column's existing
                values; FORMAT_CELLS changes only how cells look.
                A quoted number becomes a number only when it renders back identically, so "007", "1.50"
                and "42.0" stay TEXT — send a JSON number (42, not "42") when you mean a number.
                Dates need "valueType": "date" and ISO form ("2026-01-31" or "2026-01-31T14:30:00").
                Writing into a cell that holds a FORMULA removes that formula — the step reports it, but
                emit CLEAR_CELLS or pick another cell if the calculation should stay.

            15. AUTOSIZE_COLUMNS
                Keys: none required — omit "range" to size every column in the sheet
                Optional: "range" (COLUMNS, e.g. "A:D"), "maxWidth" (characters, default 60),
                "sheetName", "sheetIndex"
                Widens columns to fit their contents. "range" must name COLUMNS ("A:D", or "A1:D1" whose
                rows are ignored); naming rows ("1:1") is an error. Use it after writing or reformatting
                data that no longer fits.
                Measuring is bounded, so on a very tall sheet the step sizes as many columns as the
                budget allows and tells you how many it left — repeat it for the rest if you need them.

            16. FREEZE_PANES
                Keys: none required — with neither key below, the header row is frozen
                Optional: "rows" (how many rows to freeze from the top, default 1), "columns" (how many
                columns from the left, default 0), "sheetName", "sheetIndex"
                COUNTS, not indices: if the header is row 3, "rows": 3 keeps rows 1-3 in view.
                "rows": 0 with "columns": 0 unfreezes.

            17. NUMBER_FORMAT
                Keys: "range" (e.g. "C2:C500"), "format" — a NAME: "currency", "percent", "thousands",
                "integer", "date", "datetime", "time", "scientific", "text", "general" — or a literal
                Excel pattern like "#,##0.00"
                Optional: "decimals" (0-10), "currencySymbol" (default "$"), "sheetName", "sheetIndex"
                Changes how numbers READ without changing the numbers: 1234.5 shown as $1,234.50 is
                still a number the sheet can add up. Prefer a name over a pattern.
                It cannot help a value stored as TEXT — no number format applies to a string — so the
                step counts those and says so; run TRANSFORM_COLUMN with "TO_NUMBER" first.

            18. SET_BORDERS
                Keys: "range" (e.g. "A1:D20")
                Optional: "sides" (comma-separated: "all" | "outline" | "inside" | "top" | "bottom" |
                "left" | "right", default "all"), "style" ("thin" | "medium" | "thick" | "double" |
                "dashed" | "dotted" | "none", default "thin"), "color" (hex), "sheetName", "sheetIndex"
                "sides" names sides of the RANGE, not of every cell: over a five-row block "bottom" is
                one line under the block, and "all" is the only value meaning every cell. They combine,
                so "outline,bottom" boxes a header and rules it off in one step.
                "style": "none" removes borders; a line drawn by a cell just outside the range can
                remain, and the step says so.

            19. ALIGN_CELLS
                Keys: "range" (e.g. "A1:E1"), and at least one of the four below
                Optional: "horizontal" ("left" | "center" | "right" | "justify" | "general"),
                "vertical" ("top" | "middle" | "bottom"), "wrapText" (boolean), "indent" (0-15),
                "sheetName", "sheetIndex"
                Where a value sits in its cell. "wrapText": true also returns any fixed row height in
                the range to automatic, or the wrapped text would be clipped rather than shown.
                Naming none of the four is an error — there would be nothing to do.

            20. INSERT_ROWS
                Keys: "at" (row NUMBER as Excel shows it, counting from 1 — the new rows appear here
                and everything from this row down moves down)
                Optional: "count" (default 1, max 1000), "sheetName", "sheetIndex"
                The new rows arrive EMPTY and unstyled. Formulas that pointed at the moved cells are
                rewritten, on this sheet and on every other sheet, so a total keeps totalling.
                Inserting below the last row with anything in it does nothing — that space is free
                already.

            21. DELETE_ROWS
                Keys: "at" (row number, counting from 1) — or "range" ("5:8")
                Optional: "count" (with "at", default 1), "sheetName", "sheetIndex"
                Removes the rows and closes the gap; rows below move up. Asking past the end of the
                sheet deletes what is there and reports how much.
                A formula pointing INTO the deleted rows becomes an error — the step names those
                cells, so read what it reports back and repair or re-add them.

            22. INSERT_COLUMNS
                Keys: "at" (column LETTER, e.g. "C" — the new column appears here and everything
                right of it moves right)
                Optional: "count" (default 1, max 1000), "sheetName", "sheetIndex"
                Empty and unstyled, like INSERT_ROWS, and formulas follow the same way. Column WIDTH
                belongs to the position rather than to the data, so run AUTOSIZE_COLUMNS afterwards
                once there is something in the new column.

            23. DELETE_COLUMNS
                Keys: "at" (column letter) — or "range" ("C:E")
                Optional: "count" (with "at", default 1), "sheetName", "sheetIndex"
                Removes the columns and closes the gap; the letters after them shift back.
                Same formula warning as DELETE_ROWS.
                A CHART over deleted or moved cells is NOT repointed — its ranges live outside the
                formula table. Redraw it with CREATE_CHART if the data it plotted has moved.

            24. FILL_FORMULA
                Keys: "range" (the WHOLE range to fill, e.g. "D2:D500")
                Optional: "formula" (e.g. "B2*C2" — omit it when the range's top cell already holds
                the formula), "sheetName", "sheetIndex"
                Excel's fill handle: the top cell of the range is the source, and every cell below
                gets the same formula with its RELATIVE references moved — "B2*C2" becomes "B3*C3"
                in row 3, while "$F$1" stays "$F$1". This is the action for "compute X for every
                row"; ADD_FORMULA writes ONE cell and is what a single total needs.
                A one-row range fills sideways instead. A one-cell range is an error.

            25. ADD_TOTALS_ROW
                Keys: "range" (the data rows to total, e.g. "A2:D40")
                Optional: "function" ("sum" default | "average" | "count" | "min" | "max"),
                "label" (default "Total"), "sheetName", "sheetIndex"
                Writes the whole totals row in one step, directly under the range, in bold.
                Only columns that actually hold NUMBERS get a total — a SUM over names or dates is a
                confident wrong answer — and the step names the columns it skipped. The label goes in
                the first column unless that column is itself being totalled.

            26. REMOVE_DUPLICATES
                Keys: "range" (e.g. "A1:D500")
                Optional: "columns" (comma-separated letters, e.g. "A,C" — which columns make a row
                a duplicate; default all of them), "hasHeader" (default true), "sheetName",
                "sheetIndex"
                Keeps the FIRST of each repeated row and removes the others, then closes the gap.
                Whole rows go, so nothing is left misaligned. Comparison is on the DISPLAYED value:
                a formula by its result, text case-insensitively ("ACME" = "Acme").
                It reports how many went and how many remain, and finding none is a normal result.

            27. DELETE_SHEET
                Keys: "name" (the sheet to remove — required; this action never falls back to the
                first sheet the way the others do)
                Optional: "sheetIndex" (instead of "name")
                The counterpart to ADD_SHEET. A workbook must keep at least one sheet, so deleting
                the last one is refused.
                Formulas on OTHER sheets that read from it are named in the result and will show
                #REF! in Excel — the values are gone, so re-point or re-add them. A chart drawn from
                its data is not repaired.

            28. UNMERGE_CELLS
                Keys: none required — omit "range" to split every merged block on the sheet
                Optional: "range" (splits every merge it TOUCHES, so "A1:C1" or even "A:A" works),
                "sheetName", "sheetIndex"
                The counterpart to MERGE_CELLS, and the repair for a sheet that arrived merged.
                Worth running first when a merge is in the way: SET_CELL_VALUE cannot write into the
                cells a merge swallows, and AUTOSIZE_COLUMNS ignores merged regions when measuring.
                Each value stays in its region's top-left cell, where it already was.

            29. DATA_VALIDATION
                Keys: "range" (e.g. "C2:C500"), "type" ("list" default | "whole" | "decimal" |
                "date" | "textLength")
                For a list: "values" (comma-separated, e.g. "Open,In progress,Done") or
                "sourceRange" (cells holding the options)
                For the rest: "operator" ("between" default | "greaterThan" | "lessThan" |
                "greaterOrEqual" | "lessOrEqual" | "equal"), "min" and "max" (or "value" for a
                one-sided rule)
                Optional: "allowBlank" (default true), "strict" (default true — false warns instead
                of refusing), "errorTitle", "errorMessage", "sheetName", "sheetIndex"
                Constrains what may be TYPED in future; it does not check or fix what is already
                there, and the step says so. Use it after cleaning a column so the cleanup lasts.
                An explicit "values" list is capped by Excel at 255 characters in total — a longer
                one has to live in cells and be named with "sourceRange".

            30. CREATE_TABLE
                Keys: "range" — INCLUDING the header row, e.g. "A1:D40"
                Optional: "name" (e.g. "Sales"), "style" (default "TableStyleMedium2"), "sheetName",
                "sheetIndex"
                Makes a real Excel table: banded rows, filter arrows, and a name so a formula can say
                Sales[Amount] instead of B2:B40. It also grows as rows are added, which FORMAT_CELLS
                and SET_BORDERS only imitate.
                Excel needs every column headed and no two headings the same; blanks are filled and
                repeats numbered rather than writing a file Excel offers to repair, and the step says
                what it changed. Two tables may not overlap. A range of only a header row is an error.

            31. COLOR_SCALE
                Keys: "range" (e.g. "C2:C500")
                Optional: "minColor", "midColor", "maxColor" (hex), "sheetName", "sheetIndex"
                Shades every cell by where its value sits between the range's own smallest and
                largest — the way to show WHERE the big numbers are without knowing a threshold.
                Give no colours at all for red through yellow to green. Naming colours means naming
                both "minColor" and "maxColor"; add "midColor" for a three-colour scale, leave it
                out for two. Excel paints only numbers, and the step reports cells holding text.
                Use CONDITIONAL_FORMATTING instead when there IS a threshold ("over 100 in red").

            32. DATA_BARS
                Keys: "range" (e.g. "C2:C500")
                Optional: "color" (hex, default sky blue), "showValue" (default true — false hides
                the number and leaves the bar), "sheetName", "sheetIndex"
                Draws a bar inside each cell in proportion to its value, measured against the
                range's own smallest and largest. Comparing 500 rows at a glance without leaving
                the table; CREATE_CHART draws the picture beside the table, this one draws it in.

            33. GROUP_ROWS
                Keys: "range" (rows, e.g. "5:20") or "at" (row number from 1) + "count"
                Optional: "collapsed" (default false — true folds them away), "ungroup" (true takes
                an existing grouping off the same rows), "summaryBelow" (default true), "sheetName",
                "sheetIndex"
                Puts a +/- outline button beside a block of rows so detail can be folded under the
                total that summarises it. Excel expects that total BELOW the detail; if the totals
                sit on top, pass "summaryBelow": false — it applies to the whole sheet.
                A span past the last row is trimmed to what the sheet holds and the step says so.

            34. PAGE_SETUP
                Keys: at least one of "orientation" ("portrait"|"landscape"), "fitToWidth" (pages
                across), "fitToHeight" (pages down, 0 = as many as it takes), "printArea"
                (e.g. "A1:D40"), "repeatHeaderRows" (e.g. "1:1"), "repeatHeaderColumns" (e.g. "A:A"),
                "paperSize" ("A3"|"A4"|"A5"|"letter"|"legal"), "printGridlines" (boolean)
                Optional: "sheetName", "sheetIndex"
                How the sheet prints or exports to PDF. "Make it fit on one page wide" is
                "fitToWidth": 1 and nothing else — the height is then free, which is almost always
                what is meant. Use "repeatHeaderRows" so a long table keeps its headings on page two.

            35. HYPERLINK
                Keys: "cell" (e.g. "C1") + "address" (e.g. "https://example.com"), OR "range" alone
                (e.g. "A2:A500") to turn addresses ALREADY in those cells into links
                Optional: "text" (what the cell shows instead of the address), "linkType"
                ("url"|"email"|"file"|"sheet" — guessed from the address when left out), "sheetName",
                "sheetIndex"
                Makes cells clickable, blue and underlined the way Excel's own links are. Use the
                "range" form for a whole column of addresses rather than one step per row.

            36. COMMENT
                Keys: "cell" (e.g. "B2"), "text" (what the note says)
                Optional: "author", "remove" (true takes an existing note off), "sheetName",
                "sheetIndex"
                Pins a note to a cell — where a number came from, what still needs checking —
                without spending a column on it. Writing a second note on the same cell replaces it.

            37. PROTECT_SHEET
                Keys: none required
                Optional: "unlockedRange" (e.g. "B2:D100" — the cells that stay editable),
                "password", "unprotect" (true removes protection), "sheetName", "sheetIndex"
                Stops a finished sheet being typed over. EVERY cell is locked already and that only
                bites once the sheet is protected, so protecting on its own freezes the whole sheet:
                to guard the formulas but keep the data fillable, name those data cells in
                "unlockedRange". The password stops accidents, not people, and the step says so.

            38. LOOKUP_FROM_SHEET
                Keys: "range" (ONE column to fill, e.g. "D2:D500"), "keyRange" (the column of keys to
                match, SAME rows, e.g. "A2:A500"), "sourceRange" (the table to look in, e.g.
                "Products!A2:C100" — its FIRST column is the one matched), "sourceColumn" (which
                column to bring back: a letter like "C" or a position like 3)
                Optional: "sourceSheet" (instead of a prefix on "sourceRange"), "ifMissing" (what an
                unmatched row shows — blank by default, or "#N/A" to leave the error), "sheetName",
                "sheetIndex"
                Brings a column across from another sheet — a price onto each order line, a name onto
                each id. One column per step. The match is always exact. Keys with no match are
                counted and reported back to you.

            39. GROUP_BY
                Keys: "range" (the data INCLUDING its header row, e.g. "A1:C500"), "groupBy" (the
                column to group on: a letter like "A" or a position like 1), "valueColumn" (the
                numbers to aggregate — not needed for a count)
                Optional: "function" ("sum" default | "average" | "count"), "targetSheet" (created if
                it does not exist), "targetCell" (default A1 there, or two columns clear of the data),
                "hasHeader" (default true), "sheetName", "sheetIndex"
                One row per distinct value of the grouping column, with the numbers beside it added
                up — "how much per region". Writes REAL cells and formulas, so the result is visible
                and can be formatted or sorted afterwards. Min and max are not available here.

            40. SPARKLINES
                Keys: "range" (the cells the little charts go in — ONE column or ONE row, e.g.
                "F2:F13"), "dataRange" (the numbers they are drawn from — one row per sparkline cell
                going down a column, e.g. "B2:E13")
                Optional: "type" ("line" default | "column" | "winLoss"), "color" (hex),
                "showMarkers" (line only), "sheetName", "sheetIndex"
                A whole chart inside a single cell, for the shape of a row at a glance. Use it beside
                a table of monthly figures where CREATE_CHART would need its own space.
            """;

    /**
     * The last entry, whose rule list is filled in from the live {@code ColumnTransformRegistry} — a
     * new transform bean documents itself here without this file changing. Rendered by
     * {@link ActionCatalogPrompt}, which is what callers should inject.
     * <p>
     * It is numbered by hand and appended after {@link #MUTATING_ACTIONS}, so its number has to be
     * one past that constant's last entry: add an action there and this number moves too.
     */
    public static final String TRANSFORM_COLUMN_TEMPLATE = """
            41. TRANSFORM_COLUMN
                Keys: "range" (ONE column, data rows only, e.g. "C2:C500"), "operation" (one of the rules below)
                Optional: "targetRange" (a single column of the same height — write the results there instead
                of overwriting the source), "sheetName", "sheetIndex", plus whatever keys the chosen rule takes
                Rewrites every value in the column by the named rule. Use it whenever the user asks to clean,
                normalise, reformat or standardise the CONTENTS of a column — FORMAT_CELLS only changes how
                cells look and will not touch a single value.
                One column per step: to do two columns, emit two steps.
                Values the rule cannot convert are left exactly as they were and reported back to you.
                RULES:
            %s
            """;

    /**
     * One line per action, enough to choose one and name its keys — but not the detailed rules.
     * The chat opens every turn with this, and only pays for {@link #MUTATING_ACTIONS} once the
     * model actually reaches for an action, because most chat turns are questions.
     */
    public static final String MUTATING_ACTIONS_INDEX_LIST = """
            FORMAT_CELLS — range, backgroundColor, fontColor, bold
            CREATE_CHART — sourceRange, chartType ("barChart"|"pieChart"), title, chartWidth, chartHeight, targetSheet
            ADD_SHEET — name
            ADD_FORMULA — cell, formula, label
            SORT_DATA — range, columnIndex (0-based), ascending
            FILTER_DATA — range (the header row)
            CONDITIONAL_FORMATTING — range, operator, value, backgroundColor, fontColor, bold
            MERGE_CELLS — range
            CLEAR_CELLS — range (wipes value, formula and formatting)
            RENAME_SHEET — newName
            RENAME_COLUMN — cell (the header cell), newName
            RENAME_CHART_TITLE — newTitle, chartIndex
            RENAME_CHART_AXIS — axis ("category" = x, "value" = y), newTitle, chartIndex
            SET_CELL_VALUE — cell, value, valueType ("text"|"number"|"boolean"|"date"), range — writes ONE literal value
            AUTOSIZE_COLUMNS — range (COLUMNS, e.g. "A:D"), maxWidth — widens columns to fit their contents
            FREEZE_PANES — rows, columns (COUNTS from the top/left; 0 and 0 unfreezes)
            NUMBER_FORMAT — range, format ("currency"|"percent"|"thousands"|"date"|… or a pattern), decimals — changes how numbers READ
            SET_BORDERS — range, sides ("all"|"outline"|"inside"|"top"|"bottom"|"left"|"right"), style ("thin"|"medium"|"thick"|"none"), color
            ALIGN_CELLS — range, horizontal ("left"|"center"|"right"), vertical ("top"|"middle"|"bottom"), wrapText, indent
            INSERT_ROWS — at (row number from 1), count — everything from that row down moves DOWN
            DELETE_ROWS — at + count, or range ("5:8") — rows below move UP, formulas into them break
            INSERT_COLUMNS — at (column letter), count — everything right of it moves RIGHT
            DELETE_COLUMNS — at + count, or range ("C:E") — columns after them shift back; charts are not repointed
            FILL_FORMULA — range (the whole range), formula — one formula down a column, references moving per row
            ADD_TOTALS_ROW — range (the data), function ("sum"|"average"|"count"|"min"|"max"), label — a whole totals row in one step
            REMOVE_DUPLICATES — range, columns ("A,C"), hasHeader — keeps the FIRST of each repeated row
            DELETE_SHEET — name (REQUIRED, never guessed) — removes a whole sheet; formulas reading from it break
            UNMERGE_CELLS — range (omit for every merge on the sheet) — splits merged blocks; values stay top-left
            DATA_VALIDATION — range, type ("list"|"whole"|"decimal"|"date"), values ("A,B,C") or min/max — limits what may be TYPED next
            CREATE_TABLE — range (INCLUDING the header row), name, style — a real Excel table, not a lookalike
            COLOR_SCALE — range, minColor/midColor/maxColor — shades cells by value, low to high, no threshold needed
            DATA_BARS — range, color, showValue — a bar inside each cell, in proportion to its value
            GROUP_ROWS — range ("5:20") or at + count, collapsed, ungroup — a foldable outline over rows
            PAGE_SETUP — orientation, fitToWidth, printArea, repeatHeaderRows, paperSize — how it prints
            HYPERLINK — cell + address, or range alone to linkify addresses already there; text, linkType
            COMMENT — cell, text, author, remove — a note pinned to one cell
            PROTECT_SHEET — unlockedRange, password, unprotect — read-only except the cells you name
            LOOKUP_FROM_SHEET — range, keyRange, sourceRange, sourceColumn — a column pulled across by key
            GROUP_BY — range, groupBy, valueColumn, function, targetSheet — one row per distinct value, totalled
            SPARKLINES — range (one column/row of cells), dataRange, type — a whole chart inside one cell
            """;

    /** The TRANSFORM_COLUMN index line, whose operation list comes from the registry. */
    public static final String TRANSFORM_COLUMN_INDEX_TEMPLATE =
            "TRANSFORM_COLUMN — range (ONE column), operation (%s), targetRange"
                    + " — rewrites the VALUES in a column\n";

    public static final String MUTATING_ACTIONS_INDEX_NOTE = """

            Every action also accepts "sheetName" or "sheetIndex". Ask for an action and you will be
            given its full rules before it runs.
            """;

    public static final String COLOR_REFERENCE = """
            COLOR REFERENCE — use these exact hex values when the user names a color:
            - blue: #1E3A8A  |  light blue: #BFDBFE  |  sky blue: #0EA5E9
            - red: #DC2626   |  light red / pink: #FECACA
            - green: #15803D |  light green: #BBF7D0
            - yellow: #CA8A04|  light yellow: #FEF08A
            - orange: #EA580C|  purple: #7C3AED
            - gray / grey: #6B7280  |  dark: #1F2937  |  white: #FFFFFF  |  black: #000000
            When the user says just "blue", "red", etc. always use the non-light variant unless they say "light".
            For header backgrounds pair with white fontColor (#FFFFFF) for readability.
            """;

    private ActionCatalog() {
    }
}
