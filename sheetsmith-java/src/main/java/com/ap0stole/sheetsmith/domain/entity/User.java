package com.ap0stole.sheetsmith.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A person a job can be attributed to, and — when {@code sheetsmith.auth.enabled} is on — someone
 * who can log in.
 * <p>
 * The first row is seeded by migration rather than created here, so where it came from is a line in
 * the schema's history rather than a side effect of whichever boot ran first.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /** A hash, never what the user typed — the column is named so that it cannot be misread. */
    @Column(nullable = false)
    private String passwordHash;

    /**
     * Set on the seeded {@code admin} alone, and cleared the first time its password is changed.
     * It is what stops {@code admin}/{@code admin} quietly surviving an instance being put in
     * front of other people.
     */
    @Column(nullable = false)
    private boolean mustChangePassword;

    public static User of(String name, String passwordHash) {
        User user = new User();
        user.name = name;
        user.passwordHash = passwordHash;
        return user;
    }
}
