package com.ap0stole.sheetsmith.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZoneId;
import java.time.LocalDateTime;

/** A key the instance generated for itself because nobody supplied one. */
@Entity
@Table(name = "auth_secrets")
@Getter
@Setter
@NoArgsConstructor
public class AuthSecret {

    @Id
    private String name;

    @Column(nullable = false)
    private String secret;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public static AuthSecret of(String name, String secret) {
        AuthSecret entry = new AuthSecret();
        entry.name = name;
        entry.secret = secret;
        entry.createdAt = LocalDateTime.now(ZoneId.systemDefault());
        return entry;
    }
}
