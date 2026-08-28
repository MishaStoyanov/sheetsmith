package com.ap0stole.sheetsmith.auth;

import com.ap0stole.sheetsmith.domain.entity.DocumentSession;
import com.ap0stole.sheetsmith.repository.DocumentSessionRepository;
import com.ap0stole.sheetsmith.services.WorkVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether the caller may open a particular document session — the rule behind
 * {@code @PreAuthorize("@access.maySeeSession(#sessionId)")}.
 * <p>
 * A bean of its own rather than a line inside each handler: the rule belongs beside the endpoint it
 * guards, where somebody reading the controller can see it, and Spring evaluates it before the
 * method body — including before the streamed turn hands its work to a virtual thread that carries
 * no security context.
 * <p>
 * <strong>A session nobody has is allowed through.</strong> It is not the caller's, but it is not
 * anybody's, and the handler behind this answers "session not found or expired" — which is what the
 * screen is built to recover from, and what actually happens every night when the cleanup runs.
 * Refusing here instead would turn every expired document into "you may not", and send people
 * looking for a permission problem that does not exist.
 */
@Component("access")
@RequiredArgsConstructor
public class SessionAccess {

    private final DocumentSessionRepository sessions;
    private final WorkVisibility visibility;

    public boolean maySeeSession(String sessionId) {
        return sessions.findById(sessionId)
                .map(this::mayRead)
                .orElse(true);
    }

    private boolean mayRead(DocumentSession session) {
        return visibility.mayRead(session);
    }
}
