package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.SecurityProperties;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Confines the by-path flow to the operator-declared roots.
 * <p>
 * A string check cannot do this: {@code /data/../etc} is textually clean, a symlink inside a root
 * can point anywhere, and {@code /data-evil} shares a prefix with {@code /data}. So every path is
 * canonicalised through the filesystem — real path of the deepest part that exists, then the
 * remaining segments applied one at a time — and compared component-wise against each root's own
 * real path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PathGuard {

    private final SecurityProperties securityProperties;

    /** Resolves an input path; it must exist as a regular file inside a root. */
    public Path resolveInput(String rawPath, String field) {
        Path resolved = resolve(rawPath, field);
        if (!Files.isRegularFile(resolved)) {
            throw new ApiException(ErrorCode.FILE_NOT_FOUND, "Input file not found: " + rawPath, field);
        }
        return resolved;
    }

    /** Resolves an output path; the file may legitimately not exist yet, its directory must. */
    public Path resolveOutput(String rawPath, String field) {
        Path resolved = resolve(rawPath, field);
        Path parent = resolved.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ApiException(ErrorCode.FILE_NOT_FOUND,
                    "Output directory does not exist: " + rawPath, field);
        }
        if (Files.isDirectory(resolved)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Output path is a directory: " + rawPath, field);
        }
        return resolved;
    }

    private Path resolve(String rawPath, String field) {
        requireEnabled();
        List<Path> roots = resolvedRoots();

        Path candidate;
        try {
            candidate = canonicalise(Path.of(rawPath).toAbsolutePath());
        } catch (InvalidPathException | IOException _) {
            throw new ApiException(ErrorCode.PATH_TRAVERSAL,
                    "Path cannot be resolved: " + rawPath, field);
        }

        if (roots.stream().noneMatch(candidate::startsWith)) {
            log.warn("Rejected path '{}' (resolved to '{}'): outside {}", rawPath, candidate, roots);
            throw new ApiException(ErrorCode.PATH_TRAVERSAL,
                    "Path is outside the allowed roots " + roots + ": " + rawPath, field);
        }
        return candidate;
    }

    private void requireEnabled() {
        if (!securityProperties.isPathEndpointEnabled()) {
            throw new ApiException(ErrorCode.PATH_ENDPOINT_DISABLED,
                    "The by-path endpoint is disabled. Set SHEETSMITH_PATH_ENDPOINT_ENABLED=true and list "
                            + "the allowed directories in SHEETSMITH_PATH_ENDPOINT_ROOTS (comma-separated) "
                            + "to enable it; upload the file instead to avoid enabling it at all.");
        }
    }

    private List<Path> resolvedRoots() {
        List<Path> roots = new ArrayList<>();
        for (String configured : securityProperties.getPathEndpointRoots()) {
            if (configured == null || configured.isBlank()) {
                continue;
            }
            try {
                roots.add(canonicalise(Path.of(configured.trim()).toAbsolutePath()));
            } catch (InvalidPathException | IOException e) {
                log.warn("Ignoring unusable path-endpoint root '{}': {}", configured, e.toString());
            }
        }
        if (roots.isEmpty()) {
            throw new ApiException(ErrorCode.PATH_ENDPOINT_MISCONFIGURED,
                    "The by-path endpoint is enabled but no usable directory is configured. "
                            + "Set SHEETSMITH_PATH_ENDPOINT_ROOTS to existing directories (comma-separated).");
        }
        return roots;
    }

    /**
     * Real path of the longest existing prefix, then the missing tail re-applied segment by segment —
     * so symlinks are followed and {@code ..} is interpreted against the real parent, never textually.
     */
    private static Path canonicalise(Path absolute) throws IOException {
        Deque<Path> tail = new ArrayDeque<>();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name != null) {
                tail.push(name);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new NoSuchFileException(absolute.toString());
        }

        Path resolved = existing.toRealPath();
        for (Path segment : tail) {
            String name = segment.toString();
            if (".".equals(name)) {
                continue;
            }
            if ("..".equals(name)) {
                Path parent = resolved.getParent();
                resolved = (parent != null) ? parent : resolved;
                continue;
            }
            resolved = resolved.resolve(segment);
            if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
                resolved = resolved.toRealPath();
            }
        }
        return resolved;
    }
}
