/**
 * DealFlow360 frontend helper: thin fetch() wrapper that adds the
 * HTTP Basic Authorization header on every call, plus small shared
 * utilities (nav rendering, formatting, role guards). No build step,
 * no framework - loaded as a plain <script> on every page.
 */
const DF = (function () {
    const AUTH_KEY = "df360_auth";
    const USER_KEY = "df360_user";

    function getAuth() {
        return sessionStorage.getItem(AUTH_KEY);
    }

    function getUser() {
        const raw = sessionStorage.getItem(USER_KEY);
        return raw ? JSON.parse(raw) : null;
    }

    function setSession(auth, user) {
        sessionStorage.setItem(AUTH_KEY, auth);
        sessionStorage.setItem(USER_KEY, JSON.stringify(user));
    }

    function logout() {
        sessionStorage.removeItem(AUTH_KEY);
        sessionStorage.removeItem(USER_KEY);
        window.location.href = "/index.html";
    }

    async function request(method, url, body) {
        const headers = { "Content-Type": "application/json" };
        const auth = getAuth();
        if (auth) headers["Authorization"] = "Basic " + auth;

        const res = await fetch(url, {
            method,
            headers,
            body: body !== undefined ? JSON.stringify(body) : undefined
        });

        if (res.status === 401) {
            logout();
            throw new Error("Session expired - please log in again.");
        }
        if (!res.ok) {
            let msg = "Request failed (" + res.status + ")";
            try {
                const data = await res.json();
                if (data && (data.message || data.error)) msg = data.message || data.error;
            } catch (e) { /* ignore body parse errors */ }
            throw new Error(msg);
        }
        if (res.status === 204) return null;
        const text = await res.text();
        return text ? JSON.parse(text) : null;
    }

    async function tryLogin(username, password) {
        const auth = btoa(username + ":" + password);
        const res = await fetch("/api/auth/me", { headers: { Authorization: "Basic " + auth } });
        if (!res.ok) throw new Error("Invalid username or password.");
        const user = await res.json();
        setSession(auth, user);
        return user;
    }

    function requireLogin() {
        if (!getAuth()) {
            window.location.href = "/index.html";
            throw new Error("redirecting to login");
        }
    }

    function requireRole(roles) {
        requireLogin();
        const user = getUser();
        if (!roles.includes(user.role)) {
            alert("You do not have access to this page.");
            window.location.href = user.role === "CUSTOMER" ? "/portal.html" : "/dashboard.html";
            throw new Error("redirecting - insufficient role");
        }
        return user;
    }

    function fmtMoney(n) {
        if (n === null || n === undefined) return "-";
        return "₹" + Number(n).toFixed(2);
    }

    function fmtPct(n) {
        if (n === null || n === undefined) return "-";
        return Number(n).toFixed(2) + "%";
    }

    function fmtDateTime(s) {
        if (!s) return "-";
        return new Date(s).toLocaleString();
    }

    function fmtDate(s) {
        if (!s) return "-";
        return new Date(s).toLocaleDateString();
    }

    function escapeHtml(s) {
        if (s === null || s === undefined) return "";
        return String(s).replace(/[&<>"']/g, c => ({
            "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
        }[c]));
    }

    function badgeClass(status) {
        return "badge badge-" + String(status || "").toLowerCase().replace(/_/g, "-");
    }

    /**
     * Product thumbnail: a real photo when the catalog has one, otherwise a
     * consistent category-colored icon tile - so the Quotation Builder,
     * Upsell panel and Customer Portal all look finished even for the demo
     * catalog, which ships with no images set.
     */
    const CATEGORY_ICONS = {
        hardware: { icon: "💻", color: "#2f6fed" },
        service: { icon: "🛠️", color: "#1e9e6b" },
        subscription: { icon: "🔄", color: "#8b5cf6" }
    };

    function productThumb(imageUrl, category, size) {
        size = size || 44;
        if (imageUrl) {
            return `<img src="${imageUrl.replace(/"/g, "&quot;")}" alt="" class="product-thumb" style="width:${size}px;height:${size}px" onerror="this.outerHTML=DF.productThumb(null,'${(category || "").replace(/'/g, "")}',${size})">`;
        }
        const key = String(category || "").toLowerCase();
        const meta = CATEGORY_ICONS[key] || { icon: "📦", color: "#7b8299" };
        return `<span class="product-thumb product-thumb-fallback" style="width:${size}px;height:${size}px;background:${meta.color}1a;color:${meta.color};font-size:${Math.round(size * 0.5)}px">${meta.icon}</span>`;
    }

    /**
     * Multipart upload helper (used by the "Import from PDF" automation
     * feature). Deliberately does NOT set a Content-Type header - the
     * browser sets the correct multipart boundary itself when the body is
     * a FormData object.
     */
    async function upload(url, formData) {
        const headers = {};
        const auth = getAuth();
        if (auth) headers["Authorization"] = "Basic " + auth;
        const res = await fetch(url, { method: "POST", headers, body: formData });
        if (res.status === 401) { logout(); throw new Error("Session expired - please log in again."); }
        if (!res.ok) {
            let msg = "Request failed (" + res.status + ")";
            try { const data = await res.json(); if (data && (data.message || data.error)) msg = data.message || data.error; } catch (e) { /* ignore */ }
            throw new Error(msg);
        }
        const text = await res.text();
        return text ? JSON.parse(text) : null;
    }

    /**
     * Small dependency-free SVG chart renderer - no charting library, no
     * external CDN, so the demo works fully offline. Renders a bar series
     * (e.g. revenue per month) with an optional overlaid line series (e.g.
     * average discount % or units) sharing the same category axis.
     *
     * @param containerId  id of the element to render into
     * @param categories   array of string labels, one per column (x axis)
     * @param series       array of { name, values, type: 'bar'|'line', color }
     */
    function renderChart(containerId, categories, series) {
        const el = document.getElementById(containerId);
        if (!el) return;
        if (!categories || categories.length === 0) {
            el.innerHTML = `<div class="empty">No data for this period.</div>`;
            return;
        }

        const width = 720, height = 260;
        const padL = 46, padR = 16, padT = 16, padB = 34;
        const chartW = width - padL - padR;
        const chartH = height - padT - padB;
        const n = categories.length;
        const colW = chartW / n;

        const barSeries = series.filter(s => s.type !== "line");
        const lineSeries = series.filter(s => s.type === "line");

        const barMax = Math.max(1, ...barSeries.flatMap(s => s.values));
        const lineMax = lineSeries.length ? Math.max(1, ...lineSeries.flatMap(s => s.values)) : 1;

        let svg = `<svg viewBox="0 0 ${width} ${height}" class="df-chart" role="img" aria-label="Trend chart">`;

        // Horizontal gridlines + y-axis labels (based on the bar/primary series scale)
        const gridLines = 4;
        for (let i = 0; i <= gridLines; i++) {
            const y = padT + chartH - (chartH * i / gridLines);
            const value = (barMax * i / gridLines);
            svg += `<line x1="${padL}" y1="${y}" x2="${width - padR}" y2="${y}" stroke="#e6e8ec" stroke-width="1"/>`;
            svg += `<text x="${padL - 8}" y="${y + 4}" text-anchor="end" font-size="10" fill="#8a8f98">${formatAxisNumber(value)}</text>`;
        }

        // Bars (grouped side-by-side when there is more than one bar series)
        const barGroupW = colW * 0.6;
        const singleBarW = barGroupW / Math.max(1, barSeries.length);
        barSeries.forEach((s, si) => {
            s.values.forEach((v, i) => {
                const barH = barMax > 0 ? (v / barMax) * chartH : 0;
                const x = padL + i * colW + (colW - barGroupW) / 2 + si * singleBarW;
                const y = padT + chartH - barH;
                svg += `<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${(singleBarW - 3).toFixed(1)}" height="${barH.toFixed(1)}" fill="${s.color}" rx="2">
                            <title>${escapeHtml(s.name)}: ${v}</title>
                        </rect>`;
            });
        });

        // Line series, scaled independently against lineMax so it stays readable next to bars
        lineSeries.forEach(s => {
            const points = s.values.map((v, i) => {
                const x = padL + i * colW + colW / 2;
                const y = padT + chartH - (lineMax > 0 ? (v / lineMax) * chartH : 0);
                return `${x.toFixed(1)},${y.toFixed(1)}`;
            });
            svg += `<polyline points="${points.join(" ")}" fill="none" stroke="${s.color}" stroke-width="2.5"/>`;
            s.values.forEach((v, i) => {
                const x = padL + i * colW + colW / 2;
                const y = padT + chartH - (lineMax > 0 ? (v / lineMax) * chartH : 0);
                svg += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="3" fill="${s.color}"><title>${escapeHtml(s.name)}: ${v}</title></circle>`;
            });
        });

        // X axis labels
        categories.forEach((label, i) => {
            const x = padL + i * colW + colW / 2;
            svg += `<text x="${x.toFixed(1)}" y="${height - 10}" text-anchor="middle" font-size="11" fill="#565c66">${escapeHtml(label)}</text>`;
        });

        // Axis lines
        svg += `<line x1="${padL}" y1="${padT}" x2="${padL}" y2="${padT + chartH}" stroke="#c7cbd1"/>`;
        svg += `<line x1="${padL}" y1="${padT + chartH}" x2="${width - padR}" y2="${padT + chartH}" stroke="#c7cbd1"/>`;

        svg += `</svg>`;

        const legend = series.map(s => `<span class="chart-legend-item"><span class="chart-swatch" style="background:${s.color}"></span>${escapeHtml(s.name)}</span>`).join("");

        el.innerHTML = `${svg}<div class="chart-legend">${legend}</div>`;
    }

    function formatAxisNumber(n) {
        if (n >= 1000) return (n / 1000).toFixed(1) + "k";
        return Math.round(n).toString();
    }

    function showError(elId, err) {
        const el = document.getElementById(elId);
        if (!el) { alert(err.message || err); return; }
        el.textContent = err.message || String(err);
        el.className = "error-box"; // the shared #msg placeholders carry no class of their own
        el.style.display = "block";
    }

    function clearMessage(elId) {
        const el = document.getElementById(elId);
        if (el) { el.style.display = "none"; el.textContent = ""; }
    }

    function renderNav(activePage) {
        const user = getUser();
        const el = document.getElementById("nav");
        if (!user || !el) return;

        let links;
        if (user.role === "CUSTOMER") {
            links = `
                <a href="/portal.html" class="${activePage === "portal" ? "active" : ""}">My Orders</a>
                <a href="/portal-catalog.html" class="${activePage === "portal-catalog" ? "active" : ""}">Browse &amp; Request</a>
            `;
        } else {
            const managerLinks = (user.role === "SALES_MANAGER" || user.role === "FINANCE" || user.role === "ADMIN")
                ? `<a href="/deal-health.html" class="${activePage === "deal-health" ? "active" : ""}">Deal Health</a>
                   <a href="/reports.html" class="${activePage === "reports" ? "active" : ""}">Reports</a>
                   <a href="/automation.html" class="${activePage === "automation" ? "active" : ""}">Automation</a>`
                : "";
            const adminLinks = (user.role === "ADMIN" || user.role === "SALES_MANAGER")
                ? `<a href="/admin-products.html" class="${activePage === "admin" ? "active" : ""}">Backend Setup</a>`
                : "";
            links = `
                <a href="/dashboard.html" class="${activePage === "dashboard" ? "active" : ""}">Dashboard</a>
                <a href="/quotations.html" class="${activePage === "quotations" ? "active" : ""}">Quotations</a>
                <a href="/pipeline.html" class="${activePage === "pipeline" ? "active" : ""}">Pipeline</a>
                <a href="/trends.html" class="${activePage === "trends" ? "active" : ""}">Trends</a>
                ${managerLinks}
                ${adminLinks}
            `;
        }

        el.innerHTML = `
            <div class="nav-inner">
                <div class="brand">DealFlow360</div>
                <div class="nav-links">${links}</div>
                <div class="nav-user">
                    ${escapeHtml(user.fullName)} <span class="badge">${escapeHtml(user.role)}</span>
                    <button class="link-btn" onclick="DF.logout()">Logout</button>
                </div>
            </div>`;
    }

    return {
        get: (url) => request("GET", url),
        post: (url, body) => request("POST", url, body === undefined ? {} : body),
        put: (url, body) => request("PUT", url, body),
        del: (url) => request("DELETE", url),
        getAuth, getUser, setSession, logout, tryLogin,
        requireLogin, requireRole,
        fmtMoney, fmtPct, fmtDateTime, fmtDate, escapeHtml, badgeClass, productThumb,
        showError, clearMessage, renderNav,
        upload, renderChart
    };
})();
