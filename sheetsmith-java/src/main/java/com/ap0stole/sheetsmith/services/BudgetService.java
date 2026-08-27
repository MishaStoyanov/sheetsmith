package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.auth.CurrentUser;
import com.ap0stole.sheetsmith.domain.entity.ModelPrice;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.repository.ModelPriceRepository;
import com.ap0stole.sheetsmith.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
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

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final UserRepository users;
    private final ModelPriceRepository prices;
    private final CurrentUser currentUser;
    private final JdbcTemplate jdbc;

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
                .map(user -> user.getMonthlyBudget())
                .orElse(null);
        if (limit == null) {
            return;
        }

        BigDecimal spent = spentThisMonth(caller.get());
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

    /** What this person has spent since the first of the month, as far as prices can tell. */
    @Transactional(readOnly = true)
    public BigDecimal spentThisMonth(Long userId) {
        LocalDateTime from = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        Map<String, ModelPrice> priceList = new HashMap<>();
        prices.findAll().forEach(price -> priceList.put(key(price.getProvider(), price.getModel()), price));
        if (priceList.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal[] total = { BigDecimal.ZERO };
        jdbc.query("""
                select coalesce(provider, '') as provider, coalesce(model, '') as model,
                       coalesce(sum(prompt_tokens), 0)     as prompt_tokens,
                       coalesce(sum(completion_tokens), 0) as completion_tokens
                from llm_usage
                where user_id = ? and started_at >= ?
                  -- Cloud only. A local run costs no money, so no price on it can make one.
                  and provider_mode = 'CLOUD'
                group by provider, model
                """, rs -> {
            ModelPrice price = priceList.get(key(rs.getString("provider"), rs.getString("model")));
            if (price == null) {
                // Unpriced, so unknown rather than free. Counted as nothing, and said so elsewhere.
                return;
            }
            total[0] = total[0]
                    .add(price.getInputPerMillion().multiply(BigDecimal.valueOf(rs.getLong("prompt_tokens")))
                            .divide(MILLION, 6, RoundingMode.HALF_UP))
                    .add(price.getOutputPerMillion().multiply(BigDecimal.valueOf(rs.getLong("completion_tokens")))
                            .divide(MILLION, 6, RoundingMode.HALF_UP));
        }, userId, java.sql.Timestamp.valueOf(from));

        return total[0].setScale(4, RoundingMode.HALF_UP);
    }

    /** Money as somebody would write it, not as BigDecimal prints it. */
    private static String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String key(String provider, String model) {
        return (provider == null ? "" : provider.toUpperCase()) + " " + (model == null ? "" : model);
    }
}
