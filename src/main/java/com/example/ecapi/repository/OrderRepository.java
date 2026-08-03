package com.example.ecapi.repository;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.stats.StatsDtos.CouponUsage;
import com.example.ecapi.stats.StatsDtos.ProductSales;
import com.example.ecapi.stats.StatsDtos.ProviderSales;
import com.example.ecapi.stats.StatsDtos.StatusBreakdown;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findByUserId(Long userId, Pageable pageable);

    Optional<Order> findByIdAndUserId(Long id, Long userId);

    /** Guest order lookup: the token stands in for authentication. */
    Optional<Order> findByIdAndOrderToken(Long id, String orderToken);

    /** Orders in a given status placed before a cut-off — used to expire stale holds. */
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    /* ---------- 集計（管理画面の売上統計） ----------
       いずれも DB 側で集約する。件数が増えても定数メモリで済むうえ、
       「全注文を読んで Java で足す」実装は本番だけ遅くなる類の問題を持ち込むため。
       JPQL に閉じてあるので H2（テスト）でも PostgreSQL（本番）でも同じ文が動く。 */

    @Query("SELECT new com.example.ecapi.stats.StatsDtos$StatusBreakdown("
            + "CAST(o.status AS string), COUNT(o), COALESCE(SUM(o.totalAmount), 0)) "
            + "FROM Order o GROUP BY o.status ORDER BY o.status")
    List<StatusBreakdown> statusBreakdown();

    @Query("SELECT new com.example.ecapi.stats.StatsDtos$ProviderSales("
            + "COALESCE(o.paymentProvider, 'manual'), COUNT(o), COALESCE(SUM(o.totalAmount), 0)) "
            + "FROM Order o WHERE o.status IN :statuses "
            + "GROUP BY o.paymentProvider ORDER BY SUM(o.totalAmount) DESC")
    List<ProviderSales> revenueByProvider(@Param("statuses") List<OrderStatus> statuses);

    @Query("SELECT new com.example.ecapi.stats.StatsDtos$CouponUsage("
            + "o.couponCode, COUNT(o), COALESCE(SUM(o.discountAmount), 0)) "
            + "FROM Order o WHERE o.couponCode IS NOT NULL AND o.status IN :statuses "
            + "GROUP BY o.couponCode ORDER BY COUNT(o) DESC")
    List<CouponUsage> couponUsage(@Param("statuses") List<OrderStatus> statuses);

    /**
     * 商品別の販売数と売上。カタログではなく<strong>明細のスナップショット</strong>から積む
     * （商品名を変えても価格を変えても過去の集計が動かない）。売上は
     * 「単価×数量 − その明細が負担した割引」で、注文合計と同じ土俵に乗せる。
     */
    @Query("SELECT new com.example.ecapi.stats.StatsDtos$ProductSales("
            + "i.productName, SUM(i.quantity), "
            + "COALESCE(SUM(i.unitPrice * i.quantity - i.discountAmount), 0)) "
            + "FROM OrderItem i WHERE i.order.status IN :statuses "
            + "GROUP BY i.productName ORDER BY SUM(i.quantity) DESC")
    List<ProductSales> productSales(@Param("statuses") List<OrderStatus> statuses);

    /**
     * 月別集計のもと。月の切り出しはDB関数が方言（PostgreSQL の to_char / H2 の formatdatetime）
     * なので、<strong>ここでは月ごとに畳まず</strong>「日時と金額の2列だけ」を取り、
     * バケット分けは Java 側で行う。方言を持ち込んで本番だけ動く SQL を作るより安全で、
     * 読む量も「期間内の注文数 × 2列」に収まる。
     */
    @Query("SELECT o.createdAt, o.totalAmount FROM Order o "
            + "WHERE o.status IN :statuses AND o.createdAt >= :from ORDER BY o.createdAt")
    List<Object[]> revenueTimeline(@Param("statuses") List<OrderStatus> statuses,
                                   @Param("from") Instant from);

    @Query("SELECT COALESCE(SUM(o.discountAmount), 0) FROM Order o WHERE o.status IN :statuses")
    BigDecimal totalDiscount(@Param("statuses") List<OrderStatus> statuses);

    @Query("SELECT COALESCE(SUM(o.shippingAmount), 0) FROM Order o WHERE o.status IN :statuses")
    BigDecimal totalShipping(@Param("statuses") List<OrderStatus> statuses);
}
