package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.auth.AuthenticatedUser;
import com.ap0stole.sheetsmith.auth.CurrentUser;
import com.ap0stole.sheetsmith.domain.entity.JobRecord;
import com.ap0stole.sheetsmith.domain.entity.User;
import com.ap0stole.sheetsmith.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who a run belongs to, and the reason that is read where it is read.
 * <p>
 * The trap this pins is not in the rule but in the thread. A run is executed on a virtual thread,
 * and the security context does not follow it — so asking "who is signed in" from inside the job
 * answers "nobody", which is indistinguishable from an instance with authentication off. Every run
 * would come out ownerless and nothing would look wrong.
 */
class JobOwnerTest {

    private final UserRepository users = mock(UserRepository.class);
    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(Long id, String name) {
        User user = User.of(name, "hash");
        user.setId(id);
        when(users.findById(id)).thenReturn(Optional.of(user));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(id, name), null, List.of()));
    }

    /** Mirrors what JobService does on the request thread. */
    private void attribute(JobRecord job) {
        currentUser.id().flatMap(users::findById).ifPresent(job::setStartedBy);
    }

    @Test
    @DisplayName("a run made while signed in belongs to that person")
    void theCallerBecomesTheOwner() {
        signIn(7L, "dana");
        JobRecord job = JobRecord.create("tidy it", "book.xlsx", "/tmp/book.xlsx");

        attribute(job);

        assertThat(job.getStartedBy()).isNotNull();
        assertThat(job.getStartedBy().getId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("with nobody signed in the owner stays empty, not invented")
    void noCallerMeansNoOwner() {
        JobRecord job = JobRecord.create("tidy it", "book.xlsx", "/tmp/book.xlsx");

        attribute(job);

        // An audit that makes up who did something is worse than one that admits it does not know,
        // so the no-login case shows a dash rather than a placeholder user.
        assertThat(job.getStartedBy()).isNull();
    }

    @Test
    @DisplayName("the caller is unreadable from the thread the work runs on")
    void theSecurityContextDoesNotFollowAVirtualThread() throws Exception {
        signIn(7L, "dana");

        Future<Boolean> seenFromTheWorker;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            seenFromTheWorker = executor.submit(() -> currentUser.id().isPresent());
        }

        assertThat(seenFromTheWorker.get())
                .as("this is why the owner is read on the request thread and carried in, rather "
                        + "than looked up where the job actually runs")
                .isFalse();
    }

    @Test
    @DisplayName("an account deleted between sign-in and the run leaves the owner empty")
    void aVanishedAccountIsNotForced() {
        // The token outlives the row for as long as it has left to live. Better an unowned run than
        // a foreign key pointing at nothing.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(99L, "ghost"), null, List.of()));
        when(users.findById(any())).thenReturn(Optional.empty());

        JobRecord job = JobRecord.create("tidy it", "book.xlsx", "/tmp/book.xlsx");
        attribute(job);

        assertThat(job.getStartedBy()).isNull();
    }
}
