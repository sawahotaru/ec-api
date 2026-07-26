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
     * Atomically holds stock for a pending order: only succeeds if enough is still
     * sellable ({@code stock - reserved >= qty}). DB-level guard against overselling
     * under concurrency (avoids the lost-update race in read-check-write).
     * Returns rows affected: 1 = reserved, 0 = insufficient available stock.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Product p SET p.reserved = p.reserved + :qty "
            + "WHERE p.id = :id AND p.stock - p.reserved >= :qty")
    int reserveStock(@Param("id") Long id, @Param("qty") int qty);

    /**
     * Converts a hold into a real decrement on payment: removes the units from both
     * stock and reserved. Guarded so it can never drive either below zero even if
     * called twice for the same order. Returns 1 on success, 0 if the guard fails.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Product p SET p.stock = p.stock - :qty, p.reserved = p.reserved - :qty "
            + "WHERE p.id = :id AND p.reserved >= :qty AND p.stock >= :qty")
    int commitStock(@Param("id") Long id, @Param("qty") int qty);

    /**
     * Releases a hold (order cancelled or expired) back to sellable stock. Guarded
     * against dropping reserved below zero so a double-release is a harmless no-op.
     * Returns 1 on success, 0 if there was nothing (enough) to release.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Product p SET p.reserved = p.reserved - :qty "
            + "WHERE p.id = :id AND p.reserved >= :qty")
    int releaseStock(@Param("id") Long id, @Param("qty") int qty);

    boolean existsByCategoryId(Long categoryId);
}
