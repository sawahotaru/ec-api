package com.example.ecapi.event;

import java.math.BigDecimal;

/**
 * 注文ライフサイクル上の出来事。コア（注文・決済サービス）はこれを publish するだけで、
 * 「何が起きるか」（メール送信・Slack通知・会計連携・分析イベント…）は
 * {@link OrderEventListener} を実装するプラグイン側の関心事になる。
 *
 * <p>イベントはエンティティではなく<em>値のスナップショット</em>を運ぶ。リスナーは
 * トランザクション<em>コミット後</em>に走るため、その時点で JPA エンティティは detach
 * 済みであり、遅延ロードを踏むと落ちる。ここで必要な値を確定させておくことでその事故を
 * 構造的に防いでいる。
 *
 * <p>sealed にしてあるのは意図的で、種類が増えたときに switch の網羅漏れをコンパイラに
 * 検出させるため。新しい種類を足すときは permits に追加すること。
 */
public sealed interface OrderEvent
        permits OrderPaidEvent, OrderCancelledEvent, OrderExpiredEvent {

    Long orderId();

    /** 注文者の連絡先メール（会員はアカウント、ゲストは入力値）。未設定なら null。 */
    String contactEmail();

    /** 税込合計（支払総額）のスナップショット。 */
    BigDecimal totalAmount();

    /** ログ・件名に使う短い種別名（{@code "paid"} 等）。 */
    String type();
}
