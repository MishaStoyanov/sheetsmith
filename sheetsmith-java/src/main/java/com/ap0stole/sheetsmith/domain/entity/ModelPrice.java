package com.ap0stole.sheetsmith.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What one model costs, per million tokens, as the operator told us.
 * <p>
 * Nothing seeds this table. A price list in the repository would be wrong within months while still
 * producing confident totals, and no test would catch it — so the app knows only what it has been
 * told, and says so plainly where it has been told nothing.
 */
@Entity
@Table(name = "model_prices")
@Getter
@Setter
@NoArgsConstructor
public class ModelPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code OPENAI}, {@code OLLAMA}, … — a model name is only unique within a vendor. */
    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "input_per_million", nullable = false)
    private BigDecimal inputPerMillion;

    @Column(name = "output_per_million", nullable = false)
    private BigDecimal outputPerMillion;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
