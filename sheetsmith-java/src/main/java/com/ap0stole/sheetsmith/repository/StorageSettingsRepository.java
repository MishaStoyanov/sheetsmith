package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.StorageSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageSettingsRepository extends JpaRepository<StorageSettingsEntity, Short> {
}
