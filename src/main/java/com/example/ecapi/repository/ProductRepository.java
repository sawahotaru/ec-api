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
     *
     * <p>2つめの条件は<strong>未払いの引当が在庫の一定割合を超えないこと</strong>。
     * 引当には支払いが要らないので、これが無いと誰でも無料で棚を空にできる
     * （公開カタログが {@code available} を正確に返すため、押さえる数も分かる）。
     * 判定を SQL 側に置いてあるのは1つめと同じ理由で、読んでから書くまでの間に
     * 割り込まれると上限が意味を失うため。{@code maxHoldPercent=100} なら
     * 1つめの条件と等価になり、この層は実質無効になる。
     *
     * <p>支払い確定（{@link #commitStock}）は stock と reserved を同時に減らすので、
     * 実際に売れても割合は動かない＝「売れたせいで買えない」は起きない。
     *
     * <p>⚠ 最後の {@code OR p.reserved + :qty <= 1} は<strong>在庫が極端に少ない商品を
     * 買えなくしないため</strong>。割合だけで判定すると stock=1 の商品は
     * {@code (0+1)*100=100 <= 1*50=50} が偽になり、<em>誰も一生注文できない</em>。
     * 「1点だけは必ず押さえられる」という下駄を履かせる。上限としての意味は失われない
     * （1点は割合に関わらずどのみち押さえられる量であり、棚を空にする力にはならない）。
     *
     * @return rows affected: 1 = reserved, 0 = 在庫不足 <em>または</em> 引当上限超過
     *         （どちらだったかは呼び出し側が available を見て区別する）
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Product p SET p.reserved = p.reserved + :qty "
            + "WHERE p.id = :id AND p.stock - p.reserved >= :qty "
            + "AND ((p.reserved + :qty) * 100 <= p.stock * :maxHoldPercent OR p.reserved + :qty <= 1)")
    int reserveStock(@Param("id") Long id, @Param("qty") int qty,
                     @Param("maxHoldPercent") int maxHoldPercent);

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
