package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.auth.CurrentUser;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * What a person has spent this month, and whether they may spend more.
 * <p>
 * <strong>What this can see.</strong> Two conditions, and they are not the same one.
 * <p>
 * A call counts only if it went to a <em>cloud</em> provider — a model running on your own machine
 * bills nothing, whatever the price list happens to say about it. That is checked against what the
 * run actually recorded rather than inferred from whether somebody priced the model, because a
 * price entered on a local model by mistake would otherwise start refusing that person's work.
 * <p>
 * And it counts only if that model has a price, because an unpriced one costs an unknown amount
 * rather than nothing. That is the honest reach of a limit denominated in money — the alternative
 * would be to guess, and a budget enforced against a guess is worse than no budget. The interface
 * says as much where the limit is set.
 * <p>
 * <strong>The month is the calendar month</strong>, in the server's own time zone. A rolling
 * thirty days would be defensible and harder to reason about: "what have I spent this month" is a
 * question people already know the shape of, and a limit nobody can predict the reset of is a limit
 * they will not plan around.
 * <p>
 * The check runs before a call rather than after, so a person is stopped at the door rather than
 * billed and then told. It cannot be exact: a run already under way is allowed to finish, and one
 * call may carry someone past their ceiling. Refusing mid-run would leave a half-edited sheet,
 * which is a worse outcome than a few cents of overshoot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetService {

    private final UserRepository users;
    private final CurrentUser currentUser;
    private final JdbcTemplate jdbc;

    /** Where "now" comes from, so a test can decide what it is. */
    private final Clock clock;

    /**
     * Refuses the call if the caller is already at their limit.
     * <p>
     * Nobody signed in means no limit to check — with authentication off there are no accounts, and
     * a spend ceiling is a statement about a person.
     */
    @Transactional(readOnly = true)
    public void requireHeadroom() {
        Optional<Long> caller = currentUser.id();
        if (caller.isEmpty()) {
            return;
        }

        BigDecimal limit = users.findById(caller.get())
                .map(User::getMonthlyBudget)
                .orElse(null);
        if (limit == null) {
            return;
        }

        BigDecimal spent = spentSinceTheFirst(caller.get());
        if (spent.compareTo(limit) < 0) {
            return;
        }

        log.info("Refused a call for user {}: spent {} of a {} monthly limit", caller.get(), spent, limit);
        // One literal rather than two concatenated: `.formatted` binds to the string it follows, so
        // the split version formatted only the second half and the person read a bare "$%s".
        throw new ApiException(ErrorCode.BUDGET_EXHAUSTED, """
                You have used $%s of your $%s limit for this month. Ask an administrator to raise \
                it, or wait until the month turns over."""
                .formatted(plain(spent), plain(limit)));
    }

    /**
     * What this person has spent since the first of the month.
     * <p>
     * Summed from the rates each call recorded rather than from the price list as it stands now.
     * Read against today's prices, a correction to a figure would move what somebody had already
     * spent last week — and a ceiling somebody crossed retroactively is not a ceiling.
     * <p>
     * Arithmetic in the database, in numeric rather than floating point, because this is money
     * being compared against a limit and binary rounding has no business deciding whether somebody
     * may carry on working.
     */
    @Transactional(readOnly = true)
    public BigDecimal spentThisMonth(Long userId) {
        return spentSinceTheFirst(userId);
    }

    /** The same sum, reachable from {@link #requireHeadroom} without going out through the proxy. */
    private BigDecimal spentSinceTheFirst(Long userId) {
        LocalDateTime from = LocalDate.now(clock).withDayOfMonth(1).atStartOfDay();

        BigDecimal total = jdbc.queryForObject("""
                select coalesce(sum(
                           coalesce(input_per_million, 0)  * prompt_tokens     / 1000000
                         + coalesce(output_per_million, 0) * completion_tokens / 1000000), 0)
                from llm_usage
                where user_id = ? and started_at >= ?
                  -- Cloud only. A local run costs no money, so no rate on it could make one.
                  and provider_mode = 'CLOUD'
                """, BigDecimal.class, userId, java.sql.Timestamp.valueOf(from));

        // A row with no rate had no price when it was made: unknown rather than free, and counted
        // as nothing either way. Said out loud on the screens, not guessed at here.
        return (total == null ? BigDecimal.ZERO : total).setScale(4, RoundingMode.HALF_UP);
    }

    /** Money as somebody would write it, not as BigDecimal prints it. */
    private static String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
