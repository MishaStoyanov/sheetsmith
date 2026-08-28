package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.domain.dto.price.*;
import com.ap0stole.sheetsmith.domain.entity.ModelPrice;
import com.ap0stole.sheetsmith.domain.exception.ApiException;
import com.ap0stole.sheetsmith.domain.exception.ErrorCode;
import com.ap0stole.sheetsmith.repository.LlmUsageRepository;
import com.ap0stole.sheetsmith.repository.ModelPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** The price list, which belongs entirely to whoever runs the instance. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelPriceService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ModelPriceRepository prices;
    private final LlmUsageRepository usage;

    public Page<ModelPriceDto> search(PriceSearchRequest request) {
        int page = request.page() == null ? 0 : Math.max(0, request.page());
        int size = request.size() == null ? 50 : Math.clamp(request.size(), 1, MAX_PAGE_SIZE);
        String keyword = request.keyword() == null ? "" : request.keyword().trim();

        return prices.search(keyword, PageRequest.of(page, size, Sort.by("provider", "model")))
                .map(price -> ModelPriceDto.from(price, callsPricedBy(price)));
    }

    /**
     * PUT: set the price for a model, creating the row if nobody has priced it yet.
     * <p>
     * An upsert rather than a separate create because the key is natural — provider plus model —
     * so "put this price at this address" is exactly the operation, and a POST would only add a
     * second way to say it.
     */
    @Transactional
    /**
     * Writing a price is the same act as deleting one: both change what every past call cost, on
     * every chart and against every spend limit, for everybody. It was open to any signed-in caller
     * while the delete beside it was the superadmin's — half a door.
     */
    @PreAuthorize("@authz.superadmin()")
    public ModelPriceDto upsert(UpsertPriceRequest request) {
        String provider = request.provider().trim().toUpperCase();
        String model = request.model().trim();

        ModelPrice price = prices.findByProviderAndModel(provider, model).orElseGet(ModelPrice::new);
        price.setProvider(provider);
        price.setModel(model);
        price.setInputPerMillion(request.inputPerMillion());
        price.setOutputPerMillion(request.outputPerMillion());
        price.setUpdatedAt(LocalDateTime.now());

        ModelPrice saved = prices.save(price);
        return ModelPriceDto.from(saved, callsPricedBy(saved));
    }

    @Transactional
    @PreAuthorize("@authz.superadmin()")
    public ModelPriceDto update(Long id, PatchPriceRequest request) {
        ModelPrice price = require(id);

        if (request.inputPerMillion() != null) {
            price.setInputPerMillion(request.inputPerMillion());
        }
        if (request.outputPerMillion() != null) {
            price.setOutputPerMillion(request.outputPerMillion());
        }
        price.setUpdatedAt(LocalDateTime.now());

        return ModelPriceDto.from(prices.save(price), callsPricedBy(price));
    }

    /**
     * Refuses to remove a price the recorded calls depend on, unless told again.
     * <p>
     * The check lives here rather than in the interface, and that is the whole point of the item: a
     * warning drawn in a dialog is bypassed by anything that is not that dialog — curl, a script,
     * somebody else's client. A refusal carrying the number works for all of them.
     * <p>
     * The wording matters too. Nothing is lost: the runs keep their tokens, and only the money
     * column stops having a meaning for those calls. Saying "data will be deleted" would frighten
     * more than the truth deserves.
     * <p>
     * The superadmin's alone, like every other deletion here. Entering and correcting prices stays
     * open to administrators: those can be put back, and a wrong price is visible in the figures it
     * produces. A removed one takes the meaning of every call that used it with it.
     */
    @PreAuthorize("@authz.superadmin()")
    @Transactional
    public void delete(Long id, boolean confirmed) {
        ModelPrice price = require(id);
        long affected = callsPricedBy(price);

        if (affected > 0 && !confirmed) {
            throw new ApiException(ErrorCode.PRICE_IN_USE, affected + " recorded "
                    + (affected == 1 ? "call was" : "calls were") + " made on " + price.getModel()
                    + ". Removing its price does not delete them — their tokens stay — but their cost "
                    + "stops being known, and any chart of spend will change to match. Confirm to remove it.");
        }

        prices.delete(price);
        log.info("Removed the price for {}/{} ({} recorded calls affected)",
                price.getProvider(), price.getModel(), affected);
    }

    /**
     * How many recorded calls this price applies to. Counted rather than joined, because the answer
     * is only ever a number and the alternative is loading a table to measure it.
     */
    private long callsPricedBy(ModelPrice price) {
        return usage.countByProviderAndModel(price.getProvider(), price.getModel());
    }

    private ModelPrice require(Long id) {
        return prices.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PRICE_NOT_FOUND, "No price with id " + id));
    }
}
