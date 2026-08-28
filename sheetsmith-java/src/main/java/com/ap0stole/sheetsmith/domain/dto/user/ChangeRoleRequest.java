package com.ap0stole.sheetsmith.domain.dto.user;

import com.ap0stole.sheetsmith.domain.enums.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Its own endpoint rather than a field on the patch: changing what somebody may do is not a rename.
 *
 * @param role USER or ADMIN. SUPERADMIN cannot be handed out — it belongs to the seeded account,
 *             which is what makes it the one that can always put things right
 */
public record ChangeRoleRequest(@NotNull(message = "A role is required") Role role) {
}
