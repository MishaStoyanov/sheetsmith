package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.configs.SecurityProperties;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The by-path endpoint hands the caller a file path, so these tests are the actual boundary:
 * everything they let through, the server will read or overwrite.
 */
class PathGuardTest {

    private PathGuard guard(boolean enabled, Path... roots) {
        SecurityProperties config = new SecurityProperties();
        config.setPathEndpointEnabled(enabled);
        config.setPathEndpointRoots(Arrays.stream(roots).map(Path::toString).toList());
        return new PathGuard(config);
    }

    private Path file(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.writeString(f, "x");
        return f;
    }

    @Test
    @DisplayName("a file inside a root is accepted")
    void insideRootPasses(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("data");
        Path input = file(root.resolve("sub"), "book.xlsx");

        Path resolved = guard(true, root).resolveInput(input.toString(), "inputPath");

        assertThat(resolved).isEqualTo(input.toRealPath());
    }

    @Test
    @DisplayName("an output file that does not exist yet inside a root is accepted")
    void missingOutputInsideRootPasses(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("data");
        Files.createDirectories(root);
        Path output = root.resolve("result.xlsx");

        Path resolved = guard(true, root).resolveOutput(output.toString(), "outputPath");

        assertThat(resolved).isEqualTo(root.toRealPath().resolve("result.xlsx"));
        assertThat(Files.exists(resolved)).isFalse();
    }

    @Test
    @DisplayName("'..' climbing out of a root is rejected even though every segment exists")
    void traversalOutOfRootRejected(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("data");
        Files.createDirectories(root);
        Path secret = file(tmp.resolve("etc"), "passwd");
        String traversal = root.resolve("..").resolve("etc").resolve("passwd").toString();

        assertThat(Files.exists(Path.of(traversal))).isTrue();
        assertThatThrownBy(() -> guard(true, root).resolveInput(traversal, "inputPath"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.PATH_TRAVERSAL);
        assertThat(secret).exists();
    }

    @Test
    @DisplayName("an absolute path outside every root is rejected")
    void absolutePathOutsideRootsRejected(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("data");
        Files.createDirectories(root);
        Path outside = file(tmp.resolve("elsewhere"), "book.xlsx");

        assertThatThrownBy(() -> guard(true, root).resolveInput(outside.toString(), "inputPath"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("outside the allowed roots");
    }

    @Test
    @DisplayName("a sibling directory whose name merely starts with the root's name is rejected")
    void siblingWithRootNamePrefixRejected(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("data");
        Files.createDirectories(root);
        Path evil = file(tmp.resolve("data-evil"), "book.xlsx");

        assertThatThrownBy(() -> guard(true, root).resolveInput(evil.toString(), "inputPath"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.PATH_TRAVERSAL);
    }

    @Test
    @DisplayName("a symlink inside a root pointing outside it is rejected")
    void symlinkEscapingRootRejected(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("data");
        Files.createDirectories(root);
        Path outsideDir = tmp.resolve("secrets");
        file(outsideDir, "passwd.xlsx");

        Path link = root.resolve("escape");
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows needs developer mode or elevation to create symlinks.
            assumeTrue(false, "symlink creation not permitted here: " + e.getMessage());
        }

        assertThatThrownBy(() -> guard(true, root).resolveInput(
                link.resolve("passwd.xlsx").toString(), "inputPath"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.PATH_TRAVERSAL);
    }

    @Test
    @DisplayName("a missing file inside a root is reported as not found, not as a traversal")
    void missingInputInsideRootIsNotFound(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("data");
        Files.createDirectories(root);

        assertThatThrownBy(() -> guard(true, root).resolveInput(root.resolve("nope.xlsx").toString(), "inputPath"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    @DisplayName("while the endpoint is disabled every path is refused, with the env var named")
    void disabledEndpointRefusesEverything(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("data");
        Path input = file(root, "book.xlsx");

        assertThatThrownBy(() -> guard(false, root).resolveInput(input.toString(), "inputPath"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("SHEETSMITH_PATH_ENDPOINT_ENABLED")
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.PATH_ENDPOINT_DISABLED);
    }

    @Test
    @DisplayName("enabled with no roots fails loudly instead of allowing everything")
    void enabledWithoutRootsFails(@TempDir Path tmp) throws IOException {
        Path input = file(tmp.resolve("data"), "book.xlsx");
        SecurityProperties config = new SecurityProperties();
        config.setPathEndpointEnabled(true);
        config.setPathEndpointRoots(List.of("   "));

        assertThatThrownBy(() -> new PathGuard(config).resolveInput(input.toString(), "inputPath"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("SHEETSMITH_PATH_ENDPOINT_ROOTS")
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.PATH_ENDPOINT_MISCONFIGURED);

        assertThatThrownBy(config::validate).isInstanceOf(IllegalStateException.class);
    }
}
