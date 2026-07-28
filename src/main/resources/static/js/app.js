"use strict";

/* ---------- tiny helpers ---------- */
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));
const yen = (n) => "¥" + Number(n || 0).toLocaleString("ja-JP", { maximumFractionDigits: 0 });

// The app can be served at the site root ("/") locally or under a sub-path
// (e.g. "/ec/" behind Caddy on the Oracle VM). Derive the base from this
// script's own URL — <BASE>/js/app.js — so no build-time config is needed.
// Result: "" at the root, "/ec" under /ec.
const SELF = document.currentScript || document.scripts[document.scripts.length - 1];
const BASE = new URL("..", SELF.src).pathname.replace(/\/$/, "");

// 商品画像は同梱SVG（相対パス "images/products/x.svg"）でも、外部URLでも受け付ける。
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
    // { STANDARD: 10, REDUCED: 8 } — currently-effective rates, for the cart estimate.
    taxRates: {},
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

/* ---------- cart-side tax estimate ----------
   Mirrors TaxService: per-line, truncated to whole yen (app.tax.rounding=FLOOR).
   INCLUSIVE → tax = floor(line * rate / (100 + rate))   (line is tax-included)
   EXCLUSIVE → tax = floor(line * rate / 100)            (line is tax-exclusive)
   This is an ESTIMATE for display only — the authoritative figures are snapshotted
   onto the order at checkout, so a rate change mid-session can't desync history. */
function lineTax(lineAmount, ratePercent, mode) {
    if (!ratePercent) return 0;
    const divisor = mode === "EXCLUSIVE" ? 100 : 100 + ratePercent;
    return Math.floor((lineAmount * ratePercent) / divisor);
}

function estimateCartTax(items) {
    let tax = 0;
    for (const it of items) {
        const cat = (it.product && it.product.taxCategory) || "STANDARD";
        const rate = state.taxRates[cat];
        tax += lineTax(Number(it.lineTotal || 0), rate, state.taxMode);
    }
    return tax;
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

function rememberGuestOrder(order) {
    if (!order || !order.orderToken) return;
    let list;
    try { list = JSON.parse(localStorage.getItem(GUEST_ORDERS_KEY)) || []; } catch { list = []; }
    list.unshift({ id: order.id, token: order.orderToken, total: order.totalAmount, at: order.createdAt });
    localStorage.setItem(GUEST_ORDERS_KEY, JSON.stringify(list.slice(0, 20)));
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
    return { name: "home" };
}

async function route() {
    const r = currentRoute();
    if (r.name === "product") await showDetail(r.id);
    else if (r.name === "admin") await showAdmin();
    else showGrid();
}

// Exactly one of grid / detail / admin is visible at a time.
function hideViews() {
    $("#productDetail").classList.add("hidden");
    $("#productDetail").innerHTML = "";
    $("#adminPanel").classList.add("hidden");
    $("#adminPanel").innerHTML = "";
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
        const [settings, rates] = await Promise.all([
            api("/api/admin/settings", { auth: true }),
            api("/api/admin/tax-rates", { auth: true }),
        ]);
        renderAdmin(settings, rates);
    } catch (ex) {
        box.innerHTML = `<a href="#/" class="back-link">← 商品一覧へ戻る</a>
            <p class="empty">管理情報を取得できませんでした: ${escapeHtml(ex.message)}</p>`;
    }
}

