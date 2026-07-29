-- V3: 実DBに残っていた「エンティティと合っていないスキーマ」を是正する
--
-- 【なぜ必要か】ddl-auto=update は列の追加しかしない。CHECK 制約の作り直しも
-- NOT NULL の緩和もしないため、機能追加のたびに実DBだけが古いまま取り残されていた。
-- Flyway 導入（V1 を実DBの pg_dump から起こす）ときに、以下2件が実際に露出した:
--
--   1. orders.status の CHECK に **EXPIRED が無い**。
--      → 未入金注文の期限切れスイープ（OrderService#expireStalePendingOrders）が
--        本番で必ず制約違反になる。引当在庫が解放されず売れないまま残る。
--      → 本番(VM)・ローカル(lab-db-1) の両方で欠落を確認済み。
--   2. orders.user_id が NOT NULL のまま（ローカル lab-db-1）。
--      → ゲスト購入は user_id=NULL で入るので、そのままではゲスト注文が作れない。
--      → 本番は既に NULL 可だった（ゲスト購入の後に作り直されたため）。環境差を潰す。
--
-- 【冪等性】空DB（テストのH2・新規環境）では V1 が既に正しい形で作っているので、
-- ここは「同じ制約を貼り直すだけ」になる。DROP → ADD の順で書けば H2/PostgreSQL とも
-- 何度流しても同じ結果になる（PostgreSQL に ADD CONSTRAINT IF NOT EXISTS が無いため）。

-- 1. status の CHECK を EXPIRED 込みで貼り直す。
--    Hibernate が自動生成した名前(orders_status_check)と、V1 で付けた名前(ck_orders_status)の
--    両方を落としてから、名前つきで貼り直す。以降は名前が固定なので ALTER できる。
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (
    status IN ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'EXPIRED'));

-- 2. pricing_mode も同様に名前を固定しておく（値の変更は無いが、次に増えたとき困らないよう）。
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_pricing_mode_check;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_orders_pricing_mode;
ALTER TABLE orders ADD CONSTRAINT ck_orders_pricing_mode CHECK (
    pricing_mode IN ('INCLUSIVE', 'EXCLUSIVE'));

-- 3. ゲスト購入のため user_id を NULL 可にする（既に NULL 可なら何も起きない）。
ALTER TABLE orders ALTER COLUMN user_id DROP NOT NULL;
