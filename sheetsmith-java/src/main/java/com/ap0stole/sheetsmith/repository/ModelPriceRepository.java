package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.ModelPrice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ModelPriceRepository extends JpaRepository<ModelPrice, Long> {

    Optional<ModelPrice> findByProviderAndModel(String provider, String model);

    /** An empty keyword matches everything; it is never null, for the reason UserRepository says. */
    @Query("""
            select p from ModelPrice p
            where lower(p.model) like lower(concat('%', :keyword, '%'))
               or lower(p.provider) like lower(concat('%', :keyword, '%'))
            """)
    Page<ModelPrice> search(@Param("keyword") String keyword, Pageable pageable);
}
