package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.AuthSecret;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthSecretRepository extends JpaRepository<AuthSecret, String> {
}
