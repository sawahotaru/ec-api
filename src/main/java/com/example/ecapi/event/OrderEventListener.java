package com.example.ecapi.event;

/**
 * 注文イベントを受け取るプラグインの契約（SPI）。
 *
 * <p>実装を {@code @Component} として置くだけで {@link OrderEventDispatcher} が自動的に
 * 拾う。<strong>コア側のコードは1行も変更しなくてよい</strong>——これがこの拡張点の要点で、
 * 通知手段が増えるたびに {@code PaymentService} が肥大化していく事態を防ぐ。
 *
 * <pre>{@code
 * @Component
 * class SlackOrderNotifier implements OrderEventListener {
 *     public void onOrderEvent(OrderEvent event) {
 *         if (event instanceof OrderPaidEvent paid) { ...  }
 *     }
 * }
 * }</pre>
 *
 * <h2>実装時の約束</h2>
 * <ul>
 *   <li>呼び出しは<strong>トランザクションのコミット後</strong>。DBがロールバックしたのに
 *       メールだけ飛ぶ、が起きない代わりに、ここでのDB更新は別トランザクションになる。</li>
 *   <li>例外を投げてもよい。ディスパッチャが握り潰してログに出し、他のリスナーは走る
 *       （1つのプラグインの失敗が他を巻き込まない）。ただし注文自体は既に確定済みなので、
 *       リトライが要る処理は自前でキューイングすること。</li>
 *   <li>呼び出しは同期・直列。重い処理は {@code @Async} なり別スレッドへ逃がす。</li>
 * </ul>
 */
public interface OrderEventListener {

    void onOrderEvent(OrderEvent event);

    /** 実行順（小さいほど先）。既定100。順序に依存しないのが望ましいが、監査ログを先に出す等に。 */
    default int priority() {
        return 100;
    }

    /** ログ表示用の名前。既定はクラス名。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
