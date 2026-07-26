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

const state = {
    token: localStorage.getItem("ec_token") || null,
    user: null,
    paymentsEnabled: false,
    categories: [],
    lastOrder: null,
    productsById: {},
    // For guests at checkout: 'choice' shows 会員/ゲスト の選択、'guest' shows the guest email form.
    guestMode: "choice",
};

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
        product: { id: it.productId, name: it.name, price: it.price, imageUrl: it.imageUrl },
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
        const img = p.imageUrl ? `background-image:url('${p.imageUrl}')` : "";
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
    const m = location.hash.replace(/^#/, "").match(/^\/product\/(\d+)$/);
    return m ? { name: "product", id: Number(m[1]) } : { name: "home" };
}

async function route() {
    const r = currentRoute();
    if (r.name === "product") await showDetail(r.id);
    else showGrid();
}

function showGrid() {
    $("#productDetail").classList.add("hidden");
    $("#productDetail").innerHTML = "";
    $("#grid").classList.remove("hidden");
    // #emptyState visibility is owned by renderProducts()
}

async function showDetail(id) {
    $("#grid").classList.add("hidden");
    $("#emptyState").classList.add("hidden");
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
    const img = p.imageUrl ? `background-image:url('${p.imageUrl}')` : "";
    $("#productDetail").innerHTML = `
        <a href="#/" class="back-link">← 商品一覧へ戻る</a>
        <div class="detail-grid">
            <div class="detail-thumb" style="${img}"></div>
            <div class="detail-info">
                <div class="cat">${p.category ? escapeHtml(p.category.name) : ""}</div>
                <h1 class="detail-name">${escapeHtml(p.name)}</h1>
                <p class="detail-desc">${escapeHtml(p.description || "")}</p>
                <div class="detail-price">${yen(p.price)}</div>
                <div class="detail-stock ${out ? "out" : ""}">${out ? "在庫切れ" : "在庫 " + avail}</div>
                <button class="btn wide add-btn" data-id="${p.id}" ${out ? "disabled" : ""}>カートに入れる</button>
            </div>
        </div>`;
}

/* ---------- auth ---------- */
function isLoggedIn() { return !!state.token; }

function reflectAuth() {
    const logged = isLoggedIn();
    $("#authBtn").classList.toggle("hidden", logged);
    $("#logoutBtn").classList.toggle("hidden", !logged);
    $("#ordersBtn").classList.toggle("hidden", !logged);
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
    else items.push({ productId, quantity: 1, name: p.name, price: p.price, imageUrl: p.imageUrl });
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
            const img = p.imageUrl ? `background-image:url('${p.imageUrl}')` : "";
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
    $("#cartTotal").textContent = yen(cart ? cart.totalAmount : 0);

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
        const payBtn = (state.paymentsEnabled && o.status === "PENDING")
            ? `<button class="btn pay-btn" data-order="${o.id}">Stripeで支払う（テスト）</button>` : "";
        return `
        <div class="order-block">
            <div class="oh">
                <strong>注文 #${o.id}</strong>
                <span class="badge ${o.status}">${o.status}</span>
            </div>
            <div class="lmeta">${when}</div>
            ${lines}
            <div class="total-row" style="margin-top:8px"><span>合計</span><strong>${yen(o.totalAmount)}</strong></div>
            ${payBtn}
        </div>`;
    }).join("");
}

function showOrderResult(order) {
    const banner = $("#banner");
    const tokenAttr = order.guest && order.orderToken ? ` data-token="${escapeHtml(order.orderToken)}"` : "";
    let html = `✅ 注文 #${order.id} を受け付けました（${yen(order.totalAmount)}・状態 ${order.status}）。`;
    if (order.guest && order.orderToken) {
        html += `<div class="hint" style="margin-top:6px">照会・支払い用トークン: <code>${escapeHtml(order.orderToken)}</code><br>ログインなしで注文を追跡できます。大切に保管してください。</div>`;
    }
    if (state.paymentsEnabled && order.status === "PENDING") {
        html += ` <button class="btn pay-btn" data-order="${order.id}"${tokenAttr} style="margin-left:8px">Stripeで支払う（テスト）</button>`;
    } else if (!state.paymentsEnabled) {
        html += " （Stripe未設定のため決済はスキップ）";
    }
    banner.innerHTML = html;
    banner.classList.remove("hidden");
    window.scrollTo({ top: 0, behavior: "smooth" });
}

// token present → guest order (public endpoint); absent → the logged-in user's order.
async function payWithStripe(orderId, token) {
    try {
        toast("Stripe決済ページへ移動します…");
        const path = token
            ? `/api/payments/guest/orders/${orderId}/checkout-session?token=${encodeURIComponent(token)}`
            : `/api/payments/orders/${orderId}/checkout-session`;
        const res = await api(path, { method: "POST", auth: !token });
        window.location.href = res.checkoutUrl;
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
        if (pay) { payWithStripe(Number(pay.dataset.order), pay.dataset.token || null); return; }

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
        if (!cfg.enabled) {
            $("#banner").textContent = "🧪 デモモード: Stripe未設定のため決済はスキップされます（注文までは動作します）。";
            $("#banner").classList.remove("hidden");
        }
    } catch { /* ignore */ }
    await Promise.all([loadCategories(), loadProducts()]);
    await restoreSession();
    if (!isLoggedIn()) updateCartCount(guestCartView()); // show guest cart badge on load
    reflectAuth();
    window.addEventListener("hashchange", route);
    await route(); // honor a deep link like #/product/3 on first load
}

init();
