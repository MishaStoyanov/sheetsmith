package com.ap0stole.sheetsmith.domain.dto.user;

import com.ap0stole.sheetsmith.domain.enums.Role;
import jakarta.validation.constraints.NotNull;

/** Its own endpoint rather than a field on the patch: changing what somebody may do is not a rename. */
public record ChangeRoleRequest(@NotNull(message = "A role is required") Role role) {
}
