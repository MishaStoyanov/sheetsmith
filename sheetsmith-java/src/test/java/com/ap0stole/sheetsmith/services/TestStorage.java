package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.FileStorageConfig;
import com.ap0stole.sheetsmith.repository.StorageSettingsRepository;

import static org.mockito.Mockito.mock;

/**
 * A storage-settings service for the tests that build their services by hand.
 * <p>
 * Real rather than mocked, with an empty repository behind it: nothing has chosen a folder, so it
 * answers with the directories the configuration names — which is the world these tests describe
 * and the one the application starts in. A mock would answer null and the failure would look like a
 * path bug rather than a missing stub.
 */
final class TestStorage {

    private TestStorage() {
    }

    static StorageSettingsService storage(FileStorageConfig config) {
        return new StorageSettingsService(mock(StorageSettingsRepository.class), config, java.time.Clock.systemDefaultZone());
    }
}
