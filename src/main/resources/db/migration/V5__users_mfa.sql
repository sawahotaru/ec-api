-- V5: 管理者ログインの二段階認証（TOTP）
--
-- 【なぜ users に足すのか】
-- 別テーブルにすると「ユーザーはいるが MFA 行が無い」状態と「行はあるが無効」状態が
-- 生まれ、判定が二重になる。1ユーザーに高々1つしか持たない値なので、列で足りる。
--
-- 【mfa_enabled を別に持つ理由】
-- 鍵が入っていることと、有効になっていることは別。登録は
--   ①鍵を発行 → ②認証アプリに登録 → ③コードを1回通して初めて有効化
-- の順で、②で失敗した人が締め出されないようにしている。secret の有無だけで
-- 判定すると、①の直後に落ちた人がログインできなくなる。
--
-- 【リカバリコードを平文で持たない理由】
-- 漏れた時点でそのまま二段階目を素通りできる＝パスワードと同じ扱いが要る。
-- ハッシュを改行区切りで持ち、使うたびにその行を消す（使い捨て）。

ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_secret VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_recovery_codes VARCHAR(2000);
