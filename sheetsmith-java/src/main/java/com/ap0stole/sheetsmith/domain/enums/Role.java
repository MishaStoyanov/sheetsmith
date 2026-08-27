package com.ap0stole.sheetsmith.domain.enums;

/**
 * What an account may do to other accounts.
 * <p>
 * Nothing else is gated on this. History and analytics stay open to everybody, because knowing what
 * the instance has cost is not an administrative act — that was the whole reason roles waited until
 * after the metrics were built.
 */
public enum Role {

    /** Uses the application. Cannot see or change anybody else's account. */
    USER,

    /**
     * Manages people, and may promote somebody else to ADMIN — but never back down again.
     * <p>
     * The one-way door is deliberate: mutual demotion between two administrators is a fight the
     * software should not host, so undoing it is left to the one account that cannot be deleted.
     */
    ADMIN,

    /**
     * The seeded account. Everything ADMIN can do, plus demotion.
     * <p>
     * Not assignable: it belongs to the first account rather than being a rank anybody can be given.
     */
    SUPERADMIN;

    /** Whether this role may manage other accounts at all. */
    public boolean manages() {
        return this != USER;
    }
}
