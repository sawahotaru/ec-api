-- V4: 金額計算の段階化（商品 → 割引 → 送料 → 税 → 合計）に必要な列とクーポン表
--
-- 【既存の注文をどう扱うか】
-- 追加する金額列は NOT NULL DEFAULT 0 で入れる。既存注文は割引も送料も無かったのだから
-- 0 が正しい値であって、「不明」ではない。したがって NULL 許容にする理由が無い。
-- これで total = subtotal − discount + shipping + tax の恒等式が**過去の注文でも成立**する
-- （0 を引いて 0 を足すだけ）。集計クエリが「discount IS NULL なら 0 とみなす」のような
-- 分岐を持たずに済むのが、ここを NOT NULL にしておく実利。
--
-- 【coupon_code に外部キーを張らない理由】
-- 注文はコードを**スナップショット**として持つ。クーポン行を消したり作り直したりしても
-- 過去の注文の金額と表示は変わってはならない。外部キーを張ると、それができなくなる
-- （消せない／消すと注文が壊れる）。商品名・単価を order_items にコピーしているのと同じ考え方。

ALTER TABLE orders ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS shipping_amount NUMERIC(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS coupon_code VARCHAR(40);

ALTER TABLE order_items ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS coupons (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(40)   NOT NULL UNIQUE,
    description     VARCHAR(255),
    discount_type   VARCHAR(20)   NOT NULL,
    -- ⚠ 列名を discount_value にしてあるのは、`value` が H2 の予約語で
    --    CREATE TABLE が構文エラーになるため（テストのH2で実際に踏んだ）。
    --    PostgreSQL では通るので、本番だけで動く SQL になりかけた。
    discount_value  NUMERIC(12, 2) NOT NULL DEFAULT 0,
    min_subtotal    NUMERIC(12, 2),
    valid_from      DATE,
    valid_to        DATE,
    max_redemptions INTEGER,
    redeemed_count  INTEGER       NOT NULL DEFAULT 0,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_coupons_discount_type CHECK (discount_type IN ('PERCENT', 'FIXED', 'FREE_SHIPPING')),
    -- 引き換え数は返却（キャンセル・期限切れ）で減るので、マイナスに落ちないことをDBでも保証する。
    CONSTRAINT ck_coupons_redeemed_count CHECK (redeemed_count >= 0)
);
