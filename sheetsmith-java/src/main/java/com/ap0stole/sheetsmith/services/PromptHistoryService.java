package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.auth.CurrentUser;
import com.ap0stole.sheetsmith.domain.dto.prompt.FrequentPromptDto;
import com.ap0stole.sheetsmith.domain.enums.UsageKind;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * What you have asked for before, so you do not have to type it again.
 * <p>
 * Nothing new is stored for this. Every call already records the words the person wrote, owned and
 * timestamped, so the whole feature is a question put to a table that was filled in for another
 * reason.
 * <p>
 * <strong>Only ever your own.</strong> A prompt is somebody describing their own data in their own
 * words — the sheet, the columns, sometimes the customer — so it is the one thing on this instance
 * that never appears on a shared screen. Signed in, the query is pinned to your id and nothing else;
 * signed out, there are no accounts at all and the unowned prompts are yours by construction. The
 * two cases are separate branches rather than one clause with a null in it, because a filter that
 * quietly matches everyone is exactly the mistake worth making impossible here.
 * <p>
 * Repeats are matched by their exact text. Grouping by meaning is a different and much larger
 * problem, and the people this helps most — anyone running the same job on a schedule — write the
 * same sentence every time anyway.
 */
@Service
@RequiredArgsConstructor
// The only thing interpolated into this query is the owner clause, and its value is a
// "?" travelling in the argument list beside it — there is no caller text in the SQL.
// An identifier cannot be bound as a parameter, which is what the rule would ask for.
@SuppressWarnings("java:S2077")
public class PromptHistoryService {

    /** Two is what makes it a habit rather than a thing that happened once. */
    private static final int MIN_REPEATS = 2;

    private static final int MAX_LIMIT = 20;

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public List<FrequentPromptDto> frequent(UsageKind kind, int limit) {
        int capped = Math.clamp(limit, 1, MAX_LIMIT);
        Optional<Long> me = currentUser.id();

        String owner = me.isPresent() ? "user_id = ?" : "user_id is null";
        String sql = """
                select prompt, count(*) as uses, max(started_at) as last_used
                from llm_usage
                where prompt is not null and length(trim(prompt)) > 0
                  and kind = ?
                  and %s
                group by prompt
                having count(*) >= ?
                order by uses desc, last_used desc
                limit ?
                """.formatted(owner);

        Object[] args = me
                .map(id -> new Object[]{kind.name(), id, MIN_REPEATS, capped})
                .orElseGet(() -> new Object[]{kind.name(), MIN_REPEATS, capped});

        return jdbc.query(sql,
                (rs, i) -> new FrequentPromptDto(
                        rs.getString("prompt"),
                        rs.getLong("uses"),
                        rs.getTimestamp("last_used").toLocalDateTime()),
                args);
    }
}
