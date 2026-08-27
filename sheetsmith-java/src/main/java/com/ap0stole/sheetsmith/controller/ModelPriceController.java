package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.price.*;
import com.ap0stole.sheetsmith.services.ModelPriceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
