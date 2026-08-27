package com.ap0stole.sheetsmith.services.catalogue;

import com.ap0stole.sheetsmith.domain.dto.price.CatalogueEntry;

import java.util.List;

/**
 * Somewhere to read published prices from.
 * <p>
 * An interface with one implementation, and not for the usual reason. Reaching outside is the part
 * of this feature that can fail in ways nothing else here can — a catalogue that moves, renames its
 * fields, or simply cannot be reached — so the tests have to be able to hand the comparison a fixed
 * answer instead of the internet.
 */
public interface ModelCatalogue {

    /** Every model the catalogue knows a price for. Throws rather than returning empty on failure. */
    List<CatalogueEntry> fetch();

    /** Named in the interface it appears on the screen, so the outbound host is never a surprise. */
    String source();
}
