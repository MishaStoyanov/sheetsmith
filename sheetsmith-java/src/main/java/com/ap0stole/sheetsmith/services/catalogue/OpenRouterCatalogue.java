package com.ap0stole.sheetsmith.services.catalogue;

import com.ap0stole.sheetsmith.domain.dto.price.CatalogueEntry;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Published prices, read from OpenRouter's open model catalogue.
 * <p>
 * <strong>Why not the providers themselves:</strong> none of them publish one. OpenAI, Anthropic
 * and Google put their prices on documentation pages meant for people, and scraping those is
 * signing up to repair a parser after every redesign. OpenRouter keeps the same information as JSON,
 * needs no key, and lists all of them in one place — so it is the one source here that is
 * machine-readable and does not invent anything.
 * <p>
 * <strong>Why not ask a model:</strong> it would cost almost nothing and answer instantly, and it
 * would also state a confident price for a model whose price it does not know. This screen is about
 * money. A number that is wrong in the right format is worse here than no number.
 * <p>
 * Nothing is written from this. The catalogue is compared against what is stored and the difference
 * is shown; only what a person confirms is saved.
 */
@Slf4j
@Component
public class OpenRouterCatalogue implements ModelCatalogue {

    private static final String URL = "https://openrouter.ai/api/v1/models";

    /**
     * Catalogue vendor prefixes mapped to the provider names this instance records.
     * <p>
     * Deliberately not everything the catalogue carries: a price is only useful here for a provider
     * this application can actually call, and the rest would be several hundred rows of noise in a
     * list somebody has to read before confirming.
     */
    private static final Map<String, String> VENDORS = Map.of(
            "openai", "OPENAI",
            "anthropic", "ANTHROPIC",
            "google", "GEMINI");

    /** Prices arrive per token, and per token they are unreadable — six decimal places of zero. */
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final RestClient restClient = RestClient.builder()
            .requestFactory(factory())
            .build();

    private static org.springframework.http.client.ClientHttpRequestFactory factory() {
        var settings = org.springframework.boot.http.client.ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(15));
        return org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect().build(settings);
    }

    @Override
    public String source() {
        return "openrouter.ai";
    }

    @Override
    public List<CatalogueEntry> fetch() {
        JsonNode response;
        try {
            response = restClient.get().uri(URL).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Could not read the model catalogue at {}: {}", URL, e.getMessage());
            throw new ApiException(ErrorCode.CATALOGUE_UNREACHABLE,
                    "Could not reach " + source() + " to read published prices: " + e.getMessage());
        }

        if (response == null || !response.has("data")) {
            throw new ApiException(ErrorCode.CATALOGUE_UNREACHABLE,
                    "The catalogue at " + source() + " answered in a shape this version does not understand.");
        }

        List<CatalogueEntry> entries = new ArrayList<>();
        for (JsonNode model : response.get("data")) {
            CatalogueEntry entry = read(model);
            if (entry != null) {
                entries.add(entry);
            }
        }

        if (entries.isEmpty()) {
            throw new ApiException(ErrorCode.CATALOGUE_UNREACHABLE,
                    "The catalogue at " + source() + " listed no models this instance can use.");
        }
        return entries;
    }

    /** One catalogue row, or null where it is not something this instance could ever call. */
    private CatalogueEntry read(JsonNode model) {
        String id = model.path("id").asText(null);
        if (id == null || !id.contains("/")) {
            return null;
        }

        String provider = VENDORS.get(id.substring(0, id.indexOf('/')).toLowerCase());
        if (provider == null) {
            return null;
        }

        JsonNode pricing = model.path("pricing");
        BigDecimal input = perMillion(pricing.path("prompt").asText(null));
        BigDecimal output = perMillion(pricing.path("completion").asText(null));
        if (input == null || output == null) {
            return null;
        }

        return new CatalogueEntry(provider, id.substring(id.indexOf('/') + 1), input, output);
    }

    /**
     * A per-token price as a per-million one.
     * <p>
     * Free models list "0", and they are skipped rather than saved: a row saying a model costs
     * nothing is indistinguishable on the analytics screen from a model that was never priced, and
     * the second is the honest reading of a free tier that can end.
     */
    private BigDecimal perMillion(String perToken) {
        if (perToken == null || perToken.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(perToken);
            if (value.signum() <= 0) {
                return null;
            }
            return value.multiply(MILLION).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
