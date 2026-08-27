package com.ap0stole.sheetsmith.domain.enums;

/**
 * What an account may do, to other accounts and to other people's work.
 * <p>
 * Two things hang on this. <strong>Whose work you can see</strong>: your own runs and figures
 * always, every ordinary user's if you manage accounts, everybody's if you are the superadmin — the
 * history and the analytics answer the same question, so they answer it the same way.
 * <strong>Deletion</strong>: the superadmin's alone, because it is the one action nothing survives
 * to record.
 * <p>
 * Everything else stays open. Knowing what the instance has cost is not an administrative act.
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
