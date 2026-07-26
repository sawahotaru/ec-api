package com.example.ecapi.repository;

import com.example.ecapi.domain.TaxCategory;
import com.example.ecapi.domain.TaxRate;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaxRateRepository extends JpaRepository<TaxRate, Long> {

    /**
     * Rate(s) effective for a category on a given date, newest effectiveFrom first.
     * The caller takes the first. A date-ranged lookup (not "latest") is what makes
     * future-dated rate changes and historical accuracy work.
     */
    @Query("""
            SELECT t FROM TaxRate t
            WHERE t.category = :category
              AND t.effectiveFrom <= :date
              AND (t.effectiveTo IS NULL OR :date < t.effectiveTo)
            ORDER BY t.effectiveFrom DESC
            """)
    List<TaxRate> findEffective(@Param("category") TaxCategory category,
                               @Param("date") LocalDate date);

    List<TaxRate> findByCategoryOrderByEffectiveFromDesc(TaxCategory category);
}
