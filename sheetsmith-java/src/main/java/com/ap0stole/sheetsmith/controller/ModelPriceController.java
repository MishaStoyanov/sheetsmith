package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.price.*;
import com.ap0stole.sheetsmith.services.ModelPriceService;
import com.ap0stole.sheetsmith.services.PriceCatalogueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The price list: four endpoints, and a migration that creates the table without filling it.
 * <p>
 * There is no separate "create": the key is natural — provider plus model — so {@code PUT} is
 * already the operation for putting a price at a known address, and a fifth endpoint would only be
 * a second way to say the same thing.
 */
@RestController
@RequestMapping("/api/prices")
@RequiredArgsConstructor
public class ModelPriceController {

    private final ModelPriceService priceService;
    private final PriceCatalogueService catalogueService;

    @PostMapping("/search")
    public Page<ModelPriceDto> search(@RequestBody(required = false) PriceSearchRequest request) {
        return priceService.search(request == null ? new PriceSearchRequest(null, null, null) : request);
    }

    /** Sets the price for a model, adding it if nobody has priced it yet. */
    @PutMapping
    public ModelPriceDto upsert(@RequestBody @Valid UpsertPriceRequest request) {
        return priceService.upsert(request);
    }

    @PatchMapping("/{id}")
    public ModelPriceDto update(@PathVariable Long id, @RequestBody @Valid PatchPriceRequest request) {
        return priceService.update(id, request);
    }

    /**
     * What a published catalogue would change here, and nothing else.
     * <p>
     * A read, despite the POST — it reaches outside this machine, which is not something to put
     * behind a URL a browser may prefetch or a proxy may cache. Nothing is written: the answer is a
     * comparison, and saving is the separate call below carrying the rows somebody picked.
     */
    @PostMapping("/catalogue/preview")
    public PriceProposalDto preview() {
        return catalogueService.preview();
    }

    /**
     * Saves the proposals that were accepted.
     * <p>
     * The figures are taken from the request rather than fetched again, so what was agreed to is
     * what is saved even if the catalogue moved while it was being read.
     */
    @PostMapping("/catalogue/apply")
    public Map<String, Integer> applyCatalogue(@RequestBody @Valid List<UpsertPriceRequest> accepted) {
        return Map.of("saved", catalogueService.apply(accepted));
    }

    /**
     * Removes a price. Without {@code confirm}, a price that recorded calls depend on is refused
     * with the number of them — the guard is here rather than in the interface, so a script gets it
     * too.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestParam(defaultValue = "false") boolean confirm) {
        priceService.delete(id, confirm);
        return ResponseEntity.noContent().build();
    }
}
