package com.example.ecapi.repository;

import com.example.ecapi.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("""
            SELECT p FROM Product p
            WHERE (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
            """)
    Page<Product> search(@Param("q") String q,
                         @Param("categoryId") Long categoryId,
                         Pageable pageable);

    /**
     * Atomically decrements stock only if enough is available. DB-level guard against
     * overselling under concurrency (avoids the lost-update race in read-check-write).
     * Returns rows affected: 1 = decremented, 0 = insufficient stock.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Product p SET p.stock = p.stock - :qty WHERE p.id = :id AND p.stock >= :qty")
    int decrementStock(@Param("id") Long id, @Param("qty") int qty);

    boolean existsByCategoryId(Long categoryId);
}