function renderAdmin(settings, rates) {
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
        </section>`;
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

/* ---------- auth ---------- */
function isLoggedIn() { return !!state.token; }

function reflectAuth() {
    const logged = isLoggedIn();
    $("#authBtn").classList.toggle("hidden", logged);
    $("#logoutBtn").classList.toggle("hidden", !logged);
    $("#ordersBtn").classList.toggle("hidden", !logged);
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
        state.token = res.token;
        state.user = res.user;
        localStorage.setItem("ec_token", res.token);
        closeAuth();
        reflectAuth();
        await mergeGuestCartIfAny();
        await refreshCart();
        toast(`ようこそ、${res.user.name} さん`);
    } catch (ex) {
        err.textContent = ex.message;
        err.classList.remove("hidden");
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
    renderCartTax(cart, items);

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

/* Cart footer figures. The catalog price means different things per mode, so the
   breakdown is derived rather than assumed:
     INCLUSIVE → totalAmount is tax-included; 小計 = 合計 − 消費税
     EXCLUSIVE → totalAmount is tax-exclusive; 合計 = 小計 + 消費税
   Both are estimates until checkout snapshots them onto the order. */
function renderCartTax(cart, items) {
    const box = $("#cartTax");
    const sum = Number((cart && cart.totalAmount) || 0);
    const exclusive = state.taxMode === "EXCLUSIVE";

    if (items.length === 0) {
        box.classList.add("hidden");
        box.innerHTML = "";
        $("#cartTaxNote").classList.add("hidden");
        $("#cartTotalLabel").textContent = "合計";
        $("#cartTotal").textContent = yen(0);
        return;
    }

    const tax = estimateCartTax(items);
    const subtotal = exclusive ? sum : sum - tax;
    const total = exclusive ? sum + tax : sum;

    box.innerHTML = `
        <div><span>小計（税抜）</span><span>${yen(subtotal)}</span></div>
        <div><span>消費税${exclusive ? "（概算）" : ""}</span><span>${yen(tax)}</span></div>`;
    box.classList.remove("hidden");

    $("#cartTotalLabel").textContent = exclusive ? "合計（税込・概算）" : "合計（税込）";
    $("#cartTotal").textContent = yen(total);
    // Only 外税 needs the caveat: the shown total is derived here, not by the server yet.
    $("#cartTaxNote").classList.toggle("hidden", !exclusive);
}

async function checkout() {
    if (!isLoggedIn()) { return guestCheckout(); }
    try {
        const order = await api("/api/orders/checkout", { method: "POST", auth: true });
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
        const order = await api("/api/orders/guest-checkout", { method: "POST", body: { email, items } });
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
                <span class="badge ${o.status}">${o.status}</span>
            </div>
            <div class="lmeta">${when}</div>
            ${lines}
            ${taxBreakdownHtml(o)}
            ${payBtn}
        </div>`;
    }).join("");
}

function taxBreakdownHtml(order) {
    // subtotal(税抜) + 消費税 + 合計(税込). Fields come from the order snapshot.
    if (order.subtotalAmount == null || order.taxAmount == null) return "";
    return `<div class="tax-breakdown">
        <div><span>小計（税抜）</span><span>${yen(order.subtotalAmount)}</span></div>
        <div><span>消費税</span><span>${yen(order.taxAmount)}</span></div>
        <div class="tb-total"><span>合計（税込）</span><span>${yen(order.totalAmount)}</span></div>
    </div>`;
}

function showOrderResult(order) {
    const banner = $("#banner");
    let html = `✅ 注文 #${order.id} を受け付けました（状態 ${order.status}）。`;
    html += taxBreakdownHtml(order);
    if (order.guest && order.orderToken) {
        html += `<div class="hint" style="margin-top:6px">照会・支払い用トークン: <code>${escapeHtml(order.orderToken)}</code><br>ログインなしで注文を追跡できます。大切に保管してください。</div>`;
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
    $$(".tab").forEach((t) => t.addEventListener("click", () => setAuthTab(t.dataset.tab)));

    $("#cartBtn").addEventListener("click", async () => {
        await refreshCart(); // works for guests (localStorage) and logged-in users alike
        openDrawer("#cartDrawer");
    });
    $("#ordersBtn").addEventListener("click", openOrders);
    $("#adminBtn").addEventListener("click", () => { location.hash = "#/admin"; });
    $("#checkoutBtn").addEventListener("click", checkout);

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
    await Promise.all([loadCategories(), loadProducts()]);
    await restoreSession();
    if (!isLoggedIn()) updateCartCount(guestCartView()); // show guest cart badge on load
    reflectAuth();
    window.addEventListener("hashchange", route);
    await route(); // honor a deep link like #/product/3 on first load
}

init();
