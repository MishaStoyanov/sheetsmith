package com.ap0stole.sheetsmith.auth;

import com.ap0stole.sheetsmith.configs.AuthConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Turns a {@code Bearer} header into the caller's identity.
 * <p>
 * A bad token is treated as no token rather than as an error: the chain decides what an
 * unauthenticated request deserves, and answering here would mean this filter has an opinion about
 * endpoints that are open to everyone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final AuthConfig authConfig;
    private final AccessTokenService accessTokenService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !authConfig.isEnabled();
    }

    /**
     * Run on the async dispatch too, which {@link OncePerRequestFilter} sits out by default.
     * <p>
     * A streamed chat turn returns an {@code SseEmitter} and finishes on another thread; completing
     * it sends the request through this chain a second time, and that pass is authorised like any
     * other. Skipped here, nothing reads the token on it — and the chain is stateless, so there is
     * no context to restore either. The caller arrived anonymous, {@code AuthorizationFilter}
     * refused {@code /api/chat/**}, and by then the response was committed, so the refusal could
     * not even be delivered: the turn had run and its answer was already in the database, while the
     * browser saw a stream that stopped mid-air. Every streamed turn, on any instance with accounts
     * turned on.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request, header.substring(PREFIX.length()).trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            Jwt jwt = accessTokenService.decode(token);
            AuthenticatedUser principal = new AuthenticatedUser(
                    Long.valueOf(jwt.getSubject()), jwt.getClaimAsString("name"));

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, List.of());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | NumberFormatException e) {
            // Expired is the ordinary case, not an incident: the browser is about to refresh.
            log.debug("Rejected an access token: {}", e.getMessage());
        }
    }
}
