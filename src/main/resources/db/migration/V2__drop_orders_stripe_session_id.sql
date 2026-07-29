-- V2: 孤立列 orders.stripe_session_id を撤去する
--
-- 決済をプラグイン(SPI)化した際に、Stripe 固有の stripe_session_id は決済手段に依存しない
-- payment_provider / payment_reference に置き換えた。だが ddl-auto=update は列を落とさないため、
-- 実DBには使われない列が残り続けていた（エンティティ Order にも対応フィールドは無い）。
--
-- 捨てる前に、まだ payment_reference が埋まっていない過去の Stripe 注文だけ移し替える。
UPDATE orders
   SET payment_reference = stripe_session_id,
       payment_provider  = COALESCE(payment_provider, 'stripe')
 WHERE payment_reference IS NULL
   AND stripe_session_id IS NOT NULL;

ALTER TABLE orders DROP COLUMN stripe_session_id;
