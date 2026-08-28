package com.ap0stole.sheetsmith.domain.dto;

/**
 * The storage settings as the screen needs them: what was chosen, and what that currently means.
 *
 * @param rootDir      the folder new files go into, or null for the ones the instance was started
 *                     with
 * @param maxFiles     how many spreadsheets to keep, or null for no cap
 * @param maxBytes     how much disk to use, or null for no cap
 * @param uploadDir    where inputs are actually being written right now
 * @param resultDir    where results are actually being written right now
 * @param fileCount    spreadsheets in the archive at this moment
 * @param bytesUsed    what they take up
 * @param writable     whether the chosen folder can actually be written to — asked rather than
 *                     assumed, because a folder that stopped being writable looks exactly like one
 *                     that always was until something tries
 */
public record StorageSettingsDto(
        String rootDir,
        Integer maxFiles,
        Long maxBytes,
        String uploadDir,
        String resultDir,
        int fileCount,
        long bytesUsed,
        boolean writable) {

    /** What the caller may set; the rest is read back from the disk. */
    public record Update(String rootDir, Integer maxFiles, Long maxBytes) {
    }
}
