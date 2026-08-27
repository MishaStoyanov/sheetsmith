package com.ap0stole.sheetsmith.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the last-resort recipe in the README actually works.
 * <p>
 * It is the instruction someone follows when nothing else is available to them, and a wrong hash
 * there fails in the worst possible way: silently, on a machine whose owner has already run out of
 * other options. Nothing else in the repository would catch a typo in it.
 */
class DocumentedRecoveryTest {

    private static final Pattern BCRYPT = Pattern.compile("(\\$2[aby]\\$\\d\\d\\$[./A-Za-z0-9]{53})");

    @Test
    @DisplayName("the hash printed in the README really is the word 'admin'")
    void theReadmeRecipeRestoresTheDocumentedPassword() throws IOException {
        String readme = Files.readString(Path.of("..", "README.md"));
        Matcher hash = BCRYPT.matcher(readme);

        assertThat(hash.find()).as("the README should carry the recovery hash").isTrue();
        assertThat(new BCryptPasswordEncoder().matches("admin", hash.group(1)))
                .as("somebody following this has no other way in; a wrong hash strands them silently")
                .isTrue();
    }

    @Test
    @DisplayName("the README and the migration agree on which hash that is")
    void theRecipeMatchesWhatIsSeeded() throws IOException {
        // Two copies of one constant, in a document and in SQL. They can only drift apart, and the
        // symptom of drift is a recovery step that quietly does not work.
        Matcher inReadme = BCRYPT.matcher(Files.readString(Path.of("..", "README.md")));
        Matcher inMigration = BCRYPT.matcher(
                Files.readString(Path.of("src", "main", "resources", "db", "migration", "V7__admin_seed.sql")));

        assertThat(inReadme.find()).isTrue();
        assertThat(inMigration.find()).isTrue();
        assertThat(inReadme.group(1)).isEqualTo(inMigration.group(1));
    }
}
