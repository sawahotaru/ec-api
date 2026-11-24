package com.example.ecapi.repository;

import com.example.ecapi.domain.Order;
import com.example.ecapi.domain.OrderStatus;
import com.example.ecapi.stats.StatsDtos.CouponUsage;
import com.example.ecapi.stats.StatsDtos.ProductSales;
import com.example.ecapi.stats.StatsDtos.ProviderSales;
import com.example.ecapi.stats.StatsDtos.StatusBreakdown;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findByUserId(Long userId, Pageable pageable);

    Optional<Order> findByIdAndUserId(Long id, Long userId);

    /** Guest order lookup: the token stands in for authentication. */
    Optional<Order> findByIdAndOrderToken(Long id, String orderToken);

    /** Orders in a given status placed before a cut-off — used to expire stale holds. */
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    /**
     * 行ロック付きで読む（{@code SELECT … FOR UPDATE}）。<strong>トランザクションの最初の読み込み</strong>として
     * 呼ぶこと（先に同じ注文を普通に読むと、永続化コンテキストの古い値が返りうる）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findWithLockById(@Param("id") Long id);

    /**
     * 支払いの開始で決済手段と参照を書く。<strong>PENDING で、参照が読んだときのまま</strong>のときだけ
     * 書く条件付きの更新（読んだ実体を書き戻さない＝取り消し・期限切れ・入金を PENDING で上書きしない）。
     *
     * @param previousReference 読んだときの参照。無ければ空文字
     * @return 1 なら書いた。0 なら、その間に状態か参照が変わった
     */
    @Modifying(flushAutomatically = true)
    @Query("update Order o set o.paymentProvider = :provider, o.paymentReference = :reference "
            + "where o.id = :id and o.status = com.example.ecapi.domain.OrderStatus.PENDING "
            + "and coalesce(o.paymentReference, '') = :previousReference")
    int updatePaymentIfPending(@Param("id") Long id, @Param("provider") String provider,
                               @Param("reference") String reference,
                               @Param("previousReference") String previousReference);

    /**
     * 本人確認とロックを 1 本のクエリで行う（会員）。<strong>トランザクションの最初の読み込み</strong>として呼ぶこと。
     * 他人の注文・無い注文は空になる。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.user.id = :userId")
    Optional<Order> findLockedByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 本人確認とロックを 1 本のクエリで行う（ゲスト。注文トークンが一致するときだけ）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.orderToken = :token")
    Optional<Order> findLockedByIdAndOrderToken(@Param("id") Long id, @Param("token") String token);

    /**
     * 期限を過ぎた PENDING（行ロック付き。取り消し・入金の確定と順番に処理される）。
     * 銀行振込を選んだ注文は {@code bankCutoff}、それ以外（カード・未選択）は
     * {@code cardCutoff} より前に作られたものだけ。ロック待ちの後に状態が変わっていることがあるので、
     * 呼び出し側は状態を見直す。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.status = com.example.ecapi.domain.OrderStatus.PENDING and ("
            + "(o.paymentProvider = 'bank_transfer' and o.createdAt < :bankCutoff) "
            + "or ((o.paymentProvider is null or o.paymentProvider <> 'bank_transfer') and o.createdAt < :cardCutoff))")
    List<Order> findStalePending(@Param("cardCutoff") Instant cardCutoff,
                                 @Param("bankCutoff") Instant bankCutoff);

    /** 会員の、振込を選んだ未払いの注文の件数（{@code exceptOrderId} の注文は数えない）。 */
    long countByUserIdAndStatusAndPaymentProviderAndIdNot(Long userId, OrderStatus status,
                                                          String paymentProvider, Long exceptOrderId);

    /** 振込を選んだ未払いの注文が、その商品を取り置いている数量の合計（{@code exceptOrderId} の注文は除く）。 */
    @Query("select coalesce(sum(i.quantity), 0) from OrderItem i "
            + "where i.product.id = :productId and i.order.id <> :exceptOrderId "
            + "and i.order.status = com.example.ecapi.domain.OrderStatus.PENDING "
            + "and i.order.paymentProvider = 'bank_transfer'")
    long sumBankTransferHeld(@Param("productId") Long productId, @Param("exceptOrderId") Long exceptOrderId);

    /** ゲストの連絡先がまだ残っている古い注文 — 保持期間を過ぎたら伏せる（公開デモ向け）。 */
    List<Order> findByGuestEmailIsNotNullAndCreatedAtBefore(Instant cutoff);

    /**
     * 一度も支払われないまま終わった古い注文。これだけは<strong>行ごと</strong>消してよい。
     *
     * <p>売上集計は「支払い済みだけ」を数えるので、EXPIRED / CANCELLED を消しても
     * 集計からは1円も消えない（PAID を消すと「30日より前の売上が無かったことになる」
     * という嘘を生むので、そちらは絶対に消さない）。
     *
     * <p>{@link Pageable} を取るのは一度に読む件数を縛るため。放置された環境では
     * これが数十万行になり得るので、掃除のほうがメモリを食って落ちては本末転倒。
     */
    Page<Order> findByStatusInAndCreatedAtBefore(List<OrderStatus> statuses, Instant cutoff, Pageable pageable);

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
