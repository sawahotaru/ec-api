"use strict";

/* ---------- tiny helpers ---------- */
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));
const yen = (n) => "¥" + Number(n || 0).toLocaleString("ja-JP", { maximumFractionDigits: 0 });

// OrderStatus (server enum) → 買い手向けの日本語。未知の値はそのまま出す。
const STATUS_LABEL = {
    PENDING: "お支払い待ち",
    PAID: "支払い済み",
    SHIPPED: "発送済み",
    DELIVERED: "配達済み",
    CANCELLED: "キャンセル",
    EXPIRED: "期限切れ",
};
function statusLabel(status) { return STATUS_LABEL[status] || status; }

// The app can be served at the site root ("/") locally or under a sub-path
// (e.g. "/ec/" behind Caddy on the Oracle VM). Derive the base from this
// script's own URL — <BASE>/js/app.js — so no build-time config is needed.
// Result: "" at the root, "/ec" under /ec.
const SELF = document.currentScript || document.scripts[document.scripts.length - 1];
const BASE = new URL("..", SELF.src).pathname.replace(/\/$/, "");

// 商品画像は同梱画像（相対パス "images/products/x.jpg"）でも、外部URLでも受け付ける。
// 相対パスは BASE で解決する — CSS の url() は「ドキュメントURL基準」で、ハッシュ
// ルーティングやサブパス配信（/ec/）だと素の相対パスでは狙った先を指さないため。
function imageStyle(url) {
    if (!url) return "";
    const src = /^(https?:)?\/\//.test(url) || url.startsWith("/") ? url : `${BASE}/${url}`;
    return `background-image:url('${src}')`;
}

const state = {
    token: localStorage.getItem("ec_token") || null,
    user: null,
    paymentsEnabled: false,
    // 有効な決済手段（サーバー側の PaymentProvider プラグイン）。[{id, displayName}]
    paymentProviders: [],
    categories: [],
    lastOrder: null,
    productsById: {},
    // For guests at checkout: 'choice' shows 会員/ゲスト の選択、'guest' shows the guest email form.
    guestMode: "choice",
    // Tax display: pricing mode (INCLUSIVE=内税/EXCLUSIVE=外税) from /api/tax/config.
    taxMode: "INCLUSIVE",
    // { STANDARD: 10, REDUCED: 8 } — currently-effective rates (商品カードの表示用)。
    taxRates: {},
    // 送料の公開設定 { fee, freeThreshold }。「あと○円で送料無料」の判定にだけ使う。
    shipping: { fee: 0, freeThreshold: 0 },
    // カートに適用中のクーポンコード。サーバーが検証したものだけが入る。
    couponCode: null,
    // 直近の /api/checkout/quote の結果。表示も、注文ボタンの金額もこれが根拠。
    quote: null,
    // ログインの二段階目で使う引換券。これでは何のAPIも叩けない（サーバー側で用途を判定）。
    mfaToken: null,
};

// Suffix shown after prices, e.g. 「（税込）」. Prices in the catalog are tax-included
// in INCLUSIVE mode, tax-exclusive in EXCLUSIVE mode.
function taxSuffix() {
    return state.taxMode === "EXCLUSIVE" ? "（税抜）" : "（税込）";
}

async function loadTaxConfig() {
    const tax = await api("/api/tax/config");
    state.taxMode = tax.pricingMode || "INCLUSIVE";
    state.taxRates = {};
    (tax.rates || []).forEach((r) => { state.taxRates[r.category] = Number(r.ratePercent); });
}

/* 送料の公開設定。「あと○円で送料無料」を出すためだけに要る（金額そのものは
   見積もりAPIが返すので、ここで計算はしない）。 */
async function loadShippingConfig() {
    const cfg = await api("/api/checkout/shipping");
    state.shipping = { fee: Number(cfg.fee || 0), freeThreshold: Number(cfg.freeThreshold || 0) };
}

