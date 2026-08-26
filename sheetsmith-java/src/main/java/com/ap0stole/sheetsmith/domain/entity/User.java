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
 * A person a job can be attributed to.
 * <p>
 * There is no authentication in this app yet, so nothing creates one of these on its own — the table
 * exists first, and the code that fills it comes with the login it belongs to.
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

    public static User of(String name, String passwordHash) {
        User user = new User();
        user.name = name;
        user.passwordHash = passwordHash;
        return user;
    }
}
