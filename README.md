# EC API — Spring Boot e-commerce REST API

小さいながら「ECの背骨」を一通り備えた REST API。**認証（JWT）・商品/カテゴリ・カート・注文（在庫引当）・管理者機能** を持ち、Swagger UI でそのまま試せます。Docker と Render にデプロイできる構成込み。

## 技術構成

- Java 21 / Spring Boot 3.3
- Spring Web / Spring Data JPA / Spring Security（JWT: jjwt）/ Bean Validation
- PostgreSQL（本番・docker-compose）/ H2 インメモリ（デフォルト・ゼロ設定起動）
- springdoc-openapi（Swagger UI）
- Docker マルチステージビルド / Render Blueprint（`render.yaml`）

## 機能

| 領域 | エンドポイント | 権限 |
|---|---|---|
| 認証 | `POST /api/auth/register`, `/login`, `GET /api/auth/me` | 公開 / 本人 |
| 商品（閲覧） | `GET /api/products`（検索`q`・`categoryId`・ページング・`sort`）, `GET /api/products/{id}` | 公開 |
| カテゴリ（閲覧） | `GET /api/categories`, `/{id}` | 公開 |
| カート | `GET /api/cart`, `POST /api/cart/items`, `PUT/DELETE /api/cart/items/{productId}`, `DELETE /api/cart` | ユーザー |
| 注文 | `POST /api/orders/checkout`, `GET /api/orders`, `/{id}` | ユーザー（本人のみ） |
| 管理: 商品 | `POST/PUT/DELETE /api/admin/products` | ADMIN |
| 管理: カテゴリ | `POST/PUT/DELETE /api/admin/categories` | ADMIN |
| 管理: 注文 | `GET /api/admin/orders`, `/{id}`, `PATCH /api/admin/orders/{id}/status` | ADMIN |
| 決済（Stripe・テスト） | `GET /api/payments/config`, `POST /api/payments/orders/{id}/checkout-session`, `POST /api/payments/webhook` | 公開 / 本人 |

チェックアウトは **在庫チェック → 注文作成 → 在庫減算 → カート空** を1トランザクションで実行し、注文明細には購入時点の商品名・単価をスナップショット保存します。

## ローカル実行

> **`<Port>` について**: コマンド中の `<Port>` は**ホスト側の公開ポート**。任意の空きポートに置き換えてください。
> 標準は **`8080`**（分かりやすさ重視）、このワークスペースでの参考値は **`8502`**。
> `-p <Port>:8080` の右側（コンテナ内ポート）は常に **8080 固定**です。

### A. Docker Compose（API + PostgreSQL・本番相当）

```bash
docker compose up --build
# → http://localhost:8080/swagger-ui.html
# ホストポートは docker-compose.yml の ports で定義（既定 8080）。変えたい場合はそこを編集。
```

### B. Docker 単体（H2 インメモリ・最速）

```bash
docker build -t ec-api .
docker run --rm -p <Port>:8080 ec-api      # 例: -p 8080:8080（標準） / -p 8502:8080（参考）
# → http://localhost:<Port>/swagger-ui.html
```

> ローカルに JDK は不要（すべて Docker 内でビルド）。JDK 21 + Maven がある場合は `mvn spring-boot:run` でも可。

## 初期データ（シード）

`APP_SEED_ENABLED=true`（デフォルト）で起動時に投入されます。

- 管理者: `admin@example.com` / `admin1234`（`APP_ADMIN_EMAIL` / `APP_ADMIN_PASSWORD` で変更可）
- デモユーザー: `user@example.com` / `user1234`
- カテゴリ3件・商品6件

## 使い方（最短フロー）

```bash
BASE=http://localhost:8080        # 自分の <Port> に合わせる（例 http://localhost:8502）

# 1) ログインしてトークン取得
curl -s -X POST $BASE/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"user1234"}'

# 2) 返ってきた token を Bearer にセット
TOKEN=... 

# 3) カートに追加 → チェックアウト
curl -X POST $BASE/api/cart/items \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"productId":1,"quantity":2}'

curl -X POST $BASE/api/orders/checkout \
  -H "Authorization: Bearer $TOKEN"
```

Swagger UI なら右上の **Authorize** にトークンを貼れば全エンドポイントを画面から試せます。

## Render へのデプロイ

1. このリポジトリを GitHub に push
2. Render の **New + → Blueprint** でリポジトリを選択（`render.yaml` を自動検出）
3. `APP_ADMIN_PASSWORD` を設定（他は自動: DB接続・JWTシークレットは自動生成）
4. 数分でビルド → `https://<name>.onrender.com/swagger-ui.html`

> 無料プランはアイドルでスリープするため、初回アクセスの起動に十数秒かかることがあります。

## 環境変数

| 変数 | 用途 | デフォルト |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `postgres` で PostgreSQL 有効化 | （未指定＝H2） |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | DB接続 | — |
| `APP_JWT_SECRET` | JWT署名鍵（32バイト以上） | 開発用ダミー |
| `APP_JWT_EXPIRATION_MS` | トークン有効期限 | 86400000（24h） |
| `APP_SEED_ENABLED` | 起動時シード | true |
| `APP_ADMIN_EMAIL` / `APP_ADMIN_PASSWORD` | 初期管理者 | admin@example.com / admin1234 |
| `STRIPE_SECRET_KEY` | Stripe テストキー `sk_test_...`（空=決済無効） | （空） |
| `STRIPE_WEBHOOK_SECRET` | Webhook 署名シークレット `whsec_...` | （空） |
| `STRIPE_CURRENCY` / `STRIPE_SUCCESS_URL` / `STRIPE_CANCEL_URL` | 通貨・決済後リダイレクト先 | jpy / `/api/payments/*` |
| `PORT` | **アプリ待受ポート（コンテナ内）**。Render 等が注入。ホスト公開ポート `<Port>` とは別 | 8080 |

## 決済（Stripe・テストモード専用）

Stripe Checkout（ホスト型）。`checkout-session` が返す `checkoutUrl` をブラウザで開き、テストカード **`4242 4242 4242 4242`**（有効期限=任意の未来 / CVC=任意）で支払うと、Webhook 経由で注文が **PAID** になります。

```bash
# 1) 注文を作成（PENDING）→ 2) 決済セッション作成 → 返る checkoutUrl を開く
curl -s -X POST $BASE/api/payments/orders/1/checkout-session \
  -H "Authorization: Bearer $TOKEN" | jq -r .checkoutUrl
```

キー未設定でもアプリは起動します（決済系のみ 400）。Webhook のローカル転送は Stripe CLI:
`stripe listen --forward-to localhost:<Port>/api/payments/webhook`

> ⚠️ **テストモード限定運用**。`sk_test_` / `whsec_` のみを使用し、本番(live)キー・実カードは扱いません。キーはコミットせず環境変数で渡します。

## 次の一手（拡張候補）

- 決済確定（Webhook）時に在庫を引き当てる方式へ（現状は checkout 時に減算）
- 商品画像アップロード、レビュー、在庫のペシミスティックロック
- Flyway でマイグレーション管理、統合テスト（Testcontainers）
