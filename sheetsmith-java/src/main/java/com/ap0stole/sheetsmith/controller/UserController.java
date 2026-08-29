package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.auth.CurrentUser;
import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.dto.user.*;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.services.UserService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Five endpoints rather than four, because creating and searching are two different meanings and
 * one URL cannot hold both: they would be told apart only by the shape of the request body.
 */
@Tag(name = "Accounts", description = "Accounts, roles and spend limits. Present only when accounts are switched on.")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthConfig authConfig;
    private final UserService userService;
    private final CurrentUser currentUser;
    private final com.ap0stole.sheetsmith.services.BudgetRequestService budgetRequests;

    @PreAuthorize("@authz.admin()")
    @Operation(summary = "Create an account")
    @ApiResponse(responseCode = "400", description = "A blank name, or a password shorter than the minimum.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "409", description = "That name is taken. Names are unique, because 'who ran this' stops being an answer at the second namesake.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@RequestBody @Valid CreateUserRequest request) {
        requireAccounts();
        return userService.create(request);
    }


    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Search accounts",
            description = "Open to anyone signed in, because the history’s started-by filter is built from it. Spending is only filled in where the caller may see it.")
    @PostMapping("/search")
    public Page<UserDto> search(@RequestBody(required = false) UserSearchRequest request) {
        requireAccounts();
        return userService.search(request == null
                ? new UserSearchRequest(null, null, null, null, null)
                : request);
    }

    @PreAuthorize("@authz.admin()")
    @Operation(summary = "Replace an account")
    @PutMapping("/{id}")
    public UserDto replace(@PathVariable Long id, @RequestBody @Valid ReplaceUserRequest request) {
        requireAccounts();
        return userService.replace(id, request);
    }

    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Rename, or change a password",
            description = "Your own password takes the current one; an administrator resetting somebody else’s does not, but may only do it to an ordinary user - never a peer, never the seeded account.")
    @ApiResponse(responseCode = "400", description = "Changing your own password without the current one, or a name already taken.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "403", description = "An administrator pointing at a peer or at the seeded account.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "404", description = "No such account.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PatchMapping("/{id}")
    public UserDto update(@PathVariable Long id, @RequestBody @Valid PatchUserRequest request) {
        requireAccounts();
        return userService.update(id, request, currentUser.id().orElse(null));
    }


    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Your own limit and what you have spent",
            description = "Its own call rather than a field on the session: the people who most need it never reach the accounts screen, and it changes while they work.")
    @GetMapping("/me/spend")
    public SpendDto mySpend() {
        requireAccounts();
        return userService.mySpend(currentUser.id().orElse(null));
    }

    /**
     * Asks for a bigger ceiling. No body: the request is "more, please" — how much more is the
     * decision of whoever answers, and asking somebody to name a figure invites them to name the
     * one they think will be granted rather than the one they need.
     */
    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Ask for a bigger limit",
            description = "No amount: how much more is the decision of whoever answers. One open request at a time.")
    @ApiResponse(responseCode = "409", description = "A request from this account is already waiting for an answer.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PostMapping("/me/budget-request")
    public BudgetRequestDto askForMore() {
        requireAccounts();
        return BudgetRequestDto.from(budgetRequests.ask(currentUser.id().orElse(null)));
    }

    /** Marks the outcome as read, which is what makes the notification happen once. */
    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Acknowledge the answer",
            description = "What makes the notification happen once.")
    @PostMapping("/me/budget-request/seen")
    public ResponseEntity<Void> markDecisionSeen() {
        requireAccounts();
        budgetRequests.markSeen(currentUser.id().orElse(null));
        return ResponseEntity.noContent().build();
    }

    /** Everything still waiting on an answer, filtered to the ones this caller may answer. */
    @PreAuthorize("@authz.admin()")
    @Operation(summary = "Requests waiting for you",
            description = "Already narrowed to the ones this caller may answer.")
    @GetMapping("/budget-requests")
    public List<BudgetRequestDto> pendingRequests() {
        requireAccounts();
        return budgetRequests.pendingVisibleTo();
    }

    @PreAuthorize("@authz.admin()")
    @Operation(summary = "Approve or decline a request",
            description = "Approving carries the new limit and is refused if it is not larger: the person is told their limit was raised, and that has to be true.")
    @ApiResponse(responseCode = "400", description = "Approving without a larger limit than the one in force. The person is told their limit was raised, and that has to be true.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "404", description = "No such request, or it has already been answered.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PostMapping("/budget-requests/{id}/decide")
    public BudgetRequestDto decide(@PathVariable Long id, @RequestBody @Valid DecideBudgetRequest request) {
        requireAccounts();
        return BudgetRequestDto.from(budgetRequests.decide(id, request.approve(), request.newLimit(),
                currentUser.id().orElse(null)));
    }

    /**
     * Sets or clears a monthly spend limit.
     * <p>
     * Its own endpoint for the same reason the role has one: null means "no limit" here, and on a
     * PATCH null already means "leave this alone". One of those two meanings has to move.
     */
    @PreAuthorize("@authz.admin()")
    @Operation(summary = "Set a monthly spend limit",
            description = "Null clears it. Nobody sets their own - except the superadmin, who has nobody above them.")
    @ApiResponse(responseCode = "403", description = "Your own limit — a limit you can lift is not a limit — or the account above you.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "400", description = "A negative amount.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PutMapping("/{id}/budget")
    public UserDto setBudget(@PathVariable Long id, @RequestBody @Valid SetBudgetRequest request) {
        requireAccounts();
        return userService.setMonthlyBudget(id, request.monthlyBudget(), currentUser.id().orElse(null));
    }

    /**
     * Changes what somebody may do.
     * <p>
     * Its own endpoint rather than another field on the PATCH, because it is a different kind of
     * decision from a rename and carries different rules — and a role arriving as one optional
     * field among several is a role that can be changed by accident.
     */
    @PreAuthorize("@authz.admin()")
    @Operation(summary = "Change what somebody may do",
            description = "ADMIN can be given but not taken back; only the superadmin demotes. SUPERADMIN cannot be handed out, and the seeded account’s role cannot be changed.")
    @ApiResponse(responseCode = "403", description = "Handing out SUPERADMIN, changing the seeded account, changing your own role, or demoting without being the superadmin.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PatchMapping("/{id}/role")
    public UserDto changeRole(@PathVariable Long id, @RequestBody @Valid ChangeRoleRequest request) {
        requireAccounts();
        return userService.changeRole(id, request.role(), currentUser.id().orElse(null));
    }

    @PreAuthorize("@authz.superadmin()")
    @Operation(summary = "Delete an account",
            description = "Their sessions end at once. Runs they started stay, with no owner.")
    @ApiResponse(responseCode = "403", description = "The seeded account, or the one the caller is signed in with. Neither is a permission rule: they are locks against locking yourself out.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "404", description = "No such account.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        requireAccounts();
        userService.delete(id, currentUser.id().orElse(null));
        return ResponseEntity.noContent().build();
    }

    /**
     * With authentication off there are no accounts to manage — the table exists but nothing reads
     * it, and offering a user list would suggest the instance checks who is asking.
     */
    private void requireAccounts() {
        if (!authConfig.isEnabled()) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "This instance runs without authentication. Set SHEETSMITH_AUTH_ENABLED=true to use accounts.");
        }
    }
}
