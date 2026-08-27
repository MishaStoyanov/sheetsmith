package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.auth.CurrentUser;
import com.ap0stole.sheetsmith.configs.AuthConfig;
import com.ap0stole.sheetsmith.domain.dto.user.*;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.services.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Five endpoints rather than four, because creating and searching are two different meanings and
 * one URL cannot hold both: they would be told apart only by the shape of the request body.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthConfig authConfig;
    private final UserService userService;
    private final CurrentUser currentUser;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@RequestBody @Valid CreateUserRequest request) {
        requireAccounts();
        return userService.create(request);
    }

    /** POST, not GET, because the filters are a body rather than a queue of query parameters. */
    @PostMapping("/search")
    public Page<UserDto> search(@RequestBody(required = false) UserSearchRequest request) {
        requireAccounts();
        return userService.search(request == null
                ? new UserSearchRequest(null, null, null, null, null)
                : request);
    }

    @PutMapping("/{id}")
    public UserDto replace(@PathVariable Long id, @RequestBody @Valid ReplaceUserRequest request) {
        requireAccounts();
        return userService.replace(id, request);
    }

    @PatchMapping("/{id}")
    public UserDto update(@PathVariable Long id, @RequestBody @Valid PatchUserRequest request) {
        requireAccounts();
        return userService.update(id, request, currentUser.id().orElse(null));
    }

    /**
     * Changes what somebody may do.
     * <p>
     * Its own endpoint rather than another field on the PATCH, because it is a different kind of
     * decision from a rename and carries different rules — and a role arriving as one optional
     * field among several is a role that can be changed by accident.
     */
    @PatchMapping("/{id}/role")
    public UserDto changeRole(@PathVariable Long id, @RequestBody @Valid ChangeRoleRequest request) {
        requireAccounts();
        return userService.changeRole(id, request.role(), currentUser.id().orElse(null));
    }

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
