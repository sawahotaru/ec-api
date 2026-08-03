package com.example.ecapi.stats;

import java.math.BigDecimal;
import java.util.List;

/**
 * 売上集計の返り値。すべて<strong>注文時スナップショット</strong>から積み上げる。
 *
 * <p>商品名も単価も税額も注文明細に凍結されているので、あとから商品名を変えても価格を
 * 変えても、過去の集計は動かない。カタログを join して集計すると、値上げしただけで
 * 去年の売上が変わるという事故が起きる。
 */
public final class StatsDtos {

    private StatsDtos() {
    }

    /** 状態ごとの件数と金額。売上に数えない状態（PENDING/CANCELLED/EXPIRED）も返す。 */
    public record StatusBreakdown(String status, long orders, BigDecimal amount) {
    }

    /** 月ごとの売上と件数。注文の無い月も 0 で埋めて返す。 */
    public record MonthlyPoint(String month, long orders, BigDecimal revenue) {
    }

    /** 商品ごとの販売数と売上（明細のスナップショット基準・割引反映後）。 */
    public record ProductSales(String productName, long quantity, BigDecimal revenue) {
    }

    /** 決済手段ごとの件数と売上。手動で PAID にした注文は provider が無いので「手動」に寄る。 */
    public record ProviderSales(String provider, long orders, BigDecimal revenue) {
    }

    /** クーポンごとの利用回数と割引額。B7 の効果測定はこれが起点になる。 */
    public record CouponUsage(String code, long orders, BigDecimal discount) {
    }

    /**
     * 管理画面に出す一式。
     *
     * @param revenue        売上合計（税込・PAID 以降のみ）
     * @param paidOrders     売上として数えた注文件数
     * @param averageOrder   平均注文単価（件数0なら0）
     * @param pendingOrders  未払いのまま残っている注文
     * @param lostOrders     失効・キャンセルされた注文（機会損失）
     * @param conversionRate 「作られた注文のうち売上になった割合」（％・小数1桁）
     */
    public record StoreStats(
            BigDecimal revenue,
            long paidOrders,
            BigDecimal averageOrder,
            long pendingOrders,
            long lostOrders,
            BigDecimal conversionRate,
            BigDecimal discountTotal,
            BigDecimal shippingTotal,
            List<MonthlyPoint> monthly,
            List<StatusBreakdown> statuses,
            List<ProductSales> products,
            List<ProviderSales> providers,
            List<CouponUsage> coupons) {
    }
}
