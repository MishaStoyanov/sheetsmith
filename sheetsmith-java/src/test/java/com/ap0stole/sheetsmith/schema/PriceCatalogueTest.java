package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.domain.dto.price.CatalogueEntry;
import com.ap0stole.sheetsmith.domain.dto.price.PriceProposalDto;
import com.ap0stole.sheetsmith.domain.dto.price.PriceProposalDto.Status;
import com.ap0stole.sheetsmith.domain.dto.price.UpsertPriceRequest;
import com.ap0stole.sheetsmith.services.ModelPriceService;
import com.ap0stole.sheetsmith.services.PriceCatalogueService;
import com.ap0stole.sheetsmith.services.catalogue.ModelCatalogue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comparison between what a catalogue publishes and what this instance stores.
 * <p>
 * The catalogue itself is stubbed rather than fetched. Reaching outside is the one part of this
 * feature that can fail on its own schedule, and a test that depends on a live third party is a
 * test that fails for reasons that have nothing to do with the change being made.
 */
@SpringBootTest
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class PriceCatalogueTest {

    /** What the outside world says, fixed. */
    static final List<CatalogueEntry> PUBLISHED = List.of(
            new CatalogueEntry("OPENAI", "gpt-4o", new BigDecimal("2.5000"), new BigDecimal("10.0000")),
            new CatalogueEntry("CLAUDE", "claude-sonnet-4", new BigDecimal("3.0000"), new BigDecimal("15.0000")),
            new CatalogueEntry("CLAUDE", "claude-sonnet-4-5", new BigDecimal("4.0000"), new BigDecimal("20.0000")),
            new CatalogueEntry("GEMINI", "gemini-3.7-flash", new BigDecimal("0.3000"), new BigDecimal("2.5000")));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PriceCatalogueService catalogue;

    @Autowired
    private ModelPriceService prices;

    @BeforeEach
    void clean() {
        jdbc.update("delete from llm_usage");
        jdbc.update("delete from model_prices");
    }

    private void called(String provider, String model) {
        jdbc.update("""
                insert into llm_usage (kind, prompt, total_tokens, provider_mode, provider, model,
                        input_per_million, output_per_million, started_at, finished_at)
                select 'CHAT', 'x', 10, 'CLOUD', ?, ?,
                       p.input_per_million, p.output_per_million, now(), now()
                from (select 1) as one
                left join model_prices p on upper(p.provider) = upper(?) and p.model = ?
                """, provider, model, provider, model);
    }

    private PriceProposalDto.Proposal find(String model) {
        return catalogue.preview().proposals().stream()
                .filter(p -> p.model().equals(model))
                .findFirst().orElseThrow(() -> new AssertionError("no proposal for " + model));
    }

    @Test
    @DisplayName("a model used here and never priced is offered as new")
    void usedButUnpricedIsOffered() {
        called("OPENAI", "gpt-4o");

        PriceProposalDto.Proposal proposal = find("gpt-4o");

        assertThat(proposal.status()).isEqualTo(Status.NEW);
        assertThat(proposal.currentInputPerMillion()).isNull();
        assertThat(proposal.proposedInputPerMillion()).isEqualByComparingTo("2.5000");
        assertThat(proposal.usedByCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a price that already agrees with the catalogue is shown as checked, not hidden")
    void agreementIsStillReported() {
        // A refresh listing only differences leaves somebody wondering whether the rest were
        // checked or quietly skipped.
        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("2.50"), new BigDecimal("10.00")));

        assertThat(find("gpt-4o").status()).isEqualTo(Status.UNCHANGED);
    }

    @Test
    @DisplayName("a disagreement carries both numbers so the change can be read before it is taken")
    void changesCarryBothSides() {
        prices.upsert(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("5.00"), new BigDecimal("20.00")));

        PriceProposalDto.Proposal proposal = find("gpt-4o");

        assertThat(proposal.status()).isEqualTo(Status.CHANGED);
        assertThat(proposal.currentInputPerMillion()).isEqualByComparingTo("5.00");
        assertThat(proposal.proposedInputPerMillion()).isEqualByComparingTo("2.5000");
    }

    @Test
    @DisplayName("a dated model snapshot matches the model it is a snapshot of")
    void datedSnapshotsMatchByPrefix() {
        // Anthropic answers as claude-sonnet-4-20250514 while catalogues list claude-sonnet-4.
        called("CLAUDE", "claude-sonnet-4-20250514");

        PriceProposalDto.Proposal proposal = find("claude-sonnet-4-20250514");

        assertThat(proposal.status()).isEqualTo(Status.NEW);
        assertThat(proposal.proposedInputPerMillion()).isEqualByComparingTo("3.0000");
        assertThat(proposal.catalogueModel())
                .as("an inexact match is named, so it can be checked by eye before being accepted")
                .isEqualTo("claude-sonnet-4");
    }

    @Test
    @DisplayName("the longest matching name wins, so a newer model is not priced as an older one")
    void longestPrefixWins() {
        called("CLAUDE", "claude-sonnet-4-5-20260101");

        assertThat(find("claude-sonnet-4-5-20260101").proposedInputPerMillion())
                .as("claude-sonnet-4 must not swallow claude-sonnet-4-5")
                .isEqualByComparingTo("4.0000");
    }

    @Test
    @DisplayName("an exact name is never overridden by a prefix")
    void exactBeatsPrefix() {
        called("CLAUDE", "claude-sonnet-4");

        assertThat(find("claude-sonnet-4").proposedInputPerMillion()).isEqualByComparingTo("3.0000");
    }

    @Test
    @DisplayName("a model the catalogue has never heard of is named rather than silently dropped")
    void unknownModelsAreReported() {
        called("OPENAI", "some-internal-preview");

        PriceProposalDto.Proposal proposal = find("some-internal-preview");

        assertThat(proposal.status()).isEqualTo(Status.NOT_IN_CATALOGUE);
        assertThat(proposal.proposedInputPerMillion()).isNull();
    }

    @Test
    @DisplayName("local models are left out entirely — free is not an unfilled price")
    void localModelsAreNotOffered() {
        called("OLLAMA", "gemma4:12b");

        assertThat(catalogue.preview().proposals()).isEmpty();
    }

    @Test
    @DisplayName("looking changes nothing")
    void previewNeverWrites() {
        called("OPENAI", "gpt-4o");
        prices.upsert(new UpsertPriceRequest("GEMINI", "gemini-3.7-flash",
                new BigDecimal("99.00"), new BigDecimal("99.00")));

        catalogue.preview();
        catalogue.preview();

        assertThat(jdbc.queryForObject("select count(*) from model_prices", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select input_per_million from model_prices where model = 'gemini-3.7-flash'", BigDecimal.class))
                .as("a price somebody typed is never replaced by one found on the internet")
                .isEqualByComparingTo("99.00");
    }

    @Test
    @DisplayName("only what was accepted is saved")
    void applySavesExactlyWhatItWasGiven() {
        called("OPENAI", "gpt-4o");
        called("GEMINI", "gemini-3.7-flash");

        int saved = catalogue.apply(List.of(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("2.5000"), new BigDecimal("10.0000"))));

        assertThat(saved).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from model_prices", Long.class))
                .as("the model that was not accepted stays unpriced")
                .isEqualTo(1);
        assertThat(find("gpt-4o").status()).isEqualTo(Status.UNCHANGED);
    }

    @Test
    @DisplayName("what is saved is what was agreed to, not what the catalogue says now")
    void applyTrustsTheRequestRatherThanRefetching() {
        called("OPENAI", "gpt-4o");

        catalogue.apply(List.of(new UpsertPriceRequest("OPENAI", "gpt-4o",
                new BigDecimal("1.2345"), new BigDecimal("6.7890"))));

        assertThat(jdbc.queryForObject(
                "select input_per_million from model_prices where model = 'gpt-4o'", BigDecimal.class))
                .isEqualByComparingTo("1.2345");
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable _) {
            return false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }

        /** The internet, replaced by a constant. */
        @Bean
        @Primary
        ModelCatalogue stubCatalogue() {
            return new ModelCatalogue() {
                @Override
                public List<CatalogueEntry> fetch() {
                    return PUBLISHED;
                }

                @Override
                public String source() {
                    return "test-catalogue";
                }
            };
        }
    }
}
