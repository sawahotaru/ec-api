package com.example.ecapi.payment;

import com.example.ecapi.domain.Order;
import java.util.Map;
import java.util.Optional;

/**
 * 決済手段の契約（SPI）。実装を {@code @Component} として置けば
 * {@link PaymentProviderRegistry} が自動的に拾い、{@code /api/payments/{providerId}/...}
 * として公開される。コアのコード変更は不要。
 *
 * <p>この抽象を切った動機は「将来PayPayを足すかも」ではなく、Stripe固有の語彙
 * （{@code stripeSessionId}・{@code Stripe-Signature} ヘッダ）が注文テーブルと公開APIの
 * 仕様にまで漏れていたこと。決済業者名がドメインモデルに焼き付いている状態を解いて、
 * <em>どの決済手段でも同じ形</em>にするのがこのSPIの目的。
 *
 * <p>契約は最小限の2動作に絞ってある:
 * <ol>
 *   <li>{@link #createSession(Order)} — 支払いを開始し、利用者を送る先を返す</li>
 *   <li>{@link #handleCallback} — 外部からの支払確定通知を検証し、どの注文が支払われたかを返す</li>
 * </ol>
 * 銀行振込や代金引換のように<strong>外部APIもWebhookも持たない</strong>手段は
 * {@link #handleCallback} を実装しなくてよい（既定で「通知なし」）。それでも同じ契約に
 * 収まることが、この抽象が正しい粒度である根拠になっている。
 */
public interface PaymentProvider {

    /**
     * 安定した識別子（{@code "stripe"}, {@code "bank_transfer"}）。
     * <strong>URLとDBに保存されるので、一度公開したら変更しないこと。</strong>
     */
    String id();

    /** 決済手段の表示名（購入画面の選択肢に出る）。 */
    String displayName();

    /** 設定が揃っていて実際に決済を開始できるか。false なら選択肢に出ないし要求しても拒否される。 */
    boolean isEnabled();

    /**
     * 支払いを開始する。実装は外部にセッションを作り、利用者を誘導するURLを返す。
     * 注文ステータスの検証（PENDINGか）や注文への保存はコア側の責務なので、ここでは行わない。
     *
     * @throws com.example.ecapi.exception.BadRequestException 外部決済側でエラーが起きた場合
     */
    CheckoutSession createSession(Order order);

    /**
     * 外部からの支払確定通知（Webhook等）を検証して解釈する。
     *
     * <p><strong>署名検証はこのメソッドの中で完結させること。</strong>検証方法は業者ごとに
     * まったく違う（Stripeは {@code Stripe-Signature} のHMAC、他社はIP制限やBasic認証）ので、
     * コアに持たせると必ず業者依存が漏れる。
     *
     * <p>既定実装は<strong>拒否</strong>する。「コールバック経路を持たない手段」（銀行振込・
     * 代金引換）と「実装済みだがこのイベントには関心がない」は別物で、後者だけが
     * {@link Optional#empty()} を返す。既定を空にすると、前者の Webhook URL が認証なしで
     * 200 を返す“何もしない生きたエンドポイント”として公開されてしまう。
     *
     * <p>返す {@link PaymentCallback} には、決済側が受け取った金額・通貨・入金確定の有無を
     * 必ず入れること。照合はコア（{@code OrderService#confirmPayment}）が行う。
     *
     * @param payload 生のリクエストボディ（署名検証のためパース前の文字列で渡す）
     * @param headers 大文字小文字を区別しないヘッダマップ
     * @return 支払いが確定したなら対象注文を含む {@link PaymentCallback}。
     *         関心のないイベント（発送通知など）なら {@link Optional#empty()}。
     * @throws com.example.ecapi.exception.BadRequestException 署名が不正な場合、
     *         またはこの決済手段がコールバックを持たない場合
     */
    default Optional<PaymentCallback> handleCallback(String payload, Map<String, String> headers) {
        throw new com.example.ecapi.exception.BadRequestException(
                "Payment provider '" + id() + "' has no callback endpoint");
    }

    /**
     * 外部の決済ページを持たない手段（銀行振込・代金引換）が、ストアの画面に出す案内を返す。
     * 返した場合は支払い開始の応答に載り、{@code GET /api/payments/{id}/instructions} でも JSON で返る。
     * HTML は組み立てない（画面が値をエスケープして表示する）。
     *
     * @return 案内。持たない手段（外部の決済ページへ移るもの）は {@link Optional#empty()}
     */
    default Optional<PaymentInstructions> instructions(Order order) {
        return Optional.empty();
    }

    /**
     * 注文に紐づく、決済手段の側で開いている支払い（決済ページなど）を失効させる。
     * 変えるのは決済側の<strong>状態だけ</strong>で、決済側の記録は消さない（削除の操作は持たない）。
     *
     * <p>既定は {@link ExpireResult#NOTHING_OPEN}（外部に開いている支払いを持たない手段＝銀行振込）。
     * 実装は例外を投げず、分からないときは {@link ExpireResult#FAILED} を返すこと。
     */
    default ExpireResult expireOpenPayment(Order order) {
        return ExpireResult.NOTHING_OPEN;
    }
}
