package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.auth.CurrentUser;
import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.domain.entity.LlmUsage;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.domain.enums.UsageKind;
import com.ap0stole.sheetsmith.llm.LlmEngine;
import com.ap0stole.sheetsmith.llm.TokenUsage;
import com.ap0stole.sheetsmith.repository.LlmUsageRepository;
import com.ap0stole.sheetsmith.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Writes down what a call to a model cost.
 * <p>
 * Both flows come through here, which is the point: a spend chart built from one of them would be
 * confidently wrong about the total and look no different for it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageRecorder {

    private final LlmUsageRepository usage;
    private final com.ap0stole.sheetsmith.repository.ModelPriceRepository prices;
    private final UserRepository users;
    private final CurrentUser currentUser;

    /** Where "now" comes from, so a test can decide what it is. */
    private final Clock clock;

    /** Who to bill it to, resolved on the calling thread while the caller is still known. */
    public User caller() {
        return currentUser.id().flatMap(users::findById).orElse(null);
    }

    /**
     * Records one improve call. The owner is passed in rather than read here because the work runs
     * on a virtual thread, where the security context is not visible.
     * <p>
     * Its own transaction, and never allowed to break the caller: by the time this runs the work
     * has happened and the money is spent, so failing the turn because the bookkeeping failed would
     * turn a lost row into a lost answer. The annotation is on the public method rather than on the
     * shared helper below, because a call from inside the same class does not go through the proxy
     * and the propagation would silently do nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void improve(JobRecord job, User owner, String prompt,
                        TokenUsage tokens, LlmEngine engine, LocalDateTime startedAt) {
        save(new Entry(UsageKind.IMPROVE, owner, job, null, prompt, tokens, engine, startedAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void chat(String sessionId, User owner, String prompt,
                     TokenUsage tokens, LlmEngine engine, LocalDateTime startedAt) {
        save(new Entry(UsageKind.CHAT, owner, null, sessionId, prompt, tokens, engine, startedAt));
    }

    /**
     * A planning call made before any job exists: the plan-then-apply flow prices the plan in one
     * request and only creates the record if the user approves it in another. The call is still a
     * call, so it is written down against the session rather than waiting for a job that may never
     * come.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void chatlessPlan(String sessionId, User owner, String prompt,
                             TokenUsage tokens, LlmEngine engine, LocalDateTime startedAt) {
        save(new Entry(UsageKind.IMPROVE, owner, null, sessionId, prompt, tokens, engine, startedAt));
    }

    /**
     * One row of the audit, as its eight facts.
     * <p>
     * A record rather than eight parameters: they are always passed together, they are always in
     * the same order, and two of them are nullable in a way only the call site knows about — which
     * is exactly the shape that gets transposed by accident.
     *
     * @param job       the run this call belongs to, or null when it belongs to a chat turn
     * @param sessionId the document, for a chat turn — null for a run
     */
    private record Entry(UsageKind kind, User owner, JobRecord job, String sessionId, String prompt,
                         TokenUsage tokens, LlmEngine engine, LocalDateTime startedAt) {
    }

    private void save(Entry entry) {
        try {
            LlmUsage row = new LlmUsage();
            row.setKind(entry.kind());
            row.setUser(entry.owner());
            row.setJob(entry.job());
            row.setSessionId(entry.sessionId());
            row.setPrompt(entry.prompt());

            if (entry.tokens() != null) {
                row.setPromptTokens(entry.tokens().promptTokens());
                row.setCompletionTokens(entry.tokens().completionTokens());
                row.setTotalTokens(entry.tokens().totalTokens());
            }
            if (entry.engine() != null) {
                row.setProviderMode(entry.engine().providerMode());
                row.setProvider(entry.engine().provider());
                row.setModel(entry.engine().model());

                // The price as it stands now, stamped onto the row. Only for a cloud call: a model
                // on this machine bills nothing, so it has no rate to record whatever the price
                // list happens to say about it.
                if ("CLOUD".equals(entry.engine().providerMode()) && entry.engine().provider() != null && entry.engine().model() != null) {
                    prices.findByProviderAndModel(entry.engine().provider().toUpperCase(), entry.engine().model())
                            .ifPresent(price -> {
                                row.setInputPerMillion(price.getInputPerMillion());
                                row.setOutputPerMillion(price.getOutputPerMillion());
                            });
                }
            }

            row.setStartedAt(entry.startedAt() == null ? LocalDateTime.now(clock) : entry.startedAt());
            row.setFinishedAt(LocalDateTime.now(clock));
            usage.save(row);
        } catch (Exception e) {
            log.warn("Could not record what a {} call cost; the call itself is unaffected", entry.kind(), e);
        }
    }
}
