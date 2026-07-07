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
    $("#emptyState").classList.toggle("hidden", products.length > 0);
    grid.innerHTML = products.map((p) => {
        const out = p.stock <= 0;
        const img = p.imageUrl ? `background-image:url('${p.imageUrl}')` : "";
        return `
        <article class="card">
            <div class="thumb" style="${img}"></div>
            <div class="body">
                <div class="cat">${p.category ? escapeHtml(p.category.name) : "&nbsp;"}</div>
                <div class="name">${escapeHtml(p.name)}</div>
                <div class="desc">${escapeHtml(p.description || "")}</div>
                <div class="row">
                    <span class="price">${yen(p.price)}</span>
                    <span class="stock ${out ? "out" : ""}">${out ? "在庫切れ" : "在庫 " + p.stock}</span>
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
    reflectAuth();
    updateCartCount({ totalQuantity: 0 });
    closeDrawers();
    toast("ログアウトしました");
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
    if (!isLoggedIn()) { updateCartCount({ totalQuantity: 0 }); return null; }
    const cart = await api("/api/cart", { auth: true });
    updateCartCount(cart);
    renderCart(cart);
    return cart;
}

async function addToCart(productId) {
    if (!isLoggedIn()) { openAuth(); return; }
    try {
        const cart = await api("/api/cart/items", { method: "POST", auth: true, body: { productId, quantity: 1 } });
        updateCartCount(cart);
        renderCart(cart);
        toast("カートに追加しました");
    } catch (ex) { toast(ex.message); }
}

async function setQty(productId, quantity) {
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
    $("#checkoutBtn").disabled = items.length === 0;
}

async function checkout() {
    try {
        const order = await api("/api/orders/checkout", { method: "POST", auth: true });
        state.lastOrder = order;
        await refreshCart();
        closeDrawers();
        showOrderResult(order);
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
    let html = `✅ 注文 #${order.id} を受け付けました（${yen(order.totalAmount)}・状態 ${order.status}）。`;
    if (state.paymentsEnabled && order.status === "PENDING") {
        html += ` <button class="btn pay-btn" data-order="${order.id}" style="margin-left:8px">Stripeで支払う（テスト）</button>`;
    } else if (!state.paymentsEnabled) {
        html += " （Stripe未設定のため決済はスキップ）";
    }
    banner.innerHTML = html;
    banner.classList.remove("hidden");
    window.scrollTo({ top: 0, behavior: "smooth" });
}

async function payWithStripe(orderId) {
    try {
        toast("Stripe決済ページへ移動します…");
        const res = await api(`/api/payments/orders/${orderId}/checkout-session`, { method: "POST", auth: true });
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
        if (!isLoggedIn()) { openAuth(); return; }
        await refreshCart();
        openDrawer("#cartDrawer");
    });
    $("#ordersBtn").addEventListener("click", openOrders);
    $("#checkoutBtn").addEventListener("click", checkout);
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
        if (pay) { payWithStripe(Number(pay.dataset.order)); return; }

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
    reflectAuth();
}

init();
