package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.price.*;
import com.ap0stole.sheetsmith.services.ModelPriceService;
import com.ap0stole.sheetsmith.services.PriceCatalogueService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@Tag(name = "Prices", description = "What a million tokens costs, per provider and model. Read by anyone, written by the superadmin.")
@RestController
@RequestMapping("/api/prices")
@RequiredArgsConstructor
public class ModelPriceController {

    private final ModelPriceService priceService;
    private final PriceCatalogueService catalogueService;

    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Search the price list")
    @PostMapping("/search")
    public Page<ModelPriceDto> search(@RequestBody(required = false) PriceSearchRequest request) {
        return priceService.search(request == null ? new PriceSearchRequest(null, null, null) : request);
    }

    /** Sets the price for a model, adding it if nobody has priced it yet. */
    @PreAuthorize("@authz.superadmin()")
    @Operation(summary = "Set the price for a provider and model",
            description = "The key is natural (provider + model), so this creates as well as replaces.")
    @ApiResponse(responseCode = "400", description = "A price below zero, or a provider and model that are not both given.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @PutMapping
    public ModelPriceDto upsert(@RequestBody @Valid UpsertPriceRequest request) {
        return priceService.upsert(request);
    }

    @PreAuthorize("@authz.superadmin()")
    @Operation(summary = "Correct one price",
            description = "Only the two figures: provider and model are the row’s address, not its contents.")
    @ApiResponse(responseCode = "404", description = "No such price.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
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
    @PreAuthorize("@authz.superadmin()")
    @Operation(summary = "Compare against the public catalogue",
            description = "The only request this application makes to the outside world. It writes nothing; the answer is a was-and-would-be table.")
    @ApiResponse(responseCode = "502", description = "The public catalogue could not be reached. This is the one outbound request the application makes.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
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
    @PreAuthorize("@authz.superadmin()")
    @Operation(summary = "Save the rows somebody confirmed",
            description = "Saves the figures from the request rather than fetching them again, so what is stored is what was agreed to. Confirming an unchanged row is how a price gets marked as checked.")
    @PostMapping("/catalogue/apply")
    public Map<String, Integer> applyCatalogue(@RequestBody @Valid List<UpsertPriceRequest> accepted) {
        return Map.of("saved", catalogueService.apply(accepted));
    }

    /**
     * Removes a price. Without {@code confirm}, a price that recorded calls depend on is refused
     * with the number of them — the guard is here rather than in the interface, so a script gets it
     * too.
     */
    @PreAuthorize("@authz.superadmin()")
    @Operation(summary = "Remove a price",
            description = "Refused without confirm=true when calls were priced by it, and the refusal carries how many: their spend stops being countable, and charts already drawn change.")
    @ApiResponse(responseCode = "404", description = "No such price.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @ApiResponse(responseCode = "409", description = "Calls were priced by this row. The refusal carries how many: without it their spend stops being countable and charts already drawn change. Repeat with confirm=true to go ahead.",
            content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestParam(defaultValue = "false") boolean confirm) {
        priceService.delete(id, confirm);
        return ResponseEntity.noContent().build();
    }
}
