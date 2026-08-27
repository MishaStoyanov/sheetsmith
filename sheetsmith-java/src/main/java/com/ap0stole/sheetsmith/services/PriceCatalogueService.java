package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.domain.dto.price.CatalogueEntry;
import com.ap0stole.sheetsmith.domain.dto.price.PriceProposalDto;
import com.ap0stole.sheetsmith.domain.dto.price.UpsertPriceRequest;
import com.ap0stole.sheetsmith.domain.entity.ModelPrice;
import com.ap0stole.sheetsmith.repository.ModelPriceRepository;
import com.ap0stole.sheetsmith.services.catalogue.ModelCatalogue;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Published prices, compared against the ones stored here.
 * <p>
 * <strong>Nothing is written by looking.</strong> The comparison is a read: it fetches the
 * catalogue, works out what would change, and hands that back. Saving is a second, explicit call
 * carrying the rows a person picked. A price typed in by hand is never quietly replaced by one
 * found on the internet — this is a screen about money, and a number that changed on its own is a
 * number nobody can account for.
 * <p>
 * <strong>Only models this instance cares about.</strong> The catalogue lists hundreds; the rows
 * offered are the ones already priced here plus the ones actually recorded in use. Everything else
 * would be a list too long to read before confirming, which in practice means confirming without
 * reading.
 */
@Service
@RequiredArgsConstructor
public class PriceCatalogueService {

    private final ModelCatalogue catalogue;
    private final ModelPriceRepository prices;
    private final ModelPriceService priceService;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public PriceProposalDto preview() {
        List<CatalogueEntry> published = catalogue.fetch();

        Map<String, ModelPrice> stored = new LinkedHashMap<>();
        prices.findAll().forEach(price -> stored.put(key(price.getProvider(), price.getModel()), price));

        Map<String, Long> callsByModel = usedModels();

        // Everything worth asking about: what is priced, and what has been used. A model that is
        // both appears once.
        Set<String> interesting = new LinkedHashSet<>();
        interesting.addAll(stored.keySet());
        interesting.addAll(callsByModel.keySet());

        List<PriceProposalDto.Proposal> proposals = new ArrayList<>();
        for (String key : interesting) {
            String[] parts = key.split(" ", 2);
            proposals.add(compare(parts[0], parts[1], stored.get(key),
                    callsByModel.getOrDefault(key, 0L), published));
        }

        // Worth doing something about first, then alphabetical so the list does not reshuffle
        // between two refreshes of the same data.
        proposals.sort(Comparator
                .comparingInt((PriceProposalDto.Proposal p) -> p.status().ordinal())
                .thenComparing(PriceProposalDto.Proposal::provider)
                .thenComparing(PriceProposalDto.Proposal::model));

        return new PriceProposalDto(catalogue.source(), proposals);
    }

    /**
     * Saves the rows that were confirmed, and only those.
     * <p>
     * The figures come from the request rather than being fetched again: what somebody agreed to is
     * what is saved, even if the catalogue moved in the half-minute they spent reading it.
     */
    @Transactional
    public int apply(List<UpsertPriceRequest> accepted) {
        if (accepted == null || accepted.isEmpty()) {
            return 0;
        }
        // Through the same upsert a person typing a price by hand goes through, rather than
        // writing rows here: normalisation and the "last updated" stamp then live in one place, and
        // a price that arrived from a catalogue is indistinguishable afterwards from one that was
        // typed — which is correct, because from then on it is just a price.
        accepted.forEach(priceService::upsert);
        return accepted.size();
    }

    // ── The comparison ────────────────────────────────────────────────────────

    private PriceProposalDto.Proposal compare(String provider, String model, ModelPrice current,
                                              long calls, List<CatalogueEntry> published) {
        CatalogueEntry match = match(provider, model, published);

        if (match == null) {
            return new PriceProposalDto.Proposal(provider, model, null,
                    current == null ? null : current.getInputPerMillion(),
                    current == null ? null : current.getOutputPerMillion(),
                    null, null, calls, PriceProposalDto.Status.NOT_IN_CATALOGUE);
        }

        PriceProposalDto.Status status;
        if (current == null) {
            status = PriceProposalDto.Status.NEW;
        } else if (same(current.getInputPerMillion(), match.inputPerMillion())
                && same(current.getOutputPerMillion(), match.outputPerMillion())) {
            status = PriceProposalDto.Status.UNCHANGED;
        } else {
            status = PriceProposalDto.Status.CHANGED;
        }

        return new PriceProposalDto.Proposal(provider, model,
                match.model().equals(model) ? null : match.model(),
                current == null ? null : current.getInputPerMillion(),
                current == null ? null : current.getOutputPerMillion(),
                match.inputPerMillion(), match.outputPerMillion(), calls, status);
    }

    /**
     * The catalogue row for a model recorded here, if there is one.
     * <p>
     * Exactly first, then the longest catalogue name this model starts with. The prefix rule exists
     * because the two do not always agree: Anthropic's API answers as
     * {@code claude-sonnet-4-20250514} while the catalogue lists {@code claude-sonnet-4}, and a
     * dated snapshot of a model is that model at that model's price. Longest wins so
     * {@code claude-sonnet-4-5} is not matched by {@code claude-sonnet-4}.
     * <p>
     * A prefix match is reported as such in the proposal, so somebody can see it was not exact
     * before accepting it.
     */
    private CatalogueEntry match(String provider, String model, List<CatalogueEntry> published) {
        CatalogueEntry best = null;
        for (CatalogueEntry entry : published) {
            if (!entry.provider().equalsIgnoreCase(provider)) {
                continue;
            }
            if (entry.model().equalsIgnoreCase(model)) {
                return entry;
            }
            if (model.toLowerCase().startsWith(entry.model().toLowerCase() + "-")
                    && (best == null || entry.model().length() > best.model().length())) {
                best = entry;
            }
        }
        return best;
    }

    /** Models that have actually been called here, with how often. */
    private Map<String, Long> usedModels() {
        Map<String, Long> calls = new LinkedHashMap<>();
        jdbc.query("""
                select coalesce(provider, 'unknown') as provider,
                       coalesce(model, 'unknown')    as model,
                       count(*)                      as calls
                from llm_usage
                where provider is not null and model is not null
                group by provider, model
                """, rs -> {
            // Local models are left out on purpose: they charge nothing, so a price for one is not
            // a gap to be filled but a category error.
            if (!"OLLAMA".equalsIgnoreCase(rs.getString("provider"))) {
                calls.put(key(rs.getString("provider"), rs.getString("model")), rs.getLong("calls"));
            }
        });
        return calls;
    }

    /** Equal as amounts, not as objects: 2.5 and 2.5000 are the same price. */
    private static boolean same(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private static String key(String provider, String model) {
        return (provider == null ? "" : provider.toUpperCase()) + " " + (model == null ? "" : model);
    }
}