async function api(path, { method = "GET", body, auth = false } = {}) {
    const headers = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (auth && state.token) headers["Authorization"] = "Bearer " + state.token;
    const res = await fetch(BASE + path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
    const text = await res.text();
    const data = text ? JSON.parse(text) : null;
    if (!res.ok) {
        const msg = (data && data.message) || `${res.status} ${res.statusText}`;
        throw new Error(msg);
    }
    return data;
}

function toast(msg) {
    const t = $("#toast");
    t.textContent = msg;
    t.classList.remove("hidden");
    clearTimeout(toast._t);
    toast._t = setTimeout(() => t.classList.add("hidden"), 2600);
}

/* ---------- guest cart (localStorage; used only when NOT logged in) ----------
   Logged-in users have a server-side cart; guests have no account, so we keep
   their picks client-side and pass them inline to /api/orders/guest-checkout. */
const GUEST_CART_KEY = "ec_guest_cart";
const GUEST_ORDERS_KEY = "ec_guest_orders";

function guestCart() {
    try { return JSON.parse(localStorage.getItem(GUEST_CART_KEY)) || []; }
    catch { return []; }
}
function saveGuestCart(items) { localStorage.setItem(GUEST_CART_KEY, JSON.stringify(items)); }

// Shape the guest cart like a server CartResponse so renderCart() is shared.
function guestCartView() {
    const items = guestCart().map((it) => ({
        // taxCategory may be absent in carts saved before the tax feature — default it.
        product: { id: it.productId, name: it.name, price: it.price, imageUrl: it.imageUrl, taxCategory: it.taxCategory || "STANDARD" },
        quantity: it.quantity,
        lineTotal: it.price * it.quantity,
    }));
    return {
        items,
        totalQuantity: items.reduce((s, i) => s + i.quantity, 0),
        totalAmount: items.reduce((s, i) => s + i.lineTotal, 0),
    };
}

/* Orders placed as a guest, remembered on THIS browser only. The server has no way
   to list them (there is no account), so the id+token pair kept here is the whole
   convenience layer behind 「注文照会」. It is also the reason the screen offers a
   delete button: on a shared machine the tokens are the credential. */
function guestOrderLog() {
    try { return JSON.parse(localStorage.getItem(GUEST_ORDERS_KEY)) || []; }
    catch { return []; }
}
function saveGuestOrderLog(list) { localStorage.setItem(GUEST_ORDERS_KEY, JSON.stringify(list.slice(0, 20))); }

function rememberGuestOrder(order) {
    if (!order || !order.orderToken) return;
    const list = guestOrderLog().filter((o) => o.id !== order.id);
    list.unshift({ id: order.id, token: order.orderToken, total: order.totalAmount, at: order.createdAt });
    saveGuestOrderLog(list);
}

function forgetGuestOrder(id) {
    saveGuestOrderLog(guestOrderLog().filter((o) => o.id !== id));
}

/* ---------- products ---------- */
let searchTimer;
async function loadProducts() {
    const q = $("#search").value.trim();
    const categoryId = $("#category").value;
    const params = new URLSearchParams({ size: "24", sort: "id" });
    if (q) params.set("q", q);
    if (categoryId) params.set("categoryId", categoryId);
    const page = await api(`/api/products?${params.toString()}`);
    renderProducts(page.content || []);
}

function renderProducts(products) {
    const grid = $("#grid");
    state.productsById = {};
    products.forEach((p) => { state.productsById[p.id] = p; });
    $("#emptyState").classList.toggle("hidden", products.length > 0);
    grid.innerHTML = products.map((p) => {
        // "available" (= stock − reserved) is what can actually be sold right now.
        // Fall back to stock for older API responses that lack the field.
        const avail = (p.available != null) ? p.available : p.stock;
        const out = avail <= 0;
        const img = imageStyle(p.imageUrl);
        // Thumbnail and name link to the detail page (#/product/{id}); the router handles it.
        return `
        <article class="card">
            <a class="card-link" href="#/product/${p.id}" aria-label="${escapeHtml(p.name)} の詳細">
                <div class="thumb" style="${img}"></div>
            </a>
            <div class="body">
                <div class="cat">${p.category ? escapeHtml(p.category.name) : "&nbsp;"}</div>
                <a class="card-link" href="#/product/${p.id}"><div class="name">${escapeHtml(p.name)}</div></a>
                <div class="desc">${escapeHtml(p.description || "")}</div>
                <div class="row">
                    <span class="price">${yen(p.price)}</span>
                    <span class="stock ${out ? "out" : ""}">${out ? "在庫切れ" : "在庫 " + avail}</span>
                </div>
                <button class="btn wide add-btn" data-id="${p.id}" ${out ? "disabled" : ""}>カートに入れる</button>
            </div>
        </article>`;
    }).join("");
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

async function loadCategories() {
    state.categories = await api("/api/categories");
    const sel = $("#category");
    sel.innerHTML = '<option value="">全カテゴリ</option>' +
        state.categories.map((c) => `<option value="${c.id}">${escapeHtml(c.name)}</option>`).join("");
}

/* ---------- routing / product detail ----------
   Hash-based so detail pages are linkable and the browser Back button works.
   #/product/{id} → detail view; anything else → the product grid. */
function currentRoute() {
    const path = location.hash.replace(/^#/, "");
    const m = path.match(/^\/product\/(\d+)$/);
    if (m) return { name: "product", id: Number(m[1]) };
    if (path === "/admin") return { name: "admin" };
    // Guest order lookup. The id+token form is a deep link, so the guest can bookmark
    // one order — the token IS the credential, exactly as in the API.
    const g = path.match(/^\/orders\/guest(?:\/(\d+)\/([^/]+))?$/);
    if (g) {
        return {
            name: "guestOrders",
            id: g[1] ? Number(g[1]) : null,
            token: g[2] ? decodeURIComponent(g[2]) : null,
        };
    }
    return { name: "home" };
}

async function route() {
    const r = currentRoute();
    if (r.name === "product") await showDetail(r.id);
    else if (r.name === "admin") await showAdmin();
    else if (r.name === "guestOrders") await showGuestOrders(r.id, r.token);
    else showGrid();
}

// Exactly one of grid / detail / admin / guest-lookup is visible at a time.
function hideViews() {
    $("#productDetail").classList.add("hidden");
    $("#productDetail").innerHTML = "";
    $("#adminPanel").classList.add("hidden");
    $("#adminPanel").innerHTML = "";
    $("#guestOrders").classList.add("hidden");
    $("#guestOrders").innerHTML = "";
    $("#grid").classList.add("hidden");
    $("#emptyState").classList.add("hidden");
}

function showGrid() {
    hideViews();
    $("#grid").classList.remove("hidden");
    // #emptyState visibility is owned by renderProducts()
    $("#emptyState").classList.toggle("hidden", $("#grid").children.length > 0);
}

async function showDetail(id) {
    hideViews();
    const box = $("#productDetail");
    box.classList.remove("hidden");
    box.innerHTML = '<p class="empty">読み込み中…</p>';
    try {
        const p = state.productsById[id] || await api(`/api/products/${id}`);
        state.productsById[id] = p;
        renderDetail(p);
        window.scrollTo({ top: 0 });
    } catch {
        box.innerHTML = '<p class="empty">商品が見つかりません。 <a href="#/">一覧へ戻る</a></p>';
    }
}

function renderDetail(p) {
    const avail = (p.available != null) ? p.available : p.stock;
    const out = avail <= 0;
    const img = imageStyle(p.imageUrl);
    $("#productDetail").innerHTML = `
        <a href="#/" class="back-link">← 商品一覧へ戻る</a>
        <div class="detail-grid">
            <div class="detail-thumb" style="${img}"></div>
            <div class="detail-info">
                <div class="cat">${p.category ? escapeHtml(p.category.name) : ""}</div>
                <h1 class="detail-name">${escapeHtml(p.name)}</h1>
                <p class="detail-desc">${escapeHtml(p.description || "")}</p>
                <div class="detail-price">${yen(p.price)}<span class="tax-note">${taxSuffix()}</span></div>
                <div class="detail-stock ${out ? "out" : ""}">${out ? "在庫切れ" : "在庫 " + avail}</div>
                <button class="btn wide add-btn" data-id="${p.id}" ${out ? "disabled" : ""}>カートに入れる</button>
            </div>
        </div>`;
}

/* ---------- admin panel (#/admin, ADMIN role only) ----------
   Wraps the ADMIN-only APIs that previously had no UI:
     GET/PUT /api/admin/settings/pricing-mode  → 内税/外税 の切替（再デプロイ不要）
     CRUD    /api/admin/tax-rates              → 有効期間つき税率のメンテ
   Switching the mode or a rate affects FUTURE orders only; past orders keep the
   values snapshotted at purchase time. */
function isAdmin() { return !!(state.user && state.user.role === "ADMIN"); }

async function showAdmin() {
    hideViews();
    const box = $("#adminPanel");
    box.classList.remove("hidden");
    if (!isAdmin()) {
        box.innerHTML = `<a href="#/" class="back-link">← 商品一覧へ戻る</a>
            <p class="empty">管理者としてログインしてください。</p>`;
        return;
    }
    box.innerHTML = '<p class="empty">読み込み中…</p>';
    try {
        const [settings, rates, products, coupons, stats] = await Promise.all([
            api("/api/admin/settings", { auth: true }),
            api("/api/admin/tax-rates", { auth: true }),
            api("/api/products?size=100&sort=id"),
            api("/api/admin/coupons", { auth: true }),
            api("/api/admin/stats?months=12", { auth: true }),
        ]);
        // 失敗しても管理画面は出す（MFAカードが出ないだけ）
        let mfa = null;
        try { mfa = await api("/api/auth/mfa/status", { auth: true }); } catch { /* ignore */ }
        renderAdmin(settings, rates, products.content || [], coupons || [], stats, mfa);
    } catch (ex) {
        box.innerHTML = `<a href="#/" class="back-link">← 商品一覧へ戻る</a>
            <p class="empty">管理情報を取得できませんでした: ${escapeHtml(ex.message)}</p>`;
    }
}

function renderAdmin(settings, rates, products, coupons, stats, mfa) {
    const mode = settings.pricingMode;
    const today = new Date().toISOString().slice(0, 10);
    // Sort so the effective-date timeline reads top-down per category.
    const sorted = [...rates].sort((a, b) =>
        a.category.localeCompare(b.category) || a.effectiveFrom.localeCompare(b.effectiveFrom));

    const activeIds = effectiveRateIds(rates, today);
    const rows = sorted.map((r) => {
        const active = activeIds.has(r.id);
        return `
        <tr data-rate="${r.id}" class="${active ? "rate-active" : ""}">
            <td>
                <select data-f="category">
                    <option value="STANDARD" ${r.category === "STANDARD" ? "selected" : ""}>標準</option>
                    <option value="REDUCED" ${r.category === "REDUCED" ? "selected" : ""}>軽減</option>
                </select>
            </td>
            <td><input data-f="ratePercent" type="number" step="0.01" min="0" value="${r.ratePercent}"> %</td>
            <td><input data-f="effectiveFrom" type="date" value="${r.effectiveFrom}"></td>
            <td><input data-f="effectiveTo" type="date" value="${r.effectiveTo || ""}"></td>
            <td class="rate-state">${active ? "適用中" : ""}</td>
            <td class="rate-actions">
                <button class="btn ghost sm" data-act="save-rate">保存</button>
                <button class="link-danger" data-act="del-rate">削除</button>
            </td>
        </tr>`;
    }).join("");

    $("#adminPanel").innerHTML = `
        <a href="#/" class="back-link">← 商品一覧へ戻る</a>
        <h1 class="admin-title">⚙️ ストア管理</h1>

        ${settings.readOnly ? `
        <p class="readonly-note">🔒 これは<strong>公開デモ</strong>のため、閲覧のみです。
           保存・追加・削除は行われません（自分の環境で動かすと制限なく使えます）。</p>` : ""}

        ${statsCardHtml(stats)}

        ${mfaCardHtml(mfa, settings.readOnly)}

        <section class="admin-card">
            <h2>税の表示方式</h2>
            <p class="hint">価格の見せ方を切り替えます。<strong>再デプロイ不要・即時反映</strong>。
               切替は<strong>今後の注文のみ</strong>に影響し、過去の注文は当時の方式・税額のまま変わりません。</p>
            <div class="mode-switch">
                <button class="btn ${mode === "INCLUSIVE" ? "" : "ghost"}" data-act="mode" data-mode="INCLUSIVE">内税（税込表示）</button>
                <button class="btn ${mode === "EXCLUSIVE" ? "" : "ghost"}" data-act="mode" data-mode="EXCLUSIVE">外税（税抜表示）</button>
            </div>
            <p class="hint">現在: <strong>${mode === "EXCLUSIVE" ? "外税（税抜表示）" : "内税（税込表示）"}</strong></p>
        </section>

        <section class="admin-card">
            <h2>消費税率（有効期間つき）</h2>
            <p class="hint">税率改定は<strong>行の追加</strong>で行います。同じ区分で期間が重なっても構いません（<strong>開始日が新しい行が優先</strong>）。将来日付で予約しておけば当日から自動で切り替わります。
               <br>「適用中」は本日実際に使われる行。<strong>終了日はその日を含みません</strong>（2026/12/31 終了なら 12/30 まで有効）。</p>
            <div class="table-wrap">
                <table class="admin-table">
                    <thead><tr><th>区分</th><th>税率</th><th>開始日</th><th>終了日（空=無期限）</th><th></th><th></th></tr></thead>
                    <tbody>${rows || '<tr><td colspan="6" class="empty">税率が未登録です。</td></tr>'}</tbody>
                </table>
            </div>
            <div class="rate-new">
                <h3>税率を追加</h3>
                <div class="rate-new-row">
                    <select id="newCategory">
                        <option value="STANDARD">標準</option>
                        <option value="REDUCED">軽減</option>
                    </select>
                    <input id="newRate" type="number" step="0.01" min="0" placeholder="10.00">
                    <input id="newFrom" type="date" value="${today}">
                    <input id="newTo" type="date" placeholder="終了日（任意）">
                    <button class="btn" data-act="add-rate">追加</button>
                </div>
            </div>
        </section>

        ${shippingCardHtml(settings)}

        ${couponsCardHtml(coupons || [], today)}

        ${productImagesCardHtml(products || [])}`;
}

/* ---------- stats ----------
   売上に数えるのは支払い済み（PAID / SHIPPED / DELIVERED）だけ。未払い・失効は
   件数として別に出す。「注文が入った数」と「売れた数」を1つの数字に混ぜない。 */

const STATUS_ORDER = ["PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED", "EXPIRED"];

function statsCardHtml(stats) {
    if (!stats) return "";
    if (!stats.paidOrders && !stats.pendingOrders && !stats.lostOrders) {
        return `<section class="admin-card"><h2>売上</h2>
            <p class="hint">注文がまだありません。</p></section>`;
    }

    const tile = (label, value, note) => `
        <div class="stat-tile">
            <span class="stat-tile-label">${label}</span>
            <strong>${value}</strong>
            <span class="stat-tile-note">${note}</span>
        </div>`;

    const maxRevenue = Math.max(...stats.monthly.map((m) => Number(m.revenue)), 0);
    const months = stats.monthly.map((m) => bar(m.month, Number(m.revenue), maxRevenue, yen(m.revenue),
        `${m.orders}件`)).join("");

    const statuses = STATUS_ORDER
        .map((s) => stats.statuses.find((x) => x.status === s))
        .filter(Boolean)
        .map((s) => `<tr><td>${escapeHtml(statusLabel(s.status))}</td><td>${s.orders}件</td>
            <td>${REVENUE_STATUSES.includes(s.status) ? yen(s.amount) : "—"}</td></tr>`).join("");

    const maxQty = Math.max(...stats.products.map((p) => p.quantity), 0);
    const products = stats.products.slice(0, 10)
        .map((p) => bar(p.productName, p.quantity, maxQty, yen(p.revenue), `${p.quantity}点`)).join("");

    const providers = stats.providers.map((p) =>
        `<tr><td>${escapeHtml(p.provider)}</td><td>${p.orders}件</td><td>${yen(p.revenue)}</td></tr>`).join("");

    const coupons = stats.coupons.map((c) =>
        `<tr><td>${escapeHtml(c.code)}</td><td>${c.orders}件</td><td>−${yen(c.discount)}</td></tr>`).join("");

    return `
        <section class="admin-card">
            <h2>売上</h2>
            <p class="hint">金額に入るのは<strong>支払い済みの注文だけ</strong>です（未払い・失効・キャンセルは
               件数のみ）。金額は注文時のスナップショットから積んでいるので、
               あとから価格や商品名を変えても<strong>過去の数字は動きません</strong>。</p>
            <div class="stat-tiles">
                ${tile("売上（税込）", yen(stats.revenue), `${stats.paidOrders}件`)}
                ${tile("平均注文単価", yen(stats.averageOrder), "支払い済みの平均")}
                ${tile("お支払い待ち", stats.pendingOrders + "<small>件</small>", "在庫を確保中")}
                ${tile("失効・キャンセル", stats.lostOrders + "<small>件</small>", "機会損失")}
                ${tile("売上になった割合", stats.conversionRate + "<small>%</small>", "全注文のうち")}
                ${tile("割引の合計", "−" + yen(stats.discountTotal), "クーポン")}
            </div>

            <h3>月ごとの売上（直近12ヶ月）</h3>
            ${months}

            <h3>注文の状態</h3>
            <div class="table-wrap"><table class="admin-table">
                <thead><tr><th>状態</th><th>件数</th><th>金額</th></tr></thead>
                <tbody>${statuses}</tbody>
            </table></div>

            ${stats.products.length ? `<h3>よく売れている商品</h3>${products}` : ""}

            ${providers ? `<h3>決済手段別</h3>
            <div class="table-wrap"><table class="admin-table">
                <thead><tr><th>手段</th><th>件数</th><th>売上</th></tr></thead>
                <tbody>${providers}</tbody>
            </table></div>` : ""}

            ${coupons ? `<h3>クーポンの利用</h3>
            <div class="table-wrap"><table class="admin-table">
                <thead><tr><th>コード</th><th>利用</th><th>割引額</th></tr></thead>
                <tbody>${coupons}</tbody>
            </table></div>` : ""}
        </section>`;
}

const REVENUE_STATUSES = ["PAID", "SHIPPED", "DELIVERED"];

/* 棒グラフ1行。外部のグラフライブラリを入れないのは、このフロントが依存ゼロの
   バニラJSであることに価値があるため（配布物としてビルド工程が要らない）。 */
function bar(label, value, max, valueText, note) {
    const pct = max > 0 ? Math.round((value / max) * 100) : 0;
    return `
        <div class="stat-row">
            <span class="stat-label" title="${escapeHtml(label)}">${escapeHtml(label)}</span>
            <span class="stat-bar"><span class="stat-fill" style="width:${pct}%"></span></span>
            <span class="stat-value">${valueText}${note ? `<small>${escapeHtml(note)}</small>` : ""}</span>
        </div>`;
}

/* ---------- 二段階認証（自分のアカウント） ----------
   QRはサーバーが SVG で返す（`QrCode.svg`）。**鍵を外部のQR生成サービスへ送らない**ため。
   自作エンコーダの正しさは、実機で読めている clinic-reservation の実装と
   出力を1モジュールずつ突き合わせて担保している（QrCodeTest 参照）。
   読み取れない環境のために、otpauth:// リンクと鍵の手入力も併記する。 */

function mfaCardHtml(mfa, readOnly) {
    if (!mfa) return "";
    const body = mfa.enabled
        ? `<p class="hint">状態: <strong>有効 ✓</strong>（ログイン時に認証アプリの6桁が必要です）
             ／ リカバリコード残り <strong>${mfa.remainingRecoveryCodes}</strong> 本</p>
           <div class="rate-new-row">
             <input id="mfaDisableCode" type="text" inputmode="numeric" placeholder="認証コード">
             <button class="link-danger" data-act="mfa-disable">二段階認証を解除</button>
           </div>
           <p class="hint">解除にも認証コードが要ります（盗まれたセッションで黙って外されないように）。</p>`
        : `<p class="hint">状態: <strong>無効</strong>。有効にすると、パスワードに加えて
             認証アプリの6桁が必要になります。</p>
           <button class="btn" data-act="mfa-setup">二段階認証を設定する</button>`;

    return `
        <section class="admin-card" id="mfaCard">
            <h2>二段階認証</h2>
            ${readOnly ? '<p class="hint">※ 公開デモでは設定を変更できません（下記参照）。</p>' : ""}
            ${body}
            <div id="mfaSetupArea"></div>
        </section>`;
}

async function startMfaSetup() {
    try {
        const res = await api("/api/auth/mfa/setup", { method: "POST", auth: true });
        $("#mfaSetupArea").innerHTML = `
            <div class="mfa-setup">
              <ol class="mfa-steps">
                <li>認証アプリ（Google Authenticator など）で「アカウントを追加」→
                    「QRコードをスキャン」を選び、下のQRを読み取ります。
                    <div class="mfa-qr">${res.qrSvg || ""}</div></li>
                <li>読み取れないときは、認証アプリの「手動で入力」からこの鍵を入れてください:
                    <br><code class="mfa-secret">${escapeHtml(res.secret)}</code>
                    <br><span class="hint">スマホでこの画面を見ているなら、
                    <a class="mfa-link" href="${escapeHtml(res.otpauthUri)}">このリンク</a>を押すだけでも登録できます。</span></li>
                <li>アプリに出た6桁を入れて「確認して有効化」を押します。</li>
              </ol>
              <div class="rate-new-row">
                <input id="mfaConfirmCode" type="text" inputmode="numeric" placeholder="123456">
                <button class="btn" data-act="mfa-confirm">確認して有効化</button>
              </div>
              <p class="hint">⚠️ この鍵は<strong>毎回まったく新しいもの</strong>が作られます。
                 認証アプリに前の登録が残っていると、同じ名前が2つ並んで古いほうの数字を
                 入れてしまいます。新しく読み取ったら、アプリ側の古い登録は削除してください。</p>
            </div>`;
    } catch (ex) { toast(ex.message); }
}

async function confirmMfa() {
    const code = $("#mfaConfirmCode").value.trim();
    if (!code) { toast("認証コードを入力してください"); return; }
    try {
        const res = await api("/api/auth/mfa/confirm", { method: "POST", auth: true, body: { code } });
        // リカバリコードはここでしか出ない（保存はハッシュ）。閉じる前に控えてもらう。
        const list = (res.recoveryCodes || []).map((c) => `<li><code>${escapeHtml(c)}</code></li>`).join("");
        $("#mfaSetupArea").innerHTML = `
            <div class="mfa-recovery">
              <h3>リカバリコード（この画面でしか表示されません）</h3>
              <p class="hint">認証アプリが使えなくなったときの最後の入口です。
                 <strong>印刷するか紙に控えて</strong>保管してください。1本につき1回だけ使えます。</p>
              <ol class="mfa-codes">${list}</ol>
              <button class="btn ghost" data-act="mfa-done">控えました</button>
            </div>`;
    } catch (ex) { toast(ex.message); }
}

async function disableMfa() {
    const code = $("#mfaDisableCode").value.trim();
    if (!code) { toast("認証コードを入力してください"); return; }
    // 解除は取り消せない。もう一度有効にするときは鍵が作り直され、認証アプリへの登録と
    // リカバリコードの控えをやり直すことになる（clinic 側で同じ注意書きを出している）。
    const msg = [
        "二段階認証を解除します。",
        "",
        "もう一度有効にするときは新しい鍵が作り直され、",
        "認証アプリへの登録とリカバリコードの控えをやり直すことになります。",
        "（そのときアプリの古い登録は削除してください）",
        "",
        "解除しますか？",
    ].join("\n");
    if (!window.confirm(msg)) return;
    try {
        await api("/api/auth/mfa", { method: "DELETE", auth: true, body: { code } });
        await showAdmin();
        toast("二段階認証を解除しました");
    } catch (ex) { toast(ex.message); }
}

/* ---------- shipping ---------- */

function shippingCardHtml(settings) {
    const fee = Number(settings.shippingFee || 0);
    const threshold = Number(settings.shippingFreeThreshold || 0);
    return `
        <section class="admin-card">
            <h2>送料</h2>
            <p class="hint">金額は<strong>商品価格と同じ流儀</strong>で入れます（現在は
               ${settings.pricingMode === "EXCLUSIVE" ? "外税＝税抜" : "内税＝税込"}）。送料には<strong>標準税率</strong>がかかります。
               <br>「送料無料になる金額」は<strong>割引後</strong>の商品合計で判定します。0 にすると送料無料にはなりません。
               送料そのものを 0 にすれば送料を取りません。</p>
            <div class="rate-new-row">
                <label class="inline-field"><span>送料</span>
                    <input id="shipFee" type="number" min="0" step="1" value="${fee}"></label>
                <label class="inline-field"><span>送料無料になる金額</span>
                    <input id="shipFree" type="number" min="0" step="1" value="${threshold}"></label>
                <button class="btn" data-act="save-shipping">保存</button>
            </div>
            <p class="hint">現在: 送料 <strong>${fee > 0 ? yen(fee) : "なし"}</strong>
               ／ ${threshold > 0 ? yen(threshold) + " 以上で無料" : "送料無料の設定なし"}</p>
        </section>`;
}

async function saveShipping() {
    const fee = $("#shipFee").value.trim();
    const freeThreshold = $("#shipFree").value.trim();
    if (fee === "" || freeThreshold === "") { toast("送料としきい値を入力してください"); return; }
    try {
        await api("/api/admin/settings/shipping", {
            method: "PUT", auth: true, body: { fee: Number(fee), freeThreshold: Number(freeThreshold) },
        });
        await loadShippingConfig();
        await refreshCart();      // 表示中のカートの金額もその場で合わせる
        await showAdmin();
        toast("送料を更新しました");
    } catch (ex) { toast(ex.message); }
}

/* ---------- coupons ---------- */

const DISCOUNT_LABEL = { PERCENT: "率（%）", FIXED: "定額（円）", FREE_SHIPPING: "送料無料" };

function couponsCardHtml(coupons, today) {
    const rows = coupons.map((c) => {
        const used = c.maxRedemptions ? `${c.redeemedCount} / ${c.maxRedemptions}` : `${c.redeemedCount}`;
        return `
        <tr data-coupon="${c.id}" class="${c.activeToday ? "rate-active" : ""}">
            <td><input data-f="code" type="text" value="${escapeHtml(c.code)}" size="12"></td>
            <td>
                <select data-f="discountType">
                    ${Object.keys(DISCOUNT_LABEL).map((k) =>
                        `<option value="${k}" ${c.discountType === k ? "selected" : ""}>${DISCOUNT_LABEL[k]}</option>`).join("")}
                </select>
            </td>
            <td><input data-f="value" type="number" step="1" min="0" value="${c.value}"></td>
            <td><input data-f="minSubtotal" type="number" step="1" min="0" value="${c.minSubtotal ?? ""}"></td>
            <td><input data-f="validFrom" type="date" value="${c.validFrom || ""}"></td>
            <td><input data-f="validTo" type="date" value="${c.validTo || ""}"></td>
            <td><input data-f="maxRedemptions" type="number" step="1" min="0" value="${c.maxRedemptions ?? ""}"></td>
            <td class="rate-state">${used}</td>
            <td><input data-f="enabled" type="checkbox" ${c.enabled ? "checked" : ""}></td>
            <td class="rate-actions">
                <button class="btn ghost sm" data-act="save-coupon">保存</button>
                <button class="link-danger" data-act="del-coupon">削除</button>
            </td>
        </tr>`;
    }).join("");

    return `
        <section class="admin-card">
            <h2>クーポン</h2>
            <p class="hint">「率」は商品合計に対する割合、「定額」は商品価格と同じ流儀の金額です。
               <strong>割引は税額にも反映されます</strong>（割引後の金額に課税）。
               <br>終了日は<strong>その日を含みません</strong>（税率と同じ流儀）。「利用」は使われた回数／上限（空欄＝無制限）。
               <br>過去の注文にはコードと割引額が焼き付いているので、<strong>削除しても過去の金額は変わりません</strong>。</p>
            <div class="table-wrap">
                <table class="admin-table">
                    <thead><tr><th>コード</th><th>種類</th><th>値</th><th>最低金額</th><th>開始日</th>
                        <th>終了日</th><th>上限</th><th>利用</th><th>有効</th><th></th></tr></thead>
                    <tbody>${rows || '<tr><td colspan="10" class="empty">クーポンがありません。</td></tr>'}</tbody>
                </table>
            </div>
            <div class="rate-new">
                <h3>クーポンを追加</h3>
                <div class="rate-new-row">
                    <input id="newCouponCode" type="text" placeholder="コード（例 WELCOME500）" size="18">
                    <select id="newCouponType">
                        ${Object.keys(DISCOUNT_LABEL).map((k) =>
                            `<option value="${k}">${DISCOUNT_LABEL[k]}</option>`).join("")}
                    </select>
                    <input id="newCouponValue" type="number" step="1" min="0" placeholder="値">
                    <input id="newCouponMin" type="number" step="1" min="0" placeholder="最低金額（任意）">
                    <input id="newCouponFrom" type="date" value="${today}">
                    <input id="newCouponTo" type="date" placeholder="終了日（任意）">
                    <input id="newCouponMax" type="number" step="1" min="0" placeholder="上限（任意）">
                    <button class="btn" data-act="add-coupon">追加</button>
                </div>
            </div>
        </section>`;
}

function readCouponRow(tr) {
    const el = (f) => tr.querySelector(`[data-f="${f}"]`);
    const num = (f) => (el(f).value.trim() === "" ? null : Number(el(f).value));
    return {
        code: el("code").value.trim(),
        discountType: el("discountType").value,
        // 送料無料は値を使わないので、空欄でも 0 として通す。
        value: el("value").value.trim() === "" ? 0 : Number(el("value").value),
        minSubtotal: num("minSubtotal"),
        validFrom: el("validFrom").value || null,
        validTo: el("validTo").value || null,
        maxRedemptions: num("maxRedemptions"),
        enabled: el("enabled").checked,
    };
}

async function saveCoupon(tr) {
    const body = readCouponRow(tr);
    if (!body.code) { toast("コードを入力してください"); return; }
    try {
        await api(`/api/admin/coupons/${tr.dataset.coupon}`, { method: "PUT", auth: true, body });
        await afterCouponChange("クーポンを更新しました");
    } catch (ex) { toast(ex.message); }
}

async function deleteCoupon(tr) {
    if (!window.confirm("このクーポンを削除しますか？（過去の注文の金額は変わりません）")) return;
    try {
        await api(`/api/admin/coupons/${tr.dataset.coupon}`, { method: "DELETE", auth: true });
        await afterCouponChange("クーポンを削除しました");
    } catch (ex) { toast(ex.message); }
}

async function addCoupon() {
    const code = $("#newCouponCode").value.trim();
    if (!code) { toast("コードを入力してください"); return; }
    const raw = $("#newCouponValue").value.trim();
    const type = $("#newCouponType").value;
    if (type !== "FREE_SHIPPING" && raw === "") { toast("割引の値を入力してください"); return; }
    const optional = (id) => ($(id).value.trim() === "" ? null : Number($(id).value));
    try {
        await api("/api/admin/coupons", {
            method: "POST", auth: true,
            body: {
                code,
                discountType: type,
                value: raw === "" ? 0 : Number(raw),
                minSubtotal: optional("#newCouponMin"),
                validFrom: $("#newCouponFrom").value || null,
                validTo: $("#newCouponTo").value || null,
                maxRedemptions: optional("#newCouponMax"),
                enabled: true,
            },
        });
        await afterCouponChange("クーポンを追加しました");
    } catch (ex) { toast(ex.message); }
}

// カートに適用中のコードが無効化されたかもしれないので、カートも引き直す。
async function afterCouponChange(msg) {
    await refreshCart();
    await showAdmin();
    toast(msg);
}

/* ---------- product images ----------
   商品ごとに1枚。選ぶと即アップロードする（「選択」と「保存」を分けると、
   選んだだけで保存したつもりになる取り違えが起きるため）。
   サーバー側は先頭バイトで形式を判定するので、ここでの accept はあくまで
   ファイル選択ダイアログの絞り込み＝利便性であって、検証ではない。 */
function productImagesCardHtml(products) {
    if (products.length === 0) {
        return `<section class="admin-card"><h2>商品画像</h2>
            <p class="empty">商品が登録されていません。</p></section>`;
    }
    const rows = products.map((p) => {
        const img = imageStyle(p.imageUrl);
        const uploaded = (p.imageUrl || "").startsWith("images/uploads/");
        const source = !p.imageUrl ? "未設定"
            : uploaded ? "アップロード画像"
            : /^(https?:)?\/\//.test(p.imageUrl) ? "外部URL" : "同梱画像";
        return `
        <div class="img-row" data-product="${p.id}">
            <div class="img-thumb${p.imageUrl ? "" : " img-none"}" style="${img}"></div>
            <div class="img-main">
                <strong>${escapeHtml(p.name)}</strong>
                <span class="lmeta">${source}</span>
            </div>
            <div class="img-actions">
                <label class="btn ghost sm">
                    画像を選ぶ
                    <input type="file" accept="image/jpeg,image/png,image/webp" data-act="pick-image" hidden>
                </label>
                ${p.imageUrl ? '<button class="link-danger" data-act="drop-image">画像を外す</button>' : ""}
            </div>
        </div>`;
    }).join("");

    return `
        <section class="admin-card">
            <h2>商品画像</h2>
            <p class="hint">JPEG / PNG / WebP・1枚 2MB まで。選んだ時点で<strong>すぐ反映</strong>されます。
               <br>差し替えると古い画像ファイルは削除されます。「同梱画像」は初期データに含まれるもので、
               外すと商品からは消えますがファイルは残ります。</p>
            ${rows}
        </section>`;
}

async function uploadProductImage(row, file) {
    if (!file) return;
    const id = row.dataset.product;
    const form = new FormData();
    form.append("file", file);
    try {
        toast("アップロード中…");
        // FormData を送るときは Content-Type を自前で付けない（boundary が壊れる）。
        const res = await fetch(`${BASE}/api/admin/products/${id}/image`, {
            method: "POST",
            headers: state.token ? { Authorization: "Bearer " + state.token } : {},
            body: form,
        });
        const text = await res.text();
        const data = text ? JSON.parse(text) : null;
        if (!res.ok) {
            throw new Error((data && data.message)
                || (res.status === 503 ? "サーバー側で画像の保存先を用意できていません" : `${res.status} ${res.statusText}`));
        }
        await afterProductImageChange("画像を更新しました");
    } catch (ex) { toast(ex.message); }
}

async function dropProductImage(row) {
    if (!window.confirm("この商品の画像を外しますか？")) return;
    try {
        await api(`/api/admin/products/${row.dataset.product}/image`, { method: "DELETE", auth: true });
        await afterProductImageChange("画像を外しました");
    } catch (ex) { toast(ex.message); }
}

// 画像は買い手側の一覧にもそのまま出るので、税率変更と同じく店頭を描き直す。
async function afterProductImageChange(msg) {
    await loadProducts();
    await showAdmin();
    toast(msg);
}

/* Which rows are actually in force today — mirrors TaxRateRepository.findEffective:
     effectiveFrom <= today  AND  (effectiveTo IS NULL OR today < effectiveTo)
   …then, per category, the server takes the FIRST of "ORDER BY effectiveFrom DESC".
   So overlapping rows exist happily (adding 12% doesn't require closing the old 10%),
   but only the newest one wins. Note effectiveTo is EXCLUSIVE: a row ending 2026-12-31
   is not in force on that day. */
function effectiveRateIds(rates, today) {
    const winner = {};
    for (const r of rates) {
        if (r.effectiveFrom > today) continue;
        if (r.effectiveTo && !(today < r.effectiveTo)) continue;
        const cur = winner[r.category];
        if (!cur || r.effectiveFrom > cur.effectiveFrom) winner[r.category] = r;
    }
    return new Set(Object.values(winner).map((r) => r.id));
}

async function setPricingMode(pricingMode) {
    try {
        await api("/api/admin/settings/pricing-mode", { method: "PUT", auth: true, body: { pricingMode } });
        await afterTaxChange(pricingMode === "EXCLUSIVE" ? "外税に切り替えました" : "内税に切り替えました");
    } catch (ex) { toast(ex.message); }
}

function readRateRow(tr) {
    const val = (f) => tr.querySelector(`[data-f="${f}"]`).value;
    return {
        category: val("category"),
        // Keep the raw string: Number("") is 0, which would silently save a 0% rate.
        rateRaw: val("ratePercent").trim(),
        ratePercent: Number(val("ratePercent")),
        effectiveFrom: val("effectiveFrom"),
        effectiveTo: val("effectiveTo") || null,
    };
}

async function saveRate(tr) {
    const id = tr.dataset.rate;
    const { rateRaw, ...body } = readRateRow(tr);
    if (rateRaw === "" || Number.isNaN(body.ratePercent)) { toast("税率を入力してください"); return; }
    if (!body.effectiveFrom) { toast("開始日を入力してください"); return; }
    try {
        await api(`/api/admin/tax-rates/${id}`, { method: "PUT", auth: true, body });
        await afterTaxChange("税率を更新しました");
    } catch (ex) { toast(ex.message); }
}

async function deleteRate(tr) {
    if (!window.confirm("この税率を削除しますか？（過去の注文の税額は変わりません）")) return;
    try {
        await api(`/api/admin/tax-rates/${tr.dataset.rate}`, { method: "DELETE", auth: true });
        await afterTaxChange("税率を削除しました");
    } catch (ex) { toast(ex.message); }
}

async function addRate() {
    const rateRaw = $("#newRate").value.trim();
    const body = {
        category: $("#newCategory").value,
        ratePercent: Number(rateRaw),
        effectiveFrom: $("#newFrom").value,
        effectiveTo: $("#newTo").value || null,
    };
    // Empty input must not become 0% — Number("") === 0 would sneak past a falsy check.
    if (rateRaw === "" || Number.isNaN(body.ratePercent)) { toast("税率を入力してください"); return; }
    if (!body.effectiveFrom) { toast("開始日を入力してください"); return; }
    try {
        await api("/api/admin/tax-rates", { method: "POST", auth: true, body });
        await afterTaxChange("税率を追加しました");
    } catch (ex) { toast(ex.message); }
}

// A tax change alters how the whole storefront displays prices — repull the public
// config and repaint, so the admin sees the same thing a shopper would.
async function afterTaxChange(msg) {
    await loadTaxConfig();
    await loadProducts();
    await refreshCart();
    await showAdmin();
    toast(msg);
}

/* ---------- guest order lookup (#/orders/guest) ----------
   Wraps GET /api/orders/guest/{id}?token=… — the one public endpoint that had no UI.
   Without this screen the orderToken was shown once in the checkout banner and then
   effectively lost, so a guest could never come back to check or pay an order. */

async function showGuestOrders(id, token) {
    hideViews();
    const box = $("#guestOrders");
    box.classList.remove("hidden");
    box.innerHTML = guestLookupShellHtml(id, token);
    window.scrollTo({ top: 0 });
    if (!id || !token) return;

    const res = $("#lookupResult");
    res.innerHTML = '<p class="empty">照会中…</p>';
    try {
        const order = await api(`/api/orders/guest/${id}?token=${encodeURIComponent(token)}`);
        res.innerHTML = guestOrderCardHtml(order, token);
    } catch {
        // The API answers 404 for both "no such order" and "wrong token", so a single
        // message is honest here and does not reveal which order ids exist.
        res.innerHTML = `<p class="lookup-error">注文が見つかりませんでした。注文番号と照会用トークンをご確認ください。</p>`;
    }
}

function guestLookupShellHtml(id, token) {
    const saved = guestOrderLog();
    const rows = saved.map((o) => {
        const when = o.at ? new Date(o.at).toLocaleString("ja-JP") : "";
        return `
        <div class="lookup-row">
            <div class="lookup-row-main">
                <strong>注文 #${o.id}</strong>
                <span class="lmeta">${escapeHtml(when)}${o.total != null ? " / " + yen(o.total) : ""}</span>
            </div>
            <div class="lookup-row-actions">
                <button class="btn ghost sm" data-act="open-guest-order" data-id="${o.id}">開く</button>
                <button class="link-danger" data-act="forget-guest-order" data-id="${o.id}">記録を削除</button>
            </div>
        </div>`;
    }).join("");

    const savedSection = saved.length === 0 ? "" : `
        <section class="admin-card">
            <h2>この端末に記録された注文</h2>
            <p class="hint">ご注文時にこのブラウザへ保存されたものです。<strong>サーバーには一覧がありません</strong>（アカウントが無いため）。
               共用のパソコンでは、確認後に記録を削除してください。</p>
            ${rows}
            <button class="linklike" data-act="forget-all-guest-orders">この端末の記録をすべて削除する</button>
        </section>`;

    return `
        <a href="#/" class="back-link">← 商品一覧へ戻る</a>
        <h1 class="admin-title">🔎 注文照会（ゲスト）</h1>

        <section class="admin-card">
            <h2>注文番号とトークンで照会</h2>
            <p class="hint">ゲスト購入の際にお伝えした<strong>注文番号</strong>と<strong>照会用トークン</strong>を入力してください。
               ログインは不要です。</p>
            <form id="lookupForm" class="lookup-form">
                <label class="field">
                    <span>注文番号</span>
                    <input id="lookupId" type="number" min="1" inputmode="numeric" placeholder="12"
                           value="${id != null ? id : ""}" required>
                </label>
                <label class="field">
                    <span>照会用トークン</span>
                    <input id="lookupToken" type="text" autocomplete="off" spellcheck="false"
                           placeholder="00000000-0000-0000-0000-000000000000"
                           value="${token ? escapeHtml(token) : ""}" required>
                </label>
                <button type="submit" class="btn">照会する</button>
            </form>
        </section>

        ${savedSection}

        <div id="lookupResult"></div>`;
}

function guestOrderCardHtml(order, token) {
    const when = new Date(order.createdAt).toLocaleString("ja-JP");
    const lines = order.items.map((i) =>
        `<div class="lmeta">${escapeHtml(i.productName)} × ${i.quantity} — ${yen(i.lineTotal)}</div>`).join("");
    // The lookup response never carries the token back, so reuse the one we came in with.
    const pay = order.status === "PENDING" ? payButtonsHtml(order.id, token) : "";
    const note = order.status === "EXPIRED"
        ? '<p class="hint" style="text-align:left">お支払い期限が過ぎたため、確保していた在庫は解放されました。お手数ですが再度ご注文ください。</p>'
        : "";
    return `
        <section class="admin-card">
            <div class="oh">
                <strong>注文 #${order.id}</strong>
                <span class="badge ${escapeHtml(order.status)}">${escapeHtml(statusLabel(order.status))}</span>
            </div>
            <div class="lmeta">${escapeHtml(when)} / ${escapeHtml(order.userEmail || "")}</div>
            <div style="margin-top:8px">${lines}</div>
            ${taxBreakdownHtml(order)}
            ${note}
            ${pay}
        </section>`;
}

function guestLookupLink(id, token) {
    return `#/orders/guest/${id}/${encodeURIComponent(token)}`;
}

function submitGuestLookup(e) {
    e.preventDefault();
    const id = $("#lookupId").value.trim();
    const token = $("#lookupToken").value.trim();
    if (!id || !token) { toast("注文番号とトークンを入力してください"); return; }
    // Route rather than fetch directly: the result becomes a bookmarkable URL and
    // the browser Back button behaves.
    location.hash = guestLookupLink(Number(id), token);
}

function openSavedGuestOrder(id) {
    const hit = guestOrderLog().find((o) => o.id === id);
    if (!hit) { toast("記録が見つかりません"); return; }
    location.hash = guestLookupLink(hit.id, hit.token);
}

/* ---------- auth ---------- */
function isLoggedIn() { return !!state.token; }

function reflectAuth() {
    const logged = isLoggedIn();
    $("#authBtn").classList.toggle("hidden", logged);
    $("#logoutBtn").classList.toggle("hidden", !logged);
    $("#ordersBtn").classList.toggle("hidden", !logged);
    // Members already have 注文履歴; 注文照会 is the guest-only equivalent.
    $("#guestOrdersBtn").classList.toggle("hidden", logged);
    $("#adminBtn").classList.toggle("hidden", !isAdmin());
    const label = $("#userLabel");
    label.classList.toggle("hidden", !logged);
    label.textContent = state.user ? `${state.user.name} さん` : "";
}

function openAuth() {
    $("#authError").classList.add("hidden");
    $("#authModal").classList.remove("hidden");
    $("#f-email").focus();
}
function closeAuth() { $("#authModal").classList.add("hidden"); }

function setAuthTab(tab) {
    $$(".tab").forEach((t) => t.classList.toggle("active", t.dataset.tab === tab));
    const isRegister = tab === "register";
    $("#nameField").classList.toggle("hidden", !isRegister);
    $("#f-name").required = isRegister;
    $("#authSubmit").textContent = isRegister ? "登録して開始" : "ログイン";
    $("#authForm").dataset.mode = tab;
}

async function submitAuth(e) {
    e.preventDefault();
    const mode = $("#authForm").dataset.mode || "login";
    const email = $("#f-email").value.trim();
    const password = $("#f-password").value;
    const err = $("#authError");
    err.classList.add("hidden");
    try {
        const body = mode === "register"
            ? { email, password, name: $("#f-name").value.trim() }
            : { email, password };
        const res = await api(`/api/auth/${mode}`, { method: "POST", body });
        // 二段階認証が有効なアカウント。サーバーはトークンを返しておらず、
        // 引換券（mfaToken）だけが来ている。認証はまだ完了していない。
        if (res.mfaRequired) { showMfaStep(res.mfaToken); return; }
        await completeLogin(res);
    } catch (ex) {
        err.textContent = ex.message;
        err.classList.remove("hidden");
    }
}

/** ログイン成立後の共通処理（一段階でも二段階でもここへ合流する）。 */
async function completeLogin(res) {
    state.token = res.token;
    state.user = res.user;
    localStorage.setItem("ec_token", res.token);
    closeAuth();
    reflectAuth();
    await mergeGuestCartIfAny();
    await refreshCart();
    toast(`ようこそ、${res.user.name} さん`);
}

/* ---------- 二段階目 ---------- */

function showMfaStep(mfaToken) {
    state.mfaToken = mfaToken;
    $("#authForm").classList.add("hidden");
    $$(".tabs").forEach((t) => t.classList.add("hidden"));
    $("#mfaStep").classList.remove("hidden");
    $("#mfaError").classList.add("hidden");
    $("#f-mfa-code").value = "";
    $("#f-mfa-code").focus();
}

function hideMfaStep() {
    state.mfaToken = null;
    $("#mfaStep").classList.add("hidden");
    $("#authForm").classList.remove("hidden");
    $$(".tabs").forEach((t) => t.classList.remove("hidden"));
}

async function submitMfa(e) {
    e.preventDefault();
    const err = $("#mfaError");
    err.classList.add("hidden");
    try {
        const res = await api("/api/auth/mfa", {
            method: "POST",
            body: { mfaToken: state.mfaToken, code: $("#f-mfa-code").value.trim() },
        });
        hideMfaStep();
        await completeLogin(res);
    } catch (ex) {
        err.textContent = ex.message;
        err.classList.remove("hidden");
        $("#f-mfa-code").select();
    }
}

function logout() {
    state.token = null;
    state.user = null;
    localStorage.removeItem("ec_token");
    state.guestMode = "choice"; // back to the guest method choice
    reflectAuth();
    updateCartCount(guestCartView()); // fall back to the guest cart badge
    closeDrawers();
    if (currentRoute().name === "admin") location.hash = "#/"; // leave the admin view
    toast("ログアウトしました");
}

// When a guest logs in, carry their client-side cart into the server cart.
async function mergeGuestCartIfAny() {
    const items = guestCart();
    if (items.length === 0) return;
    for (const it of items) {
        try {
            await api("/api/cart/items", { method: "POST", auth: true, body: { productId: it.productId, quantity: it.quantity } });
        } catch { /* skip items that no longer fit stock */ }
    }
    saveGuestCart([]);
    toast("カートを引き継ぎました");
}

async function restoreSession() {
    if (!state.token) return;
    try {
        state.user = await api("/api/auth/me", { auth: true });
        reflectAuth();
        await refreshCart();
    } catch {
        logout();
    }
}

/* ---------- cart ---------- */
function updateCartCount(cart) {
    $("#cartCount").textContent = cart ? cart.totalQuantity : 0;
}

async function refreshCart() {
    if (!isLoggedIn()) { const v = guestCartView(); updateCartCount(v); renderCart(v); return v; }
    const cart = await api("/api/cart", { auth: true });
    updateCartCount(cart);
    renderCart(cart);
    return cart;
}

async function addToCart(productId) {
    if (!isLoggedIn()) { addGuestItem(productId); return; }
    try {
        const cart = await api("/api/cart/items", { method: "POST", auth: true, body: { productId, quantity: 1 } });
        updateCartCount(cart);
        renderCart(cart);
        toast("カートに追加しました");
    } catch (ex) { toast(ex.message); }
}

// Guest add: look the product up from the last-rendered grid, cap at available.
function addGuestItem(productId) {
    const p = state.productsById[productId];
    if (!p) { toast("商品情報が見つかりません"); return; }
    const avail = (p.available != null) ? p.available : p.stock;
    const items = guestCart();
    const existing = items.find((i) => i.productId === productId);
    const nextQty = (existing ? existing.quantity : 0) + 1;
    if (nextQty > avail) { toast("在庫が足りません"); return; }
    if (existing) existing.quantity = nextQty;
    else items.push({ productId, quantity: 1, name: p.name, price: p.price, imageUrl: p.imageUrl, taxCategory: p.taxCategory || "STANDARD" });
    saveGuestCart(items);
    const v = guestCartView();
    updateCartCount(v);
    renderCart(v);
    toast("カートに追加しました");
}

async function setQty(productId, quantity) {
    if (!isLoggedIn()) { setGuestQty(productId, quantity); return; }
    try {
        let cart;
        if (quantity <= 0) {
            cart = await api(`/api/cart/items/${productId}`, { method: "DELETE", auth: true });
        } else {
            cart = await api(`/api/cart/items/${productId}`, { method: "PUT", auth: true, body: { quantity } });
        }
        updateCartCount(cart);
        renderCart(cart);
    } catch (ex) { toast(ex.message); }
}

function setGuestQty(productId, quantity) {
    let items = guestCart();
    if (quantity <= 0) {
        items = items.filter((i) => i.productId !== productId);
    } else {
        const it = items.find((i) => i.productId === productId);
        if (it) {
            const p = state.productsById[productId];
            const avail = p ? ((p.available != null) ? p.available : p.stock) : quantity;
            if (quantity > avail) { toast("在庫が足りません"); return; }
            it.quantity = quantity;
        }
    }
    saveGuestCart(items);
    const v = guestCartView();
    updateCartCount(v);
    renderCart(v);
}

function renderCart(cart) {
    const box = $("#cartItems");
    const items = (cart && cart.items) || [];
    if (items.length === 0) {
        box.innerHTML = '<p class="empty">カートは空です。</p>';
    } else {
        box.innerHTML = items.map((it) => {
            const p = it.product;
            const img = imageStyle(p.imageUrl);
            return `
            <div class="line">
                <div class="lthumb" style="${img}"></div>
                <div class="lmain">
                    <div class="lname">${escapeHtml(p.name)}</div>
                    <div class="lmeta">${yen(p.price)} / 点</div>
                    <div class="qty" data-id="${p.id}">
                        <button data-act="dec">−</button>
                        <span>${it.quantity}</span>
                        <button data-act="inc">＋</button>
                        <button class="link-danger" data-act="rm">削除</button>
                    </div>
                </div>
                <div class="lprice">${yen(it.lineTotal)}</div>
            </div>`;
        }).join("");
    }
    // 金額はサーバーに聞く。描画をブロックしないよう待たない（届いたら差し替わる）。
    renderCartTotals(items);

    // Checkout UI has three shapes:
    //   member                     → the normal 「注文を確定する」 button
    //   guest + choosing            → 会員/ゲスト の選択ボタン（choice）
    //   guest + entered guest mode  → メール欄 ＋ 「この内容で注文する」 ＋ 選び直しリンク
    const guest = !isLoggedIn();
    const hasItems = items.length > 0;
    if (guest && !hasItems) state.guestMode = "choice"; // reset once the cart is empty
    const choosing = guest && hasItems && state.guestMode === "choice";
    const enteringGuest = guest && hasItems && state.guestMode === "guest";

    $("#guestChoice").classList.toggle("hidden", !choosing);
    $("#guestEmailField").classList.toggle("hidden", !enteringGuest);
    $("#guestHint").classList.toggle("hidden", !enteringGuest);
    $("#guestBackBtn").classList.toggle("hidden", !enteringGuest);
    // Hide the main button only while the guest is still choosing a method.
    $("#checkoutBtn").classList.toggle("hidden", choosing);
    $("#checkoutBtn").disabled = !hasItems;
    $("#checkoutBtn").textContent = guest ? "この内容で注文する" : "注文を確定する";
}

/* Cart footer figures.
   These used to be estimated here in the browser, which meant the cart could disagree
   with the invoice — and once 送料 and クーポン entered the picture the estimate would
   have had to reimplement the discount allocation to stay honest. Instead the server
   quotes the cart with the SAME code that will charge for it (/api/checkout/quote), so
   there is exactly one implementation of the arithmetic and nothing to keep in sync. */
async function renderCartTotals(items) {
    const box = $("#cartTax");
    const note = $("#cartTaxNote");

    if (items.length === 0) {
        state.quote = null;
        box.classList.add("hidden");
        box.innerHTML = "";
        note.classList.add("hidden");
        $("#couponRow").classList.add("hidden");
        $("#couponState").classList.add("hidden");
        $("#cartTotalLabel").textContent = "合計";
        $("#cartTotal").textContent = yen(0);
        return;
    }
    $("#couponRow").classList.remove("hidden");

    const lines = items.map((it) => ({ productId: it.product.id, quantity: it.quantity }));
    let quote;
    try {
        quote = await api("/api/checkout/quote", {
            method: "POST",
            body: { items: lines, couponCode: state.couponCode || null },
        });
        setCouponState(state.couponCode ? `クーポン ${state.couponCode} を適用中` : "", false);
    } catch (ex) {
        // ほぼ「クーポンが使えない」。クーポンを外した金額は出し続ける
        // （金額が消えるより、理由が出て素の金額が見えるほうがよい）。
        if (state.couponCode) {
            setCouponState(ex.message, true);
            state.couponCode = null;
            try {
                quote = await api("/api/checkout/quote", { method: "POST", body: { items: lines } });
            } catch { quote = null; }
        } else {
            setCouponState(ex.message, true);
            quote = null;
        }
    }
    state.quote = quote;

    if (!quote) {
        box.classList.add("hidden");
        note.classList.add("hidden");
        $("#cartTotal").textContent = yen(0);
        return;
    }

    const rows = [`<div><span>小計（税抜）</span><span>${yen(quote.itemSubtotal)}</span></div>`];
    if (Number(quote.discount) > 0) {
        rows.push(`<div class="disc"><span>割引${quote.couponCode ? "（" + escapeHtml(quote.couponCode) + "）" : ""}</span>`
            + `<span>−${yen(quote.discount)}</span></div>`);
    }
    rows.push(`<div><span>送料</span><span>${quote.freeShipping ? "無料" : yen(quote.shipping)}</span></div>`);
    rows.push(`<div><span>消費税</span><span>${yen(quote.tax)}</span></div>`);
    box.innerHTML = rows.join("");
    box.classList.remove("hidden");

    $("#cartTotalLabel").textContent = "合計（税込）";
    $("#cartTotal").textContent = yen(quote.total);

    // 「あと○円で送料無料」。届いていない時だけ出す（届いた後に出しても意味がない）。
    const remaining = shippingGapToFree(quote);
    note.textContent = remaining > 0 ? `あと ${yen(remaining)} で送料無料になります。` : "";
    note.classList.toggle("hidden", remaining <= 0);
}

/** 送料無料まであといくらか。しきい値未設定・到達済みなら 0。 */
function shippingGapToFree(quote) {
    const threshold = Number(state.shipping.freeThreshold || 0);
    if (threshold <= 0 || quote.freeShipping) return 0;
    // しきい値は「割引後の商品合計」に対する判定なので、同じ基準で残りを出す。
    const itemsAfterDiscount = Number(quote.total) - Number(quote.shipping) - Number(quote.shippingTax);
    return Math.max(0, threshold - itemsAfterDiscount);
}

function setCouponState(message, isError) {
    const el = $("#couponState");
    el.textContent = message || "";
    el.classList.toggle("err", !!isError);
    el.classList.toggle("hidden", !message);
}

async function applyCoupon() {
    const code = $("#couponInput").value.trim();
    state.couponCode = code || null;
    await refreshCart();
    if (state.couponCode && state.quote && state.quote.couponCode) {
        $("#couponInput").value = "";
    }
}

async function checkout() {
    if (!isLoggedIn()) { return guestCheckout(); }
    try {
        const order = await api("/api/orders/checkout", {
            method: "POST", auth: true, body: { couponCode: state.couponCode || null },
        });
        state.couponCode = null;   // 使い切り。次のカートに持ち越さない
        state.lastOrder = order;
        await refreshCart();
        closeDrawers();
        showOrderResult(order);
    } catch (ex) { toast(ex.message); }
}

async function guestCheckout() {
    const email = $("#guestEmail").value.trim();
    if (!email) { toast("メールアドレスを入力してください"); $("#guestEmail").focus(); return; }
    const items = guestCart().map((i) => ({ productId: i.productId, quantity: i.quantity }));
    if (items.length === 0) { toast("カートが空です"); return; }
    try {
        const order = await api("/api/orders/guest-checkout", {
            method: "POST", body: { email, items, couponCode: state.couponCode || null },
        });
        state.couponCode = null;
        saveGuestCart([]);
        state.guestMode = "choice"; // next guest visit starts from the method choice again
        rememberGuestOrder(order);
        state.lastOrder = order;
        await refreshCart();
        closeDrawers();
        showOrderResult(order);
        loadProducts(); // reflect the newly reserved stock in the grid
    } catch (ex) { toast(ex.message); }
}

/* ---------- orders ---------- */
async function openOrders() {
    if (!isLoggedIn()) { openAuth(); return; }
    try {
        const page = await api("/api/orders?size=20&sort=createdAt,desc", { auth: true });
        renderOrders(page.content || []);
        openDrawer("#ordersDrawer");
    } catch (ex) { toast(ex.message); }
}

function renderOrders(orders) {
    const box = $("#ordersBody");
    if (orders.length === 0) { box.innerHTML = '<p class="empty">注文はまだありません。</p>'; return; }
    box.innerHTML = orders.map((o) => {
        const when = new Date(o.createdAt).toLocaleString("ja-JP");
        const lines = o.items.map((i) => `<div class="lmeta">${escapeHtml(i.productName)} × ${i.quantity} — ${yen(i.lineTotal)}</div>`).join("");
        const payBtn = o.status === "PENDING" ? payButtonsHtml(o.id, null) : "";
        return `
        <div class="order-block">
            <div class="oh">
                <strong>注文 #${o.id}</strong>
                <span class="badge ${escapeHtml(o.status)}">${escapeHtml(statusLabel(o.status))}</span>
            </div>
            <div class="lmeta">${when}</div>
            ${lines}
            ${taxBreakdownHtml(o)}
            ${payBtn}
        </div>`;
    }).join("");
}

/* 注文のスナップショットをそのまま並べる: 小計 − 割引 + 送料 + 税 = 合計。
   割引・送料の行は 0 のときは出さない（無いものを「¥0」と書いても情報が増えない）。
   過去の注文にはそもそも列が無く 0 で入っているので、同じ扱いで正しく出る。 */
function taxBreakdownHtml(order) {
    if (order.subtotalAmount == null || order.taxAmount == null) return "";
    const rows = [`<div><span>小計（税抜）</span><span>${yen(order.subtotalAmount)}</span></div>`];
    if (Number(order.discountAmount) > 0) {
        rows.push(`<div class="disc"><span>割引${order.couponCode ? "（" + escapeHtml(order.couponCode) + "）" : ""}</span>`
            + `<span>−${yen(order.discountAmount)}</span></div>`);
    }
    if (Number(order.shippingAmount) > 0) {
        rows.push(`<div><span>送料（税抜）</span><span>${yen(order.shippingAmount)}</span></div>`);
    }
    rows.push(`<div><span>消費税</span><span>${yen(order.taxAmount)}</span></div>`);
    rows.push(`<div class="tb-total"><span>合計（税込）</span><span>${yen(order.totalAmount)}</span></div>`);
    return `<div class="tax-breakdown">${rows.join("")}</div>`;
}

function showOrderResult(order) {
    const banner = $("#banner");
    let html = `✅ 注文 #${order.id} を受け付けました（状態 ${escapeHtml(statusLabel(order.status))}）。`;
    html += taxBreakdownHtml(order);
    if (order.guest && order.orderToken) {
        // The token is shown once here, but it is also stored on this browser and the
        // link below reaches the order again — so closing this banner is not fatal.
        html += `<div class="hint" style="margin-top:6px;text-align:left">
            照会・支払い用トークン: <code>${escapeHtml(order.orderToken)}</code><br>
            ログインなしで注文を追跡できます。大切に保管してください。<br>
            <a href="${guestLookupLink(order.id, order.orderToken)}">🔎 この注文の照会ページを開く</a>
            （このブラウザなら「注文照会」からいつでも開けます）
        </div>`;
    }
    if (state.paymentsEnabled && order.status === "PENDING") {
        html += " " + payButtonsHtml(order.id, order.guest && order.orderToken ? order.orderToken : null);
    } else if (!state.paymentsEnabled) {
        html += " （決済手段が未設定のためスキップ）";
    }
    banner.innerHTML = html;
    banner.classList.remove("hidden");
    window.scrollTo({ top: 0, behavior: "smooth" });
}

// 決済手段のボタンはサーバーが返す一覧から組み立てる。手段を1つ足しても
// （PaymentProvider の実装を追加しても）このコードは変更不要。
function payButtonsHtml(orderId, token) {
    if (!state.paymentsEnabled) return "";
    const tokenAttr = token ? ` data-token="${escapeHtml(token)}"` : "";
    return state.paymentProviders.map((p) =>
        `<button class="btn pay-btn" data-order="${orderId}" data-provider="${escapeHtml(p.id)}"${tokenAttr} style="margin-left:8px">${escapeHtml(p.displayName)}で支払う</button>`
    ).join("");
}

// token present → guest order (public endpoint); absent → the logged-in user's order.
async function startPayment(orderId, token, providerId) {
    try {
        toast("支払い画面へ移動します…");
        const base = token
            ? `/api/payments/guest/orders/${orderId}/checkout-session?token=${encodeURIComponent(token)}`
            : `/api/payments/orders/${orderId}/checkout-session`;
        const path = providerId
            ? `${base}${base.includes("?") ? "&" : "?"}provider=${encodeURIComponent(providerId)}`
            : base;
        const res = await api(path, { method: "POST", auth: !token });
        window.location.href = res.redirectUrl;
    } catch (ex) { toast(ex.message); }
}

/* ---------- drawers ---------- */
function openDrawer(sel) {
    $("#scrim").classList.remove("hidden");
    $(sel).classList.remove("hidden");
}
function closeDrawers() {
    $("#scrim").classList.add("hidden");
    $("#cartDrawer").classList.add("hidden");
    $("#ordersDrawer").classList.add("hidden");
}

/* ---------- wire up ---------- */
function bind() {
    $("#search").addEventListener("input", () => { clearTimeout(searchTimer); searchTimer = setTimeout(loadProducts, 250); });
    $("#category").addEventListener("change", loadProducts);

    $("#authBtn").addEventListener("click", openAuth);
    $("#logoutBtn").addEventListener("click", logout);
    $("#authForm").addEventListener("submit", submitAuth);
    $("#mfaStep").addEventListener("submit", submitMfa);
    $$(".tab").forEach((t) => t.addEventListener("click", () => setAuthTab(t.dataset.tab)));

    $("#cartBtn").addEventListener("click", async () => {
        await refreshCart(); // works for guests (localStorage) and logged-in users alike
        openDrawer("#cartDrawer");
    });
    $("#ordersBtn").addEventListener("click", openOrders);
    $("#guestOrdersBtn").addEventListener("click", () => { closeDrawers(); location.hash = "#/orders/guest"; });
    $("#adminBtn").addEventListener("click", () => { location.hash = "#/admin"; });
    $("#checkoutBtn").addEventListener("click", checkout);
    $("#couponApplyBtn").addEventListener("click", applyCoupon);
    $("#couponInput").addEventListener("keydown", (e) => {
        // Enter で適用できないと、入力欄の隣にボタンがあっても押し忘れる。
        if (e.key === "Enter") { e.preventDefault(); applyCoupon(); }
    });

    // Guest checkout method choice
    $("#chooseMemberBtn").addEventListener("click", openAuth);       // login → cart merges → member checkout
    $("#chooseGuestBtn").addEventListener("click", () => {
        state.guestMode = "guest";
        renderCart(guestCartView());
        $("#guestEmail").focus();
    });
    $("#guestBackBtn").addEventListener("click", () => {
        state.guestMode = "choice";
        renderCart(guestCartView());
    });

    $("#scrim").addEventListener("click", closeDrawers);

    // The lookup form lives inside a re-rendered view, so delegate (submit bubbles).
    document.body.addEventListener("submit", (e) => {
        if (e.target.id === "lookupForm") submitGuestLookup(e);
    });

    document.body.addEventListener("click", (e) => {
        const el = e.target.closest("[data-close]");
        if (el) {
            const w = el.dataset.close;
            if (w === "auth") closeAuth(); else closeDrawers();
            return;
        }
        const add = e.target.closest(".add-btn");
        if (add) { addToCart(Number(add.dataset.id)); return; }

        const pay = e.target.closest(".pay-btn");
        if (pay) { startPayment(Number(pay.dataset.order), pay.dataset.token || null, pay.dataset.provider || null); return; }

        // Admin panel actions (the panel is re-rendered, so delegate rather than bind).
        const act = e.target.closest("[data-act]");
        if (act) {
            const kind = act.dataset.act;
            if (kind === "mode") { setPricingMode(act.dataset.mode); return; }
            if (kind === "add-rate") { addRate(); return; }
            if (kind === "save-shipping") { saveShipping(); return; }
            if (kind === "mfa-setup") { startMfaSetup(); return; }
            if (kind === "mfa-confirm") { confirmMfa(); return; }
            if (kind === "mfa-disable") { disableMfa(); return; }
            if (kind === "mfa-done") { showAdmin(); return; }
            if (kind === "add-coupon") { addCoupon(); return; }
            if (kind === "open-guest-order") { openSavedGuestOrder(Number(act.dataset.id)); return; }
            if (kind === "forget-guest-order") {
                forgetGuestOrder(Number(act.dataset.id));
                route();                       // repaint the list without this entry
                toast("記録を削除しました");
                return;
            }
            if (kind === "forget-all-guest-orders") {
                if (!window.confirm("この端末に記録された注文をすべて削除しますか？\n（注文自体は取り消されません。トークンを控えていないと再照会できなくなります）")) return;
                saveGuestOrderLog([]);
                // Leave a deep link if we were on one; repaint in place otherwise.
                if (location.hash === "#/orders/guest") route(); else location.hash = "#/orders/guest";
                toast("記録を削除しました");
                return;
            }
            const imgRow = act.closest(".img-row");
            if (imgRow && kind === "drop-image") { dropProductImage(imgRow); return; }
            const couponRow = act.closest("tr[data-coupon]");
            if (couponRow && kind === "save-coupon") { saveCoupon(couponRow); return; }
            if (couponRow && kind === "del-coupon") { deleteCoupon(couponRow); return; }
            const tr = act.closest("tr[data-rate]");
            if (tr && kind === "save-rate") { saveRate(tr); return; }
            if (tr && kind === "del-rate") { deleteRate(tr); return; }
        }

        const qbtn = e.target.closest(".qty button");
        if (qbtn) {
            const wrap = qbtn.closest(".qty");
            const id = Number(wrap.dataset.id);
            const cur = Number(wrap.querySelector("span").textContent);
            const act = qbtn.dataset.act;
            if (act === "inc") setQty(id, cur + 1);
            else if (act === "dec") setQty(id, cur - 1);
            else if (act === "rm") setQty(id, 0);
        }
    });

    // 商品画像の <input type="file">。click ではなく change で拾う必要があるので、
    // 上のクリック委譲とは別に張る（管理パネルは再描画されるため要素直付けはしない）。
    document.addEventListener("change", (e) => {
        const picker = e.target.closest('[data-act="pick-image"]');
        if (!picker) return;
        const row = picker.closest(".img-row");
        const file = picker.files && picker.files[0];
        picker.value = "";   // 同じファイルを選び直しても change が発火するようにする
        if (row) uploadProductImage(row, file);
    });
}

async function init() {
    bind();
    setAuthTab("login");
    try {
        const cfg = await api("/api/payments/config");
        state.paymentsEnabled = cfg.enabled;
        state.paymentProviders = cfg.providers || [];
        if (!cfg.enabled) {
            $("#banner").textContent = "🧪 デモモード: 決済手段が未設定のためスキップされます（注文までは動作します）。";
            $("#banner").classList.remove("hidden");
        }
    } catch { /* ignore */ }
    try {
        await loadTaxConfig();
    } catch { /* keep defaults */ }
    try {
        await loadShippingConfig();
    } catch { /* 「あと○円で送料無料」が出ないだけ。金額は見積もりAPIが返す */ }
    await Promise.all([loadCategories(), loadProducts()]);
    await restoreSession();
    if (!isLoggedIn()) updateCartCount(guestCartView()); // show guest cart badge on load
    reflectAuth();
    window.addEventListener("hashchange", route);
    await route(); // honor a deep link like #/product/3 on first load
}

init();
