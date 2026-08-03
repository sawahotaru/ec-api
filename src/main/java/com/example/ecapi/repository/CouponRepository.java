package com.example.ecapi.repository;

import com.example.ecapi.domain.Coupon;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Claims one redemption, atomically.
     *
     * <p>The limit is enforced in the WHERE clause rather than by reading the count and
     * writing it back, for the same reason the stock hold is: two checkouts racing on the
     * last redemption would both read "99 of 100" and both write 100. Here one of them
     * updates zero rows and is told the code is used up.
     *
     * @return 1 if a redemption was claimed, 0 if the code is exhausted or gone
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Coupon c SET c.redeemedCount = c.redeemedCount + 1
            WHERE c.id = :id
              AND (c.maxRedemptions IS NULL OR c.redeemedCount < c.maxRedemptions)
            """)
    int redeem(@Param("id") Long id);

    /**
     * Gives a redemption back — the order it was claimed for was cancelled or expired
     * unpaid, so it never became a sale.
     *
     * <p>Guarded at zero so a double release (e.g. cancel after expiry) cannot drive the
     * count negative and hand out free redemptions.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Coupon c SET c.redeemedCount = c.redeemedCount - 1
            WHERE c.code = :code AND c.redeemedCount > 0
            """)
    int release(@Param("code") String code);
}
