package com.ap0stole.sheetsmith.schema;

import com.ap0stole.sheetsmith.domain.dto.price.ModelPriceDto;
import com.ap0stole.sheetsmith.domain.dto.price.PatchPriceRequest;
import com.ap0stole.sheetsmith.domain.dto.price.PriceSearchRequest;
import com.ap0stole.sheetsmith.domain.dto.price.UpsertPriceRequest;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.llm.LlmEngine;
import com.ap0stole.sheetsmith.llm.TokenUsage;
import com.ap0stole.sheetsmith.services.ModelPriceService;
import com.ap0stole.sheetsmith.services.UsageRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The price list, and the refusal that protects the numbers built on it.
 */
@SpringBootTest
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class ModelPriceTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ModelPriceService prices;

    @Autowired
    private UsageRecorder recorder;

    @BeforeEach
    void clear() {
        jdbc.update("delete from llm_usage");
        jdbc.update("delete from model_prices");
    }

    private ModelPriceDto price(String provider, String model, String in, String out) {
        return prices.upsert(new UpsertPriceRequest(provider, model, new BigDecimal(in), new BigDecimal(out)));
    }

    @Test
    @DisplayName("the migration leaves the table empty, so nothing claims a price it was never told")
    void nothingIsSeeded() {
        // A price list shipped in the repository would be wrong within months while still producing
        // confident totals, and no test would catch that.
        assertThat(prices.search(new PriceSearchRequest(null, null, null)).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("PUT adds a model nobody had priced, and PUT again replaces it")
    void putIsAlsoTheWayToAdd() {
        ModelPriceDto added = price("openai", "gpt-4o", "2.50", "10.00");
        assertThat(added.provider()).isEqualTo("OPENAI");
        assertThat(added.inputPerMillion()).isEqualByComparingTo("2.50");

        ModelPriceDto replaced = price("OPENAI", "gpt-4o", "3.00", "12.00");
        assertThat(replaced.id()).isEqualTo(added.id());
        assertThat(prices.search(new PriceSearchRequest(null, null, null)).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("a model name is only unique within a vendor")
    void twoVendorsCanServeTheSameModel() {
        price("OPENAI", "llama3.1", "1.00", "2.00");
        price("OLLAMA", "llama3.1", "0.00", "0.00");

        assertThat(prices.search(new PriceSearchRequest("llama", null, null)).getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("PATCH leaves out what it did not mention")
    void patchTouchesOnlyWhatItNames() {
        ModelPriceDto added = price("OPENAI", "gpt-4o", "2.50", "10.00");

        ModelPriceDto patched = prices.update(added.id(), new PatchPriceRequest(new BigDecimal("2.00"), null));

        assertThat(patched.inputPerMillion()).isEqualByComparingTo("2.00");
        assertThat(patched.outputPerMillion()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("the search looks at the vendor as well as the model")
    void searchMatchesEitherColumn() {
        price("OPENAI", "gpt-4o", "2.50", "10.00");
        price("OLLAMA", "gemma4:12b", "0.00", "0.00");

        assertThat(prices.search(new PriceSearchRequest("olla", null, null)).getTotalElements()).isEqualTo(1);
        assertThat(prices.search(new PriceSearchRequest("gpt", null, null)).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unused price goes without ceremony")
    void deletingAnUnusedPriceJustWorks() {
        ModelPriceDto added = price("OPENAI", "gpt-4o", "2.50", "10.00");

        prices.delete(added.id(), false);

        assertThat(prices.search(new PriceSearchRequest(null, null, null)).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("a price the recorded calls depend on is refused, with the count")
    void deletingAUsedPriceIsRefusedFirst() {
        ModelPriceDto added = price("OPENAI", "gpt-4o", "2.50", "10.00");
        recorder.chat("s1", null, "tidy it", new TokenUsage(100L, 10L, 110L),
                new LlmEngine("CLOUD", "OPENAI", "gpt-4o"), LocalDateTime.now());
        recorder.chat("s2", null, "again", new TokenUsage(100L, 10L, 110L),
                new LlmEngine("CLOUD", "OPENAI", "gpt-4o"), LocalDateTime.now());

        // The guard is on the server, not in a dialog: a warning drawn in the interface is bypassed
        // by anything that is not the interface.
        var addedId = added.id();
        assertThatThrownBy(() -> prices.delete(addedId, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("2 recorded calls")
                .hasMessageContaining("does not delete them");

        assertThat(prices.search(new PriceSearchRequest(null, null, null)).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("confirming removes it, and the calls it priced are untouched")
    void confirmingGoesThroughWithoutLosingTheCalls() {
        ModelPriceDto added = price("OPENAI", "gpt-4o", "2.50", "10.00");
        recorder.chat("s1", null, "tidy it", new TokenUsage(100L, 10L, 110L),
                new LlmEngine("CLOUD", "OPENAI", "gpt-4o"), LocalDateTime.now());

        prices.delete(added.id(), true);

        assertThat(prices.search(new PriceSearchRequest(null, null, null)).getTotalElements()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from llm_usage", Integer.class))
                .as("only the price is gone; the record of the call and its tokens remain")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the row says how many calls it prices, so the screen can warn before the refusal")
    void theRowCarriesItsOwnUsageCount() {
        price("OPENAI", "gpt-4o", "2.50", "10.00");
        recorder.chat("s1", null, "tidy it", new TokenUsage(100L, 10L, 110L),
                new LlmEngine("CLOUD", "OPENAI", "gpt-4o"), LocalDateTime.now());

        ModelPriceDto row = prices.search(new PriceSearchRequest(null, null, null)).getContent().getFirst();

        assertThat(row.usedByCalls()).isEqualTo(1);
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
    }
}
