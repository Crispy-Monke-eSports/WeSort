// WeSort Manager page. Item names come from players, so the DOM is only built with
// createElement/textContent, never innerHTML.
(function () {
  "use strict";

  var POLL_MS = 10000;
  var LINK_POLL_MS = 3000;
  var FETCH_TIMEOUT_MS = 8000;
  var SEARCH_DEBOUNCE_MS = 100;
  var PLACEHOLDER = "/placeholder.svg";
  var ICON_RETRIES = 20;
  var ICON_SOURCE_TOKEN = "g2"; // bump when the server changes how icons are made
  var DEFAULT_MC = "1.21.11";
  var ICON_RETRY_DELAYS = [1000, 2000, 3000, 5000];
  var STORAGE_KEY = "wesort.selected";

  function $(id) {
    return document.getElementById(id);
  }

  // ---------- streamer mode (display only) ----------
  // Sensitive values are always marked with data-sensitive where they are created; the CSS blurs them while
  // <html> has the "streamer" class (set before the first paint by streamer-init.js).
  var STREAMER_KEY = "wesort.streamer";

  function streamerOn() {
    return document.documentElement.classList.contains("streamer");
  }

  /**
   * The owner forced streamer mode on for this user (perms.streamer): always on, and the server already leaves out
   * positions, names and addresses. The page only shows it as locked.
   */
  function forcedStreamer() {
    return typeof me === "object" && !me.owner && !!me.perms && me.perms.streamer === true;
  }

  function hide(node) {
    node.setAttribute("data-sensitive", "");
    return node;
  }

  function hiddenSpan(text) {
    var span = document.createElement("span");
    span.textContent = text;
    return hide(span);
  }

  var el = {
    select: $("stash-select"),
    streamerBtn: $("streamer-btn"),
    streamerState: $("streamer-state"),
    dot: $("bot-dot"),
    botText: $("bot-text"),
    infoIndex: $("info-index"),
    infoContainers: $("info-containers"),
    infoMeta: $("info-meta"),
    indexMeta: $("index-meta"),
    tileContainers: $("tile-containers"),
    tileStale: $("tile-stale"),
    tileIndexed: $("tile-indexed"),
    gridWrap: $("grid-wrap"),
    grid: $("item-grid"),
    empty: $("empty"),
    emptyText: $("empty-text"),
    search: $("search"),
    sort: $("sort"),
    summary: $("summary"),
    tooltip: $("tooltip"),
    tabs: $("tabs"),
    tabsRight: $("tabs-right"),
    panelLabel: $("panel-label"),
    viewItems: $("view-items"),
    viewOrders: $("view-orders"),
    crumbs: $("crumbs"),
    pickerBody: $("picker-body"),
    cartLines: $("cart-lines"),
    cartTotals: $("cart-totals"),
    namesSeg: $("names-seg"),
    namesAll: $("names-all"),
    namesEach: $("names-each"),
    namesRows: $("names-rows"),
    namesWarn: $("names-warn"),
    craftInfo: $("craft-info"),
    deliveryFit: $("delivery-fit"),
    deliveryWarn: $("delivery-warn"),
    cartPlace: $("cart-place"),
    cartClear: $("cart-clear"),
    cartStatus: $("cart-status"),
    cartOffline: $("cart-offline"),
    orderList: $("order-list"),
    toast: $("toast"),
    linkOpen: $("link-open"),
    linkModal: $("link-modal"),
    linkClose: $("link-close"),
    linkUrls: $("link-urls"),
    linkKey: $("link-key"),
    linkKeyNote: $("link-key-note"),
    linkDot: $("link-dot"),
    linkStatusText: $("link-status-text"),
    linkRegen: $("link-regen"),
    linkCopyNote: $("link-copy-note"),
    deliveryStatus: $("delivery-status"),
    orderBlocker: $("order-blocker"),
    cartDrop: $("cart-drop"),
    cartCount: $("cart-count"),
    chestGrid: $("chest-grid"),
    binGrid: $("bin-grid"),
    binTitle: $("bin-title"),
    dragGhost: $("drag-ghost"),
    ordersClear: $("orders-clear"),
    pageActivity: $("page-activity"),
    navActivity: $("nav-activity"),
    activityTitle: $("activity-title"),
    actEmpty: $("act-empty"),
    actBody: $("act-body"),
    actHead: $("act-head"),
    actStats: $("act-stats"),
    actNow: $("act-now"),
    actEvents: $("act-events"),
    actSource: $("act-source"),
    actLevel: $("act-level"),
    actModules: $("act-modules"),
    actModCount: $("act-mod-count"),
    actModFilter: $("act-mod-filter"),
    actEnabledOnly: $("act-enabled-only"),
    actPlugins: $("act-plugins"),
    sudoBox: $("sudo-box"),
    sudoText: $("sudo-text"),
    sudoLock: $("sudo-lock"),
    inventory: $("inventory"),
    tabsWrap: $("tabs-wrap"),
    pageBots: $("page-bots"),
    pageSettings: $("page-settings"),
    dashNav: $("dash-nav"),
    botSelect: $("bot-select"),
    dashBots: $("dash-bots"),
    stashBots: $("stash-bots"),
    settingsTitle: $("settings-title"),
    settingsStatus: $("settings-status"),
    settingsRevert: $("settings-revert"),
    settingsSave: $("settings-save"),
    settingsOffline: $("settings-offline"),
    settingsBody: $("settings-body"),
    authScreen: $("auth-screen"),
    authForm: $("auth-form"),
    authText: $("auth-text"),
    authPassword: $("auth-password"),
    authPasswordRow: $("auth-password-row"),
    authConfirm: $("auth-confirm"),
    authConfirmRow: $("auth-confirm-row"),
    authCode: $("auth-code"),
    authCodeRow: $("auth-code-row"),
    authSubmit: $("auth-submit"),
    authError: $("auth-error"),
    logoutBtn: $("logout-btn"),
    passwordOpen: $("password-open"),
    logoutAll: $("logout-all"),
    botsOpen: $("bots-open"),
    conflictWarn: $("conflict-warn"),
    linkBots: $("link-bots"),
    linkStatus: $("link-status"),
    rescanBox: $("rescan-box"),
    rescanBtn: $("rescan-btn"),
    rescanStatus: $("rescan-status"),
    rescanProgress: $("rescan-progress"),
    rescanFill: $("rescan-fill"),
    authUsername: $("auth-username"),
    authUsernameRow: $("auth-username-row"),
    authUserHint: $("auth-user-hint"),
    authPasswordLabel: $("auth-password-label"),
    authNext: $("auth-next"),
    authNextRow: $("auth-next-row"),
    authConfirmLabel: $("auth-confirm-label"),
    authLogout: $("auth-logout"),
    userName: $("user-name"),
    accountName: $("account-name"),
    accountSection: $("account-section"),
    accountWho: $("account-who"),
    orderPicker: $("order-picker"),
    usersSection: $("users-section"),
    usersBody: $("users-body"),
    audit: $("audit"),
    auditStatus: $("audit-status"),
    auditList: $("audit-list"),
    auditRefresh: $("audit-refresh")
  };

  var slotEntry = new WeakMap(); // slot element -> entry

  var state = {
    stashes: [],          // last /api/v1/stashes reply
    selected: null,       // stash key string
    clockSkew: 0,         // server time minus local time, from the Date header
    unreachable: false,
    polling: false,
    index: null,          // loaded index
    loadedWant: null,     // "<stash key>|<indexedAt>" of the loaded index
    pendingWant: null,    // same, for the request in flight
    loadToken: 0,         // bumps on every index request; stale replies are dropped
    items: [],            // entries in index order: { item, slot, img, countEl, glint, hay, ns, ... }
    byKey: new Map(),     // item key -> entry, kept across index refreshes
    shown: [],            // entries currently in the grid, in display order
    shulkers: [],         // shulker group entries (the Shulkers tab), same shape as item entries
    tab: "all",           // selected tab id
    sortAll: "count",     // sort used in the Search/All and Shulkers tabs
    sortTab: "creative",  // sort used inside creative tabs
    mc: DEFAULT_MC,       // the loaded index's Minecraft version (for icon URLs)
    hovered: null,        // entry under the cursor
    mouse: { x: 0, y: 0, over: false },
    searchTimer: 0
  };

  // ---------- formatting ----------

  function trimZero(s) {
    return s.replace(/\.0$/, "");
  }

  function compact(n) {
    if (n < 1000) return String(n);
    if (n < 9950) return trimZero((n / 1e3).toFixed(1)) + "k";
    if (n < 999500) return Math.round(n / 1e3) + "k";
    if (n < 9950000) return trimZero((n / 1e6).toFixed(1)) + "M";
    if (n < 999500000) return Math.round(n / 1e6) + "M";
    return trimZero((n / 1e9).toFixed(1)) + "B";
  }

  function approx(v) {
    if (v > 0 && v < 0.05) return "<0.1";
    return v >= 10 ? String(Math.round(v)) : trimZero(v.toFixed(1));
  }

  function grouped(n) {
    return Number(n).toLocaleString("en-US");
  }

  function serverNow() {
    return Date.now() + state.clockSkew;
  }

  function ago(ms) {
    if (ms == null) return "never";
    var s = Math.max(0, Math.round((serverNow() - ms) / 1000));
    if (s < 60) return s + "s ago";
    var m = Math.floor(s / 60);
    if (m < 60) return m + "m ago";
    var h = Math.floor(m / 60);
    if (h < 48) return h + "h ago";
    return Math.floor(h / 24) + "d ago";
  }

  function stashKey(s) {
    return JSON.stringify([s.server, s.stash]);
  }

  function str(v) {
    return typeof v === "string" ? v : "";
  }

  function num(v, d) {
    return typeof v === "number" && isFinite(v) ? v : d;
  }

  // ---------- website login ----------
  // The page's files are public, everything else needs the wesort_session cookie (HttpOnly, set by the server).
  // Any 401 from the API shows the login screen over the page; after logging in the page carries on where it was.
  // A sub-user whose password the owner set (mustChange) gets the "Choose a new password" screen instead, and so
  // does any API answer 403 {mustChange: true}.

  var MIN_PASSWORD = 12;
  var USER_KEY = "wesort.user";
  var auth = { blocked: true, mode: "login", busy: false, userId: null };

  // ---------- the logged-in user and what it may do ----------
  // auth/state names the user and its effective permissions. The server enforces every one of them; the page only
  // hides what the user can't use. A server without sub-users sends no user: that is the owner, with everything.

  var OWNER_PERMS = {
    view: true, order: true, ordersAll: true, guildOrders: true, positions: true, activity: true, settings: true,
    rescan: true, link: true, streamer: false, stashes: null, bots: null
  };
  var me = { id: "owner", name: "", owner: true, mustChange: false, perms: OWNER_PERMS };
  var permsTimer = 0;

  /** Takes auth/state's user (or none: the owner). Returns true when anything changed. */
  function setUser(u) {
    var before = JSON.stringify(me);
    if (u && typeof u === "object" && u.owner !== true) {
      me = {
        id: str(u.id),
        name: str(u.name),
        owner: false,
        mustChange: u.mustChange === true,
        perms: u.perms && typeof u.perms === "object" ? u.perms : {}
      };
    } else {
      me = {
        id: u && typeof u === "object" ? str(u.id) || "owner" : "owner",
        name: u && typeof u === "object" ? str(u.name) : "",
        owner: true,
        mustChange: !!(u && typeof u === "object" && u.mustChange === true),
        perms: OWNER_PERMS
      };
    }
    return JSON.stringify(me) !== before;
  }

  /** A yes/no permission of the logged-in user. */
  function can(key) {
    return me.owner || me.perms[key] === true;
  }



  /** The dashboard pages this user may open. */
  function pageAllowed(page) {
    if (page === "bots" || page === "activity") return can("activity");
    return ["stash", "orders", "settings"].indexOf(page) >= 0;
  }

  /** An order is the user's own when they placed it; an order without placedBy is from before users: the owner's. */
  function ownOrder(o) {
    var by = o && o.placedBy && typeof o.placedBy === "object" ? o.placedBy : null;
    return by ? str(by.id) === me.id : me.owner;
  }

  function canManageOrder(o) {
    return can("ordersAll") || ((can("order")) && ownOrder(o));
  }

  /** A 403 naming a permission: the owner may have changed this user's permissions, so ask again (once). */
  function permsStale() {
    if (permsTimer) return;
    permsTimer = window.setTimeout(function () {
      permsTimer = 0;
      refreshSudo();
    }, 500);
  }

  /** "wrong username or password" -> "Wrong username or password." */
  function sentence(text) {
    var t = String(text || "").trim();
    if (!t) return "";
    return t.charAt(0).toUpperCase() + t.slice(1) + (/[.!?]$/.test(t) ? "" : ".");
  }

  function savedUsername() {
    try {
      return window.localStorage.getItem(USER_KEY) || "";
    } catch (e) {
      return "";
    }
  }

  function saveUsername(name) {
    try {
      if (name) window.localStorage.setItem(USER_KEY, name);
      else window.localStorage.removeItem(USER_KEY);
    } catch (e) {
      // Storage disabled: just don't remember.
    }
  }

  function checkAuth() {
    api("/api/v1/auth/state").then(function (st) {
      if (st && typeof st.minLength === "number") MIN_PASSWORD = st.minLength;
      if (st && st.loggedIn) {
        setUser(st.user);
        // Another account on the same page (an expired session, then someone else logged in): start clean.
        if (auth.userId !== null && auth.userId !== me.id) {
          reloadPage();
          return;
        }
        auth.userId = me.id;
        if (me.mustChange) showAuth("change");
        else loggedIn();
      } else if (st && st.setupNeeded) {
        showAuth(st.setupAllowed ? "setup" : "elsewhere", str(st.message));
      } else {
        showAuth("login");
      }
    }, function () {
      showAuth("login");
      authError("Server unreachable. Retrying...");
      window.setTimeout(checkAuth, 3000);
    });
  }

  function loginRequired() {
    if (auth.blocked) return;
    showAuth("login");
  }

  /** Any API answer 403 {mustChange: true}: the blocking "Choose a new password" screen. */
  function mustChangeRequired() {
    if (auth.blocked && auth.mode === "change") return;
    me.mustChange = true;
    showAuth("change");
  }

  function authError(text) {
    el.authError.textContent = text || "";
  }

  function showAuth(mode, message) {
    auth.blocked = true;
    auth.mode = mode;
    hideTooltip();
    if (typeof closeWindow === "function") closeWindow();
    if (link.open) closeLink();
    el.authScreen.hidden = false;
    var setup = mode === "setup";
    var elsewhere = mode === "elsewhere";
    var change = mode === "change";
    el.authText.textContent = setup
      ? "First run: type the one-time code from the local bootstrap command, then choose a password for this website (at least " + MIN_PASSWORD +
        " characters). Anyone on your network will need it to see the stash, place orders or control the bots. It is remembered for 30 days on each browser."
      : elsewhere
        ? (message || "Set the dashboard password on the server PC (open http://localhost:8765).")
        : change
          ? "Choose a new password" + (me.name ? " for " + me.name : "") + " (at least " + MIN_PASSWORD + " characters). " +
            "The password you were given is temporary: the site opens once you have changed it."
          : "Enter the website password. Sub-users also type their username; the owner can leave it blank.";
    el.authUsernameRow.hidden = mode !== "login";
    // The hidden username field is a password-manager hint for the new password screens only.
    el.authUserHint.disabled = !(setup || change);
    el.authUserHint.value = change && me.name ? me.name : "stash-manager";
    el.authPasswordRow.hidden = elsewhere;
    el.authPasswordLabel.textContent = change ? "Current (temporary) password" : "Password";
    el.authNextRow.hidden = !change;
    el.authConfirmRow.hidden = !(setup || change);
    el.authConfirmLabel.textContent = change ? "New password again" : "Confirm password";
    el.authCodeRow.hidden = !setup;
    el.authCode.value = "";
    el.authSubmit.hidden = elsewhere;
    el.authSubmit.textContent = setup ? "Set password" : change ? "Change password" : "Log in";
    el.authLogout.hidden = !change;
    el.authPassword.setAttribute("autocomplete", setup ? "new-password" : "current-password");
    el.authPassword.value = "";
    el.authNext.value = "";
    el.authConfirm.value = "";
    if (mode === "login" && !el.authUsername.value) el.authUsername.value = savedUsername();
    authError("");
    if (setup) el.authCode.focus();
    else if (!elsewhere) el.authPassword.focus();
  }

  function loggedIn() {
    var first = !auth.started;
    auth.blocked = false;
    auth.started = true;
    el.authScreen.hidden = true;
    el.authPassword.value = "";
    el.authNext.value = "";
    el.authConfirm.value = "";
    refreshSudo();
    applyPerms(false);
    loadGuildProfile();
    if (first) {
      renderTabs();
      showPage(dash.page);
    } else {
      retryIcons();
    }
    poll();
    pollBots();
    scheduleOrdersPoll(0);
  }

  /** Icons that failed while logged out are asked for again. */
  function retryIcons() {
    tabsShown = ""; // rebuild the tab buttons, whose icons may have failed too
    renderTabs();
    var all = [];
    state.byKey.forEach(function (e) { all.push(e); });
    if (state.shulkerByKey) state.shulkerByKey.forEach(function (e) { all.push(e); });
    all.forEach(function (e) {
      if (e.iconLoaded) return;
      e.tries = 0;
      e.img.src = e.url + "&retry=login";
    });
  }

  function tooMany(body) {
    return "Too many attempts. Try again in " + (body && body.retryAfter ? body.retryAfter : 60) + " s.";
  }

  /** The must-change screen: the caller's own password, then auth/state again. */
  function submitChange() {
    var current = el.authPassword.value;
    var next = el.authNext.value;
    if (!current) return authError("Enter the password you were given.");
    if (next.length < MIN_PASSWORD) return authError("The new password needs at least " + MIN_PASSWORD + " characters.");
    if (next !== el.authConfirm.value) return authError("The two new passwords are different.");
    if (next === current) return authError("Choose a new password, not the one you were given.");
    auth.busy = true;
    el.authSubmit.disabled = true;
    authError("Saving...");
    api("/api/v1/auth/password", { method: "POST", body: { current: current, next: next } }).then(function () {
      authError("");
      me.mustChange = false;
      toast("Password changed");
      checkAuth();
    }, function (err) {
      var body = err && err.body;
      if (err && err.status === 401) {
        authError("The current password is wrong.");
        el.authPassword.select();
      } else if (err && err.status === 429) {
        authError(tooMany(body));
      } else {
        authError(body && body.error ? sentence(body.error) : err && err.network ? "Server unreachable." : "Could not change the password.");
      }
    }).then(function () {
      auth.busy = false;
      el.authSubmit.disabled = false;
    });
  }

  el.authForm.addEventListener("submit", function (e) {
    e.preventDefault();
    if (auth.busy || auth.mode === "elsewhere") return;
    if (auth.mode === "change") return void submitChange();
    var password = el.authPassword.value;
    var setup = auth.mode === "setup";
    var username = setup ? "" : el.authUsername.value.trim();
    if (setup) {
      if (!el.authCode.value.trim()) return authError("Type the one-time code from the local bootstrap command.");
      if (password.length < MIN_PASSWORD) return authError("The password needs at least " + MIN_PASSWORD + " characters.");
      if (password !== el.authConfirm.value) return authError("The two passwords are different.");
    } else if (!password) {
      return authError("Enter the password.");
    }
    auth.busy = true;
    el.authSubmit.disabled = true;
    authError(setup ? "Saving..." : "Checking...");
    // A blank username is the owner, exactly as before sub-users existed.
    var payload = setup ? { password: password, code: el.authCode.value } : username ? { username: username, password: password } : { password: password };
    api(setup ? "/api/v1/auth/setup" : "/api/v1/auth/login", { method: "POST", body: payload }).then(function () {
      authError("");
      if (!setup) saveUsername(username);
      checkAuth(); // names the user (and its permissions, or the must-change screen)
    }, function (err) {
      var body = err && err.body;
      if (err && err.status === 429) {
        authError(tooMany(body));
      } else if (err && err.status === 401) {
        authError(body && body.error ? sentence(body.error) : "Wrong password.");
        el.authPassword.select();
      } else if (err && err.status === 409) {
        checkAuth(); // set up meanwhile, or reset
      } else if (err && err.status === 403 && setup) {
        authError(body && body.error ? body.error : "Refused.");
        el.authCode.select();
      } else if (err && err.status === 403) {
        authError(body && body.error ? sentence(body.error) : "Refused."); // "this account is disabled"
      } else {
        authError(body && body.error ? body.error : err && err.network ? "Server unreachable." : "Could not log in.");
      }
    }).then(function () {
      auth.busy = false;
      el.authSubmit.disabled = false;
    });
  });

  function logout() {
    api("/api/v1/auth/logout", { method: "POST", body: {} }).then(reloadPage, reloadPage);
  }

  el.logoutBtn.addEventListener("click", logout);
  el.authLogout.addEventListener("click", logout);

  function reloadPage() {
    window.location.reload();
  }

  /** The owner ends every session of every user; a sub-user ends their own. */
  function logoutEverywhere() {
    var text = me.owner ? "Log out every browser, including this one?" : "Log out every browser where you are logged in, including this one?";
    if (!window.confirm(text)) return;
    api("/api/v1/auth/logout-all", { method: "POST", body: {} }).then(reloadPage, function (e) {
      toast("Could not log out everywhere" + (e && e.body && e.body.error ? ": " + e.body.error : ""));
    });
  }

  el.logoutAll.addEventListener("click", logoutEverywhere);
  $("account-logout-all").addEventListener("click", logoutEverywhere);

  /** The caller's own password (owner or sub-user). */
  function openPasswordWindow() {
    if (link.open) closeLink();
    var body = document.createElement("form");
    body.className = "password-form";
    body.noValidate = true;
    var fields = {};
    [["current", "Current password", "current-password"], ["next", "New password (at least " + MIN_PASSWORD + " characters)", "new-password"],
      ["confirm", "New password again", "new-password"]].forEach(function (f) {
      var label = document.createElement("label");
      label.className = "auth-field";
      var span = document.createElement("span");
      span.className = "field-label";
      span.textContent = f[1];
      var input = document.createElement("input");
      input.type = "password";
      input.className = "mc-input";
      input.maxLength = 1024;
      input.autocomplete = f[2];
      label.appendChild(span);
      label.appendChild(input);
      body.appendChild(label);
      fields[f[0]] = input;
    });
    var info = line("note", "Every other browser is logged out. This one stays logged in.");
    body.appendChild(info);
    var msg = line("note warn", "");
    var save = document.createElement("button");
    save.type = "submit";
    save.className = "mc-button";
    save.textContent = "Change password";
    body.appendChild(save);
    body.appendChild(msg);
    body.addEventListener("submit", function (e) {
      e.preventDefault();
      if (fields.next.value.length < MIN_PASSWORD) return void (msg.textContent = "The new password needs at least " + MIN_PASSWORD + " characters.");
      if (fields.next.value !== fields.confirm.value) return void (msg.textContent = "The new passwords are different.");
      save.disabled = true;
      msg.textContent = "Saving...";
      api("/api/v1/auth/password", { method: "POST", body: { current: fields.current.value, next: fields.next.value } }).then(function (r) {
        closeWindow();
        var n = r && typeof r.ended === "number" ? r.ended : 0;
        toast("Password changed" + (n ? "; " + n + " other session" + (n === 1 ? "" : "s") + " logged out" : ""));
      }, function (err) {
        save.disabled = false;
        var b = err && err.body;
        msg.textContent = err && err.status === 401 ? "The current password is wrong." :
          err && err.status === 429 ? tooMany(b) :
          b && b.error ? b.error : "Could not change the password.";
      });
    });
    openWindow("Change password", body, "password-window");
    fields.current.focus();
  }

  el.passwordOpen.addEventListener("click", openPasswordWindow);
  $("account-password").addEventListener("click", openPasswordWindow);

  // ---------- networking ----------

  /**
   * fetch + JSON with an 8 s timeout. Non-GET requests carry the headers the server's browser-write
   * guard requires (X-Stash-Manager: 1 and a JSON content type). Rejects with err.status for HTTP
   * errors and err.network for connection problems.
   */
  function api(path, opts) {
    opts = opts || {};
    var method = opts.method || "GET";
    var ctrl = typeof AbortController === "function" ? new AbortController() : null;
    var timer = ctrl ? window.setTimeout(function () { ctrl.abort(); }, opts.timeout || FETCH_TIMEOUT_MS) : 0;
    var init = {
      method: method,
      cache: opts.cache || "no-store",
      credentials: "same-origin",
      headers: {},
      signal: ctrl ? ctrl.signal : undefined
    };
    if (method !== "GET") {
      init.headers["X-Stash-Manager"] = "1";
      init.headers["Content-Type"] = "application/json";
      if (opts.idempotencyKey) init.headers["Idempotency-Key"] = opts.idempotencyKey;
      init.body = JSON.stringify(opts.body || {});
    }
    return fetch(path, init).then(function (res) {
      window.clearTimeout(timer);
      var date = res.headers.get("Date");
      if (date) {
        var t = Date.parse(date);
        if (!isNaN(t)) state.clockSkew = t - Date.now();
      }
      if (res.status === 401 && path.indexOf("/api/v1/auth/") !== 0) loginRequired();
      if (!res.ok) {
        var err = new Error("HTTP " + res.status);
        err.status = res.status;
        return res.json().then(function (body) {
          err.body = body;
          if (res.status === 403 && body && typeof body === "object" && path.indexOf("/api/v1/auth/") !== 0) {
            if (body.mustChange === true) mustChangeRequired();
            else if (typeof body.permission === "string") permsStale();
          }
          throw err;
        }, function () {
          throw err;
        });
      }
      if (res.status === 204) return null;
      return res.json();
    }, function (e) {
      window.clearTimeout(timer);
      var err = new Error("network: " + (e && e.message));
      err.network = true;
      throw err;
    });
  }

  function renderGuildStock(data) {
    var status = document.getElementById("stock-status");
    var balances = document.getElementById("stock-balances");
    var sources = document.getElementById("stock-sources");
    if (!status || !balances || !sources) return;
    var rows = data && Array.isArray(data.items) ? data.items : [];
    var physical = data && Array.isArray(data.sources) ? data.sources : [];
    status.textContent = rows.length || physical.length ? rows.length + " item variants · " + physical.length + " visible physical sources · revision " + data.stockRevision +
      " · freshness limit " + Math.round(data.maxAgeMillis / 1000) + " s" + (rows.length > 200 ? " · showing 200 of " + rows.length + " item variants" : "") : "No verified physical observations yet. A new scan is required.";
    if (data && Array.isArray(data.automation) && data.automation.length) status.textContent += " · physical sorting active or awaiting reconciliation; new orders paused";
    balances.replaceChildren(); sources.replaceChildren();
    rows.slice(0, 200).forEach(function (item) {
      var row = document.createElement("tr");
      [item.serverLabel || item.server, item.name || item.id, item.observed, item.reserved, item.available, item.unknown].forEach(function (value) {
        var cell = document.createElement("td"); cell.textContent = String(value); row.appendChild(cell);
      }); balances.appendChild(row);
    });
    var stashRows = document.getElementById("stock-stashes");
    stashRows.replaceChildren();
    (data && Array.isArray(data.stashes) ? data.stashes : []).forEach(function (stash) {
      (stash.items || []).forEach(function (item) {
        var row = document.createElement("tr");
        [stash.stash, item.name || item.id, item.observed, item.reserved, item.available, item.unknown].forEach(function (value) {
          var cell = document.createElement("td"); cell.textContent = String(value); row.appendChild(cell);
        }); stashRows.appendChild(row);
      });
    });
    physical.forEach(function (source) {
      var row = document.createElement("li");
      var position = Array.isArray(source.parts) ? " · " + source.parts.map(function (p) { return p.join(", "); }).join(" / ") : "";
      var observedDate = new Date(Number(source.verifiedAt));
      var observedLabel = Number(source.verifiedAt) > 0 && Number.isFinite(observedDate.getTime()) ? observedDate.toISOString() : "not verified";
      var sourceState = ({FRESH: "Fresh", PROTECTED: "Protected stock; automation blocked", STALE_STARTUP: "Rescan required after restart", STALE_AGE: "Observation expired", STALE: "Rescan required", UNREACHABLE: "Cannot reach container", UNKNOWN: "Not read yet", MISSING: "Container missing", WITHDRAWN: "Absent from latest scan; contents unknown", SHARED_SOURCE: source.slotSchema ? "Shared physical source" : "Shared source; ordering paused"})[source.state] || source.state;
      row.textContent = (source.serverLabel || source.server) + " · " + (Array.isArray(source.stashes) ? source.stashes.join(", ") : source.stash) + " · " + (source.dimension || "") + position + " · " + sourceState +
        " · revision " + source.sourceRevision + " · last verified " + observedLabel;
      if (source.id && source.slotSchema) {
        var details = document.createElement("details");
        var summary = document.createElement("summary"); summary.textContent = "Inspect last verified slots (" + source.slotCount + ")"; details.appendChild(summary);
        details.addEventListener("toggle", function () {
          if (!details.open || details.dataset.loaded) return;
          details.dataset.loaded = "true";
          api("/api/v1/stock/source?id=" + encodeURIComponent(source.id)).then(function (data) {
            var note = document.createElement("p"); note.textContent = "State: " + data.state + ". Slot paths identify locations; fingerprints describe contents. Uncertain contents require a new physical read."; details.appendChild(note);
            var table = document.createElement("table");
            var returnSlots = new Map();
            if (Array.isArray(data.returnIntake)) {
              (data.slots || []).forEach(function (slot) {
                if (slot.present && slot.kind === "SHULKER" && slot.count === 1 && !slot.path.includes("/")) returnSlots.set(slot.path, slot);
              });
            }
            ((data.snapshot && data.snapshot.positions) || []).forEach(function (slot) {
              var line = document.createElement("tr");
              [slot.path, slot.id, slot.count, slot.copies, slot.kind, slot.components].forEach(function (value) {
                var cell = document.createElement("td"); cell.textContent = String(value); line.appendChild(cell);
              });
              var verified = returnSlots.get(slot.path);
              if (verified) line.appendChild(returnIntakeControls(data, verified));
              table.appendChild(line);
            }); details.appendChild(table);
          }, function () { details.dataset.loaded = ""; var error = document.createElement("p"); error.textContent = "Snapshot unavailable. Reopen to retry."; details.appendChild(error); });
        }); row.appendChild(details);
      }
      sources.appendChild(row);
    });
  }

  function returnIntakeControls(source, slot) {
    var cell = document.createElement("td");
    var status = document.createElement("p");
    var saved = source.returnIntake.find(function (entry) { return entry.path === slot.path; });
    status.setAttribute("role", "status");
    function show(decision) {
      saved = decision;
      status.textContent = decision.state === "UUID_REQUESTED" ? "UUID marking requested" : "Return recorded with its current name";
    }
    if (saved) show(saved);
    var buttons = [false, true].map(function (markUuid) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = "mc-button small";
      button.textContent = markUuid ? "Request UUID marking" : "Record return and keep name";
      button.addEventListener("click", function () {
        buttons.forEach(function (control) { control.disabled = true; });
        api("/api/v1/stock/return-intake", {method: "POST", body: {
          source: source.id, path: slot.path, slotRevision: slot.revision, markUuid: markUuid,
          expectedDecisionRevision: saved && saved.decisionRevision || 0
        }}).then(show, function (error) {
          status.textContent = error.body && error.body.error || "Could not record the return.";
        }).finally(function () {
          buttons.forEach(function (control) { control.disabled = false; });
        });
      });
      cell.appendChild(button);
      return button;
    });
    cell.appendChild(status);
    return cell;
  }

  function poll() {
    if (state.polling || auth.blocked) return;
    state.polling = true;
    if (can("view")) api("/api/v1/stock").then(renderGuildStock, function () {
      var status = document.getElementById("stock-status"); if (status) status.textContent = "Source status unavailable. Stock freshness cannot be confirmed.";
    });
    // Without "view" the server refuses the stash list: don't ask, and don't call that "unreachable".
    var request = can("view") ? api("/api/v1/stashes") : Promise.resolve([]);
    request.then(function (list) {
      state.unreachable = false;
      state.stashes = Array.isArray(list) ? list : [];
      state.polledOnce = true;
    }, function () {
      state.unreachable = true;
    }).then(function () {
      state.polling = false;
      // Rendering runs outside the fetch's error handler, so a render bug can't look like "unreachable".
      updateSelect();
      updateHeader();
      renderBotStatus();
      if (!state.unreachable) {
        maybeLoadIndex();
        maybeAutoOpenLink();
        deliveryMaybeLoad();
      }
    });
  }

  function currentStash() {
    for (var i = 0; i < state.stashes.length; i++) {
      if (stashKey(state.stashes[i]) === state.selected) return state.stashes[i];
    }
    return null;
  }

  function maybeLoadIndex() {
    var s = currentStash();
    if (!s || s.indexedAt == null) {
      if (state.loadedWant !== null || state.items.length) clearIndex();
      showEmpty();
      return;
    }
    var want = state.selected + "|" + s.indexedAt;
    if (want === state.loadedWant || want === state.pendingWant) return;

    var token = ++state.loadToken;
    var key = state.selected;
    state.pendingWant = want;
    // "no-cache" lets the browser revalidate with the ETag (304) instead of downloading again.
    api("/api/v1/index?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash), { cache: "no-cache" })
      .then(function (index) {
        if (token !== state.loadToken) return; // a newer selection or index superseded this one
        state.pendingWant = null;
        var sameStash = state.loadedWant !== null && state.loadedWant.indexOf(key + "|") === 0;
        var scroll = el.gridWrap.scrollTop;
        state.index = index;
        state.loadedWant = want;
        updateItems(index);
        if (!sameStash) onStashChanged();
        renderTabs();
        renderNow();
        updateHeader();
        refreshOrdersView();
        el.gridWrap.scrollTop = sameStash ? scroll : 0;
      }, function (e) {
        if (token !== state.loadToken) return;
        state.pendingWant = null;
        if (e && e.status === 404) {
          clearIndex();
          showEmpty();
        }
      });
  }

  // ---------- stash selection ----------

  function savedSelection() {
    try {
      return window.localStorage.getItem(STORAGE_KEY);
    } catch (e) {
      return null;
    }
  }

  function saveSelection(key) {
    try {
      if (key) window.localStorage.setItem(STORAGE_KEY, key);
    } catch (e) {
      // Storage disabled: just don't remember.
    }
  }

  function pickDefault(keys) {
    var saved = savedSelection();
    if (saved && keys.indexOf(saved) >= 0) return saved;
    for (var i = 0; i < state.stashes.length; i++) {
      if (state.stashes[i].indexedAt != null) return keys[i];
    }
    return keys.length ? keys[0] : null;
  }

  /** "● stash · player @ server" (● online, ○ offline). */
  function stashLabel(s) {
    var who = str(s.owner) || str(s.player);
    return (s.online ? "\u25cf " : "\u25cb ") + str(s.stash) + (who ? " \u00b7 " + who : "") + (streamerOn() ? (str(s.serverLabel) ? " @ " + str(s.serverLabel) : "") : " @ " + str(s.server));
  }

  /** Switches the page to another stash (the stash picker and the Bots overview). */
  function selectStash(key) {
    if (key === state.selected) return;
    state.selected = key || null;
    el.select.value = key || "";
    saveSelection(state.selected);
    clearIndex();
    onStashChanged();
    updateHeader();
    maybeLoadIndex();
  }

  function updateSelect() {
    var keys = state.stashes.map(stashKey);
    if (state.selected === null || keys.indexOf(state.selected) < 0) {
      state.selected = pickDefault(keys);
    }
    var labels = state.stashes.map(stashLabel);
    var opts = el.select.options;
    var same = opts.length === Math.max(1, keys.length) && keys.every(function (k, i) {
      return opts[i].value === k && opts[i].textContent === labels[i];
    });
    if (!same) {
      var frag = document.createDocumentFragment();
      if (!keys.length) {
        var none = document.createElement("option");
        none.value = "";
        none.textContent = "(none)";
        frag.appendChild(none);
      }
      keys.forEach(function (k, i) {
        var o = document.createElement("option");
        o.value = k;
        o.textContent = labels[i];
        frag.appendChild(o);
      });
      el.select.replaceChildren(frag);
    }
    el.select.value = state.selected === null ? "" : state.selected;
    el.select.disabled = keys.length <= 1;
  }

  // ---------- header ----------

  function setDot(dot, online) {
    dot.className = "dot " + (online ? "dot-on" : "dot-off");
  }

  var CONFLICT_SHOW_MS = 24 * 60 * 60 * 1000;

  function conflictText(s) {
    var c = s && s.conflict;
    if (!c || typeof c !== "object" || serverNow() - num(c.at, 0) > CONFLICT_SHOW_MS) return "";
    return "Another bot (" + str(c.player) + ") tried to use stash '" + str(s.stash) + "' at " +
      new Date(num(c.at, 0)).toLocaleString() + ". It was refused: give that bot its own stash name.";
  }

  function updateHeader() {
    updateRescan();
    var warn = conflictText(currentStash());
    el.conflictWarn.hidden = !warn;
    if (el.conflictWarn.textContent !== warn) el.conflictWarn.textContent = warn;
    if (bots.body && bots.body.isConnected) renderBots();
    var s = currentStash();
    if (state.unreachable) {
      setDot(el.dot, false);
      el.botText.textContent = "Server unreachable";
    } else if (!s) {
      setDot(el.dot, false);
      el.botText.textContent = "No stash";
    } else {
      var who = s.player ? str(s.player) + " " : "Bot ";
      setDot(el.dot, !!s.online);
      el.botText.textContent = who + (s.online ? "online" : "offline") + " \u00b7 last seen " + ago(s.lastSeen);
    }

    if (!s) {
      el.infoIndex.textContent = "";
      el.infoContainers.textContent = "";
      el.infoMeta.textContent = "";
      el.indexMeta.textContent = "";
      el.tileContainers.textContent = "\u2013";
      el.tileStale.textContent = "\u2013";
      el.tileIndexed.textContent = "\u2013";
      return;
    }
    if (s.indexedAt != null) {
      el.infoIndex.textContent = "Indexed " + ago(s.indexedAt);
      el.infoIndex.title = new Date(s.indexedAt).toLocaleString();
    } else {
      el.infoIndex.textContent = "No index yet";
      el.infoIndex.title = "";
    }

    var loaded = state.loadedWant !== null && state.loadedWant.indexOf(state.selected + "|") === 0;
    var idx = loaded ? state.index : null;
    var c = idx && idx.containers && typeof idx.containers === "object" ? idx.containers : null;
    el.infoContainers.textContent = c
      ? "Containers: " + grouped(num(c.known, 0)) + " / " + grouped(num(c.total, 0)) + " known \u00b7 " + grouped(num(c.stale, 0)) + " stale"
      : "";
    var meta = [];
    if (idx) {
      if (str(idx.dimension)) meta.push(str(idx.dimension));
      if (str(idx.mcVersion)) meta.push("MC " + str(idx.mcVersion));
    }
    el.infoMeta.textContent = meta.join(" \u00b7 ");
    // The same values for the stash index card (mobile layout).
    el.tileIndexed.textContent = s.indexedAt != null ? ago(s.indexedAt) : "never";
    el.tileContainers.textContent = c ? grouped(num(c.known, 0)) + " / " + grouped(num(c.total, 0)) : "\u2013";
    el.tileStale.textContent = c ? grouped(num(c.stale, 0)) : "\u2013";
    var shortMeta = [];
    if (idx && str(idx.dimension)) shortMeta.push(str(idx.dimension).replace(/^minecraft:/, ""));
    if (idx && str(idx.mcVersion)) shortMeta.push("MC " + str(idx.mcVersion));
    el.indexMeta.textContent = shortMeta.join(" \u00b7 ");
  }

  // ---------- bots overview ----------

  var bots = { body: null };

  function botDoing(s) {
    var o = s.orders && typeof s.orders === "object" ? s.orders : null;
    var active = o && o.active && typeof o.active === "object" ? o.active : null;
    var c = s.containers && typeof s.containers === "object" ? s.containers : null;
    var stale = c ? num(c.stale, 0) : 0;
    if (active) return { text: "Order " + str(active.id) + ": " + (str(active.phase) || STATUS_LABEL[active.status] || str(active.status)) };
    if (str(s.orderBlocker)) return { text: "Can't take orders: " + str(s.orderBlocker), warn: true };
    if (typeof s.rescanPending === "number") return { text: "Rescan requested" };
    if (str(s.rescanBlocked)) return { text: "Can't rescan: " + str(s.rescanBlocked), warn: true };
    if (stale > 0) return { text: "Re-reading containers: " + grouped(Math.max(0, num(c.total, 0) - stale)) + " / " + grouped(num(c.total, 0)) };
    return { text: "Idle" };
  }

  function cell(cls, text) {
    var d = document.createElement("div");
    d.className = "bots-cell " + cls;
    d.textContent = text;
    return d;
  }

  function renderBots() {
    if (!bots.body) return;
    var frag = document.createDocumentFragment();
    var head = document.createElement("div");
    head.className = "bots-row bots-head";
    ["Bot", "Stash", "Status", "Doing", "Delivery container", "Open orders", ""].forEach(function (t) { head.appendChild(cell("", t)); });
    frag.appendChild(head);
    if (!state.stashes.length) {
      frag.appendChild(line("window-info", "No bots yet. Use Link addon to connect one."));
    }
    state.stashes.forEach(function (s) {
      var key = stashKey(s);
      var row = document.createElement("div");
      row.className = "bots-row" + (key === state.selected ? " selected" : "");
      row.title = "Show this stash";
      var who = document.createElement("div");
      who.className = "bots-cell bots-who";
      var dot = document.createElement("span");
      setDot(dot, !!s.online);
      who.appendChild(dot);
      who.appendChild(document.createTextNode(" " + (str(s.owner) || str(s.player) || "(no owner)")));
      row.appendChild(who);
      var where = cell("", str(s.stash) + " @ ");
      where.appendChild(hiddenSpan(str(s.server)));
      row.appendChild(where);
      row.appendChild(cell("", (s.online ? "online" : "offline") + " \u00b7 " + ago(num(s.lastSeen, null))));
      var doing = botDoing(s);
      row.appendChild(hide(cell(doing.warn ? "warn" : "", doing.text)));
      var d = s.delivery;
      var dc = cell(d && typeof d === "object" ? "" : "warn",
        d && typeof d === "object" ? num(d.x, 0) + ", " + num(d.y, 0) + ", " + num(d.z, 0) : s.deliveryConfigured ? "set" : "not set");
      row.appendChild(d && typeof d === "object" ? hide(dc) : dc);
      row.appendChild(cell("bots-num", String(s.orders && typeof s.orders === "object" ? num(s.orders.open, 0) : 0)));
      var actions = document.createElement("div");
      actions.className = "bots-cell";
      if (str(s.owner) && can("link")) {
        var rel = document.createElement("button");
        rel.type = "button";
        rel.className = "mc-button small danger";
        rel.textContent = "Release";
        rel.addEventListener("click", function (e) {
          e.stopPropagation();
          releaseStash(s);
        });
        actions.appendChild(rel);
      }
      row.appendChild(actions);
      var warn = conflictText(s);
      if (warn) {
        var w = cell("warn bots-conflict", warn);
        row.appendChild(w);
      }
      row.addEventListener("click", function () {
        selectStash(key);
        closeWindow();
        if (dash.page === "bots") showPage("stash");
      });
      frag.appendChild(row);
    });
    bots.body.replaceChildren(frag);
  }

  function releaseStash(s) {
    var sure = window.confirm("Release stash '" + str(s.stash) + "' from " + str(s.owner) + "?\n\nAnother bot can then take it over by " +
      "using this stash name. If " + str(s.owner) + " is still running with this stash name, it takes it back on its next heartbeat.");
    if (!sure) return;
    api("/api/v1/stashes/release", { method: "POST", body: { server: s.server, stash: s.stash } }).then(function () {
      toast("Released '" + str(s.stash) + "'");
      poll();
    }, function (e) {
      toast("Could not release" + (e && e.body && e.body.error ? ": " + e.body.error : e && e.status ? " (HTTP " + e.status + ")" : ""));
    });
  }


  // ---------- manual rescan ----------
  // The bot acknowledges a request (rescanPending goes back to null), marks every container stale and re-reads
  // them; progress comes from the stash's containers.stale count. "watching" is this page's memory of a rescan
  // it has seen requested, so "Rescan complete" is only shown after one.

  var RESCAN_DONE_MS = 6000;
  var rescan = { key: null, busy: false, watching: false, lastPending: null, ackAt: 0, sawStale: false, doneUntil: 0 };

  function updateRescan() {
    var s = currentStash();
    if (!s || state.unreachable || !can("rescan")) {
      el.rescanBox.hidden = true;
      return;
    }
    el.rescanBox.hidden = false;
    if (rescan.key !== state.selected) {
      rescan = { key: state.selected, busy: false, watching: false, lastPending: null, ackAt: 0, sawStale: false, doneUntil: 0 };
    }
    var pending = typeof s.rescanPending === "number" ? s.rescanPending : null;
    var blocked = str(s.rescanBlocked);
    var c = s.containers && typeof s.containers === "object" ? s.containers : null;
    var total = c ? Math.max(0, num(c.total, 0)) : 0;
    var stale = c ? Math.max(0, num(c.stale, 0)) : 0;
    var now = serverNow();

    if (pending !== null) {
      rescan.watching = true;
      rescan.lastPending = pending;
      rescan.ackAt = 0;
      rescan.sawStale = false;
    } else if (rescan.lastPending !== null) {
      rescan.lastPending = null; // acknowledged by the bot
      rescan.ackAt = now;
    }
    if (rescan.watching && pending === null && stale > 0) rescan.sawStale = true;

    var text = "";
    var warn = false;
    var progress = -1;
    if (pending !== null && !s.online) {
      text = "Bot offline: rescan will start when it reconnects";
      warn = true;
    } else if (pending !== null) {
      text = "Rescan requested: waiting for the bot";
    } else if (blocked && rescan.watching) {
      text = "Can't rescan yet: " + blocked;
      warn = true;
    } else if (rescan.watching && stale > 0) {
      text = "Re-reading containers: " + grouped(Math.max(0, total - stale)) + " / " + grouped(total);
      progress = total > 0 ? (total - stale) / total : 0;
    } else if (rescan.watching && (rescan.sawStale || (rescan.ackAt && num(s.indexedAt, 0) > rescan.ackAt))) {
      rescan.watching = false;
      rescan.doneUntil = now + RESCAN_DONE_MS;
      text = "Rescan complete";
    } else if (rescan.watching) {
      text = "Rescan started: waiting for the bot's index";
    } else if (now < rescan.doneUntil) {
      text = "Rescan complete";
    }
    if (el.rescanStatus.textContent !== text) el.rescanStatus.textContent = text;
    el.rescanStatus.classList.toggle("warn", warn);
    el.rescanStatus.classList.toggle("done", text === "Rescan complete");
    el.rescanProgress.hidden = progress < 0;
    if (progress >= 0) el.rescanFill.style.transform = "scaleX(" + Math.max(0, Math.min(1, progress)) + ")";
    el.rescanBtn.disabled = rescan.busy || pending !== null;
    el.rescanBtn.title = pending !== null ? "A rescan is already waiting for the bot" : "Ask the bot to re-read every container";
  }

  el.rescanBtn.addEventListener("click", function () {
    var s = currentStash();
    if (!s || rescan.busy) return;
    var sure = window.confirm("Rescan this stash?\n\nThe bot will re-read every container in this stash (nearest first) and pick up " +
      "new containers inside the stash area. Items already listed stay visible until their container is read again. " +
      "Works with sorting turned off.");
    if (!sure) return;
    rescan.busy = true;
    updateRescan();
    api("/api/v1/rescan", { method: "POST", body: { server: s.server, stash: s.stash } }).then(function (r) {
      s.rescanPending = r && typeof r.rescanId === "number" ? r.rescanId : 0;
      rescan.busy = false;
      updateRescan();
      toast("Rescan requested");
      poll();
    }, function (e) {
      rescan.busy = false;
      updateRescan();
      toast(e && e.status === 404 ? "The server doesn't know this stash yet." :
        "Could not request a rescan" + (e && e.body && e.body.error ? ": " + e.body.error : e && e.status ? " (HTTP " + e.status + ")" : ""));
    });
  });

  // ---------- items ----------

  function clearIndex() {
    state.index = null;
    state.loadedWant = null;
    state.pendingWant = null;
    state.loadToken++;
    state.items = [];
    state.byKey = new Map();
    state.shown = [];
    state.shulkers = [];
    state.shulkerByKey = new Map();
    renderTabs();
    el.grid.replaceChildren();
    hideTooltip();
    updateSummary(0, 0);
  }

  function showEmpty() {
    el.emptyText.textContent = !can("view")
      ? "Your account has no access to stashes; ask the owner."
      : currentStash()
      ? "No stash index yet for this stash. Waiting for the addon to send one."
      : "No stash index yet. Turn on stash-manager in the addon and set its API key.";
    el.empty.hidden = false;
  }

  function splitId(id) {
    var i = id.indexOf(":");
    return i < 0 ? ["minecraft", id] : [id.slice(0, i), id.slice(i + 1)];
  }

  /**
   * The v token names the icon source generation and the index's Minecraft version (the server renders
   * from that version's game jar). Changing ICON_SOURCE_TOKEN or the version changes every URL, so icons the
   * browser cached for a week under an older scheme (e.g. wiki PNGs) are not reused.
   */
  function iconUrl(id, mc) {
    var p = splitId(id);
    return "/icon/" + encodeURIComponent(p[0]) + "/" + encodeURIComponent(p[1]) + ".png?v=" +
      encodeURIComponent(mc + "-" + ICON_SOURCE_TOKEN);
  }

  function indexMcVersion(index) {
    var mc = str(index.mcVersion);
    return /^[0-9][0-9.]{0,31}$/.test(mc) ? mc : DEFAULT_MC;
  }

  function retryDelay(tries) {
    return tries <= ICON_RETRY_DELAYS.length ? ICON_RETRY_DELAYS[tries - 1] : 10000;
  }

  function normalizeItem(it) {
    var id = str(it.id);
    var name = str(it.name) || id;
    return {
      key: str(it.key),
      id: id,
      name: name,
      englishName: str(it.englishName) || name,
      count: Math.max(0, Math.floor(num(it.count, 0))),
      maxStack: Math.max(1, Math.floor(num(it.maxStack, 64))),
      details: Array.isArray(it.details) ? it.details.filter(function (d) { return typeof d === "string"; }) : [],
      enchanted: it.enchanted === true,
      tab: typeof it.tab === "string" && TAB_INDEX.hasOwnProperty(it.tab) ? it.tab : "other",
      tabOrder: num(it.tabOrder, 1000000),
      // Bots that report durability send these only for a damaged, damageable stack.
      damage: Math.max(0, Math.floor(num(it.damage, 0))),
      maxDamage: Math.max(0, Math.floor(num(it.maxDamage, 0)))
    };
  }

  /** Remaining durability from 0 to 1 for a damaged item, or -1 when it isn't damaged (or the bot doesn't say). */
  function durabilityLeft(item) {
    if (!item || !(item.maxDamage > 0) || !(item.damage > 0)) return -1;
    return Math.max(0, Math.min(1, (item.maxDamage - item.damage) / item.maxDamage));
  }

  /**
   * The game's durability bar on an icon host (a .slot or an .icon-wrap): a black bar 13/16 of the icon wide and
   * 2/16 high near the bottom, with the coloured part over its top half. Width round(13 x left) of 13, colour from
   * green to red (the game's hsv(left / 3, 1, 1), which is hsl(120 x left, 100%, 50%)). Removed when not damaged.
   */
  function setDurability(host, item) {
    if (!host) return;
    var left = durabilityLeft(item);
    var bar = null;
    for (var c = host.firstElementChild; c; c = c.nextElementSibling) if (c.classList.contains("dura")) bar = c;
    if (left < 0) {
      if (bar) bar.remove();
      return;
    }
    if (!bar) {
      bar = document.createElement("span");
      bar.className = "dura";
      bar.setAttribute("aria-hidden", "true");
      var fill = document.createElement("span");
      fill.className = "dura-fill";
      bar.appendChild(fill);
      host.appendChild(bar);
    }
    var px = Math.round(13 * left);
    bar.firstChild.style.width = (px / 13 * 100) + "%";
    bar.firstChild.style.backgroundColor = "hsl(" + Math.round(120 * left) + ", 100%, 50%)";
  }

  function haystack(item) {
    return [item.name, item.englishName, item.id].concat(item.details).join("\n").toLowerCase();
  }

  /** Adds or removes the glint. It is only added once the real icon has loaded, since it is masked by it. */
  function syncGlint(entry) {
    if (entry.item.enchanted && entry.iconLoaded) {
      if (!entry.glint) {
        var glint = document.createElement("div");
        glint.className = "glint";
        // Set through the CSSOM (allowed by the CSP). The URL is our own /icon/ path (encodeURIComponent
        // output), already in the browser's image cache, so the mask costs no extra request.
        var mask = "url(\"" + entry.img.getAttribute("src").replace(/["\\]/g, "") + "\")";
        glint.style.webkitMaskImage = mask;
        glint.style.maskImage = mask;
        glint.appendChild(document.createElement("div")).className = "sheen";
        entry.slot.insertBefore(glint, entry.countEl);
        entry.glint = glint;
      }
    } else if (entry.glint) {
      entry.glint.remove();
      entry.glint = null;
    }
  }

  /** The number a slot shows: for a shulker-box item with filled groups, only the empty ones. */
  function slotCount(entry) {
    return typeof entry.emptyCount === "number" ? entry.emptyCount : entry.item.count;
  }

  function setCount(entry) {
    var n = slotCount(entry);
    var text = n === 1 ? "" : compact(n);
    if (entry.countEl.textContent !== text) entry.countEl.textContent = text;
  }

  function createEntry(item, mc) {
    var slot = document.createElement("div");
    slot.className = "slot";
    var img = document.createElement("img");
    img.alt = "";
    img.loading = "lazy";
    img.decoding = "async";
    img.width = 32;
    img.height = 32;
    img.draggable = false;
    var countEl = document.createElement("span");
    countEl.className = "count";
    slot.appendChild(img);
    slot.appendChild(countEl);
    setDurability(slot, item);
    // Touch layout only (CSS hides it elsewhere): the shift-click action, as a visible button.
    var addBtn = document.createElement("button");
    addBtn.type = "button";
    addBtn.className = "slot-add";
    addBtn.textContent = "+";
    addBtn.setAttribute("aria-label", "Add a stack of " + item.name + " to the cart");
    slot.appendChild(addBtn);
    slot.addEventListener("click", gridSlotClick);

    var entry = {
      item: item,
      slot: slot,
      img: img,
      countEl: countEl,
      glint: null,
      iconLoaded: false,
      tries: 0,
      url: iconUrl(item.id, mc),
      ns: splitId(item.id)[0].toLowerCase(),
      hay: haystack(item)
    };

    img.addEventListener("load", function () {
      if (img.getAttribute("src") === PLACEHOLDER) return;
      entry.iconLoaded = true;
      syncGlint(entry);
    });
    // The server answers 503 while it is still fetching an icon. Keep the placeholder up and ask again
    // quickly (1s, 2s, 3s, 5s, then every 10s); the query string only defeats the browser's image cache.
    img.addEventListener("error", function () {
      if (img.getAttribute("src") !== PLACEHOLDER) img.src = PLACEHOLDER;
      entry.tries++;
      if (entry.tries > ICON_RETRIES) return;
      var n = entry.tries;
      window.setTimeout(function () {
        if (img.isConnected && !entry.iconLoaded) img.src = entry.url + "&retry=" + n;
      }, retryDelay(n));
    });
    img.src = entry.url;
    setCount(entry);
    if (item.isShulker) setFace(entry, shulkerFace(item.contents));
    slotEntry.set(slot, entry);
    return entry;
  }

  // ---------- filled-shulker icons ----------
  // A filled shulker is drawn as its box with the item it holds most of on top (by total count over all its
  // stacks, items of the same id counted together; a tie goes to the item in the lowest slot). When it holds
  // more than one kind of item, the second most common one is drawn small in the top-left corner as the
  // "mixed" marker. A plain shulker icon always means an empty one. The tooltip and the shulker window still
  // list the full contents.

  /** { id, second } for a shulker's contents (second: null unless mixed), or null when it is empty. */
  function shulkerFace(contents) {
    if (!contents || !contents.length) return null;
    var totals = new Map();
    var firstSlot = new Map();
    contents.forEach(function (c) {
      var it = itemByKey(c.key);
      var id = it ? it.id : String(c.key).split("#")[0];
      totals.set(id, (totals.get(id) || 0) + c.count);
      if (!firstSlot.has(id) || c.slot < firstSlot.get(id)) firstSlot.set(id, c.slot);
    });
    var ids = Array.from(totals.keys()).sort(function (x, y) {
      return totals.get(y) - totals.get(x) || firstSlot.get(x) - firstSlot.get(y);
    });
    return ids.length ? { id: ids[0], second: ids.length > 1 ? ids[1] : null } : null;
  }

  /**
   * Puts (or clears, for null) the contents icon and the mixed marker on a slot-like holder: anything with
   * .slot and .countEl (grid entries, pooled plan slots, icon wrappers). Images only reload when a URL changes.
   */
  function setFace(h, face) {
    h.faceImg = swapFaceImage(h, h.faceImg, face ? iconUrl(face.id, state.mc) : null, "face");
    h.face2Img = swapFaceImage(h, h.face2Img, face && face.second ? iconUrl(face.second, state.mc) : null, "face face-second");
    h.slot.classList.toggle("has-face", !!face);
  }

  function swapFaceImage(h, img, url, cls) {
    if (img && img.dataset.url === url) return img;
    if (img) img.remove();
    return url ? faceImage(url, h, cls) : null;
  }

  /** A contents <img>, with the same 503 retry as the slot icon. */
  function faceImage(url, h, cls) {
    var img = document.createElement("img");
    img.alt = "";
    img.className = cls;
    img.decoding = "async";
    img.width = 20;
    img.height = 20;
    img.draggable = false;
    img.dataset.url = url;
    var tries = 0;
    img.addEventListener("error", function () {
      if (img.getAttribute("src") !== PLACEHOLDER) img.src = PLACEHOLDER;
      tries++;
      if (tries > ICON_RETRIES) return;
      var n = tries;
      window.setTimeout(function () {
        if (img.isConnected) img.src = url + "&retry=" + n;
      }, retryDelay(n));
    });
    img.src = url;
    h.slot.insertBefore(img, h.countEl || null);
    return img;
  }

  /** iconImg for a line that may be a filled shulker: the icon plus its contents icon, in a small wrapper. */
  function iconWithContents(id, contents, size) {
    var face = shulkerFace(contents);
    var icon = iconImg(id, size);
    if (!face) return icon;
    var wrap = document.createElement("span");
    wrap.className = "icon-wrap";
    wrap.appendChild(icon);
    setFace({ slot: wrap, countEl: null }, face);
    return wrap;
  }

  /**
   * Applies a new index. Slots are reused by item key, so an index refresh (frequent: the addon pushes on
   * every stash change) updates counts and details in place and never recreates images or glints.
   */
  function updateItems(index) {
    var raw = Array.isArray(index.items) ? index.items : [];
    var mc = indexMcVersion(index);
    var next = new Map();
    var out = [];
    for (var i = 0; i < raw.length; i++) {
      var it = raw[i];
      if (!it || typeof it !== "object") continue;
      var item = normalizeItem(it);
      if (!item.key || next.has(item.key)) continue;
      var entry = state.byKey.get(item.key);
      if (!entry || entry.item.id !== item.id || entry.url !== iconUrl(item.id, mc)) {
        entry = createEntry(item, mc);
      } else {
        var old = entry.item;
        entry.item = item;
        if (old.count !== item.count) setCount(entry);
        if (old.enchanted !== item.enchanted) syncGlint(entry);
        if (old.damage !== item.damage || old.maxDamage !== item.maxDamage) setDurability(entry.slot, item);
        if (old.name !== item.name || old.englishName !== item.englishName || old.details.join("\n") !== item.details.join("\n")) {
          entry.hay = haystack(item);
        }
      }
      next.set(item.key, entry);
      out.push(entry);
    }
    state.byKey = next; // entries for removed keys are dropped here and leave the DOM in renderNow
    state.items = out;
    state.mc = mc;
    updateShulkers(index, mc);
  }

  function parseQuery(q) {
    var terms = [];
    var namespaces = [];
    q.toLowerCase().split(/\s+/).forEach(function (t) {
      if (!t) return;
      if (t.charAt(0) === "@") {
        if (t.length > 1) namespaces.push(t.slice(1));
      } else {
        terms.push(t);
      }
    });
    return { terms: terms, namespaces: namespaces };
  }

  function matches(entry, query) {
    for (var i = 0; i < query.namespaces.length; i++) {
      if (entry.ns.indexOf(query.namespaces[i]) !== 0) return false;
    }
    for (var j = 0; j < query.terms.length; j++) {
      if (entry.hay.indexOf(query.terms[j]) < 0) return false;
    }
    return true;
  }

  var collator = typeof Intl !== "undefined" ? new Intl.Collator(undefined, { sensitivity: "base", numeric: true }) : null;

  function cmp(a, b) {
    if (collator) return collator.compare(a, b);
    return a < b ? -1 : a > b ? 1 : 0;
  }

  /** Damaged copies of the same item: the most worn first, undamaged ones last. */
  function duraOrder(a, b) {
    if (a.item.id !== b.item.id) return 0;
    var x = durabilityLeft(a.item), y = durabilityLeft(b.item);
    return (x < 0 ? 2 : x) - (y < 0 ? 2 : y);
  }

  var sorters = {
    creative: function (a, b) {
      return (TAB_INDEX[a.item.tab] - TAB_INDEX[b.item.tab]) || (a.item.tabOrder - b.item.tabOrder) ||
        cmp(a.item.name, b.item.name) || duraOrder(a, b) || cmp(a.item.key, b.item.key);
    },
    count: function (a, b) {
      return b.item.count - a.item.count || cmp(a.item.name, b.item.name) || cmp(a.item.key, b.item.key);
    },
    name: function (a, b) {
      return cmp(a.item.name, b.item.name) || b.item.count - a.item.count || cmp(a.item.key, b.item.key);
    },
    id: function (a, b) {
      return cmp(a.item.id, b.item.id) || cmp(a.item.key, b.item.key);
    }
  };

  function sameEntries(a, b) {
    if (a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
  }

  function renderNow() {
    window.clearTimeout(state.searchTimer);
    state.searchTimer = 0;
    var query = parseQuery(el.search.value);
    var sortName = el.sort.value;
    if (state.tab === "shulkers" && sortName === "creative") sortName = "count";
    var sorter = sorters[sortName] || sorters.count;
    var source = tabEntries(state.tab);
    var shown;
    if (state.tab === "shulkers") {
      shown = source.filter(function (e) { return matches(e, query); });
      shown.sort(sorter);
    } else {
      // A shulker-box item stays in the list (as the anchor for its filled groups) when only a group matches.
      shown = source.filter(function (e) {
        return matches(e, query) || (e.filledGroups && e.filledGroups.some(function (g) { return matches(g, query); }));
      });
      shown.sort(sorter);
      shown = withFilledGroups(shown, query);
    }
    var total = 0;
    for (var i = 0; i < shown.length; i++) total += slotCount(shown[i]);

    // Only touch the grid when the set or order of slots changed. Count changes were already
    // written into the existing slots by updateItems.
    if (!sameEntries(shown, state.shown)) {
      var frag = document.createDocumentFragment();
      for (var j = 0; j < shown.length; j++) frag.appendChild(shown[j].slot);
      el.grid.replaceChildren(frag);
      state.shown = shown;
    }

    var loaded = state.loadedWant !== null;
    if (loaded && !state.items.length) {
      el.emptyText.textContent = "This stash index has no items.";
      el.empty.hidden = false;
    } else if (loaded) {
      el.empty.hidden = true;
    }
    updateSummary(shown.length, total);
    refreshTooltip();
  }

  function queueRender(delay) {
    window.clearTimeout(state.searchTimer);
    state.searchTimer = window.setTimeout(renderNow, delay || 0);
  }

  /** How many slots the current tab has without a search (filled shulker groups count as their own slots). */
  function tabSlotCount() {
    var source = tabEntries(state.tab);
    if (state.tab === "shulkers") return source.length;
    var n = 0;
    source.forEach(function (e) {
      n += e.filledGroups ? (e.emptyCount > 0 ? 1 : 0) + e.filledGroups.length : 1;
    });
    return n;
  }

  function updateSummary(shownCount, shownTotal) {
    var all = state.items.length ? tabSlotCount() : 0;
    if (!all) {
      el.summary.textContent = "";
      return;
    }
    var what = state.tab === "shulkers" ? " filled shulker kinds \u00b7 total " : " slots \u00b7 total ";
    el.summary.textContent = "Showing " + grouped(shownCount) + " of " + grouped(all) + what +
      grouped(shownTotal || 0) + (state.tab === "shulkers" ? " shulkers" : " items");
  }

  // ---------- tooltip ----------
  // Layout is read only when the tooltip's content changes (showTooltip) and on resize. Mouse moves just
  // store the position; one requestAnimationFrame callback per frame writes a transform.

  var tip = { w: 0, h: 0, x: 0, y: 0, raf: 0 };
  var viewport = { w: 0, h: 0 };

  function measureViewport() {
    viewport.w = document.documentElement.clientWidth;
    viewport.h = document.documentElement.clientHeight;
  }
  measureViewport();
  window.addEventListener("resize", measureViewport, { passive: true });

  function line(cls, text) {
    var d = document.createElement("div");
    d.className = cls;
    d.textContent = text;
    return d;
  }

  function plural(text, word) {
    return text + " " + word + (text === "1" ? "" : "s");
  }

  function showTooltip(entry) {
    var it = entry.item;
    var frag = document.createDocumentFragment();
    var nameCls = "tt-name" + (it.enchanted ? " ench" : "") + (it.name !== it.englishName ? " custom" : "");
    frag.appendChild(line(nameCls, it.name));
    for (var i = 0; i < it.details.length; i++) frag.appendChild(line("tt-line", it.details[i]));
    if (durabilityLeft(it) >= 0 && !it.details.some(function (d) { return /^Durability/i.test(d); })) {
      frag.appendChild(line("tt-line", "Durability: " + grouped(it.maxDamage - it.damage) + " / " + grouped(it.maxDamage)));
    }
    if (it.plain) {
      // Planned shulkers and stacks in the cart view: the details already say everything.
    } else if (it.isShulker) {
      frag.appendChild(line("tt-count", "\u00d7" + grouped(it.count) + (it.count === 1 ? " shulker" : " identical shulkers")));
      frag.appendChild(line("tt-hint", "Right-click to look inside"));
    } else {
      var stacks = approx(it.count / it.maxStack);
      var shulkers = approx(it.count / (it.maxStack * 27));
      frag.appendChild(line("tt-count", "Count: " + grouped(it.count) + " (\u2248 " + plural(stacks, "stack") + ", " +
        plural(shulkers, "shulker") + ")"));
      if (typeof entry.emptyCount === "number") {
        frag.appendChild(line("tt-line", grouped(entry.emptyCount) + " empty, " + grouped(it.count - entry.emptyCount) +
          " filled (listed next to it, showing what they hold)"));
      }
      if (isShulkerBoxId(it.id) && state.byKey.get(it.key) === entry) frag.appendChild(line("tt-hint", "Right-click to see the filled ones"));
    }
    frag.appendChild(line("tt-id", it.id));
    el.tooltip.replaceChildren(frag);
    el.tooltip.hidden = false;
    // The one layout read per hovered item.
    tip.w = el.tooltip.offsetWidth;
    tip.h = el.tooltip.offsetHeight;
    state.hovered = entry;
  }

  function hideTooltip() {
    state.hovered = null;
    el.tooltip.hidden = true;
  }

  function moveTooltip(x, y) {
    tip.x = x;
    tip.y = y;
    if (!tip.raf) tip.raf = window.requestAnimationFrame(applyTooltip);
  }

  function applyTooltip() {
    tip.raf = 0;
    if (!state.hovered) return;
    if (!state.hovered.slot.isConnected) {
      hideTooltip(); // its slot was replaced by a re-render
      return;
    }
    var left = tip.x + 14;
    var top = tip.y - 16;
    if (left + tip.w + 4 > viewport.w) left = tip.x - tip.w - 14;
    if (left < 4) left = 4;
    if (top + tip.h + 4 > viewport.h) top = viewport.h - tip.h - 4;
    if (top < 4) top = 4;
    el.tooltip.style.transform = "translate(" + Math.round(left) + "px, " + Math.round(top) + "px)";
  }

  function entryAt(x, y) {
    var target = document.elementFromPoint(x, y);
    var slot = target && target.closest ? target.closest(".slot") : null;
    if (!slot) return null;
    return slotEntry.get(slot) || null;
  }

  /** After a re-render, keep the tooltip if the cursor is still over the same item key. */
  function refreshTooltip() {
    if (!state.hovered) return;
    var key = state.hovered.item.key;
    var entry = state.mouse.over ? entryAt(state.mouse.x, state.mouse.y) : null;
    if (entry && entry.item.key === key) {
      showTooltip(entry);
      moveTooltip(state.mouse.x, state.mouse.y);
    } else {
      hideTooltip();
    }
  }

  // Delegated on the document so slots in windows and the order picker get tooltips too.
  document.addEventListener("mouseover", function (e) {
    var slot = e.target.closest ? e.target.closest(".slot") : null;
    var entry = slot ? slotEntry.get(slot) : null;
    if (!entry || drag.active) {
      if (state.hovered) hideTooltip();
      return;
    }
    if (entry === state.hovered) return;
    showTooltip(entry);
    moveTooltip(e.clientX, e.clientY);
  });

  document.addEventListener("mousemove", function (e) {
    state.mouse.x = e.clientX;
    state.mouse.y = e.clientY;
    state.mouse.over = true;
    if (state.hovered) moveTooltip(e.clientX, e.clientY);
  }, { passive: true });

  document.documentElement.addEventListener("mouseleave", function () {
    state.mouse.over = false;
    hideTooltip();
  });
  document.addEventListener("scroll", hideTooltip, { passive: true, capture: true });

  // ---------- link addon panel ----------

  var link = {
    open: false,
    autoOpened: false,
    data: null,
    timer: 0,
    urlsShown: null
  };

  function maybeAutoOpenLink() {
    if (link.autoOpened || link.open || !dash.botsLoaded || !state.polledOnce || dash.bots.length || auth.blocked || !can("link")) return;
    var anyIndex = state.stashes.some(function (s) { return s.indexedAt != null; });
    if (anyIndex) return;
    link.autoOpened = true;
    openLink();
  }

  function openLink() {
    link.open = true;
    el.linkModal.hidden = false;
    hideTooltip();
    refreshLink();
    window.clearInterval(link.timer);
    link.timer = window.setInterval(refreshLink, LINK_POLL_MS);
    el.linkClose.focus();
  }

  function closeLink() {
    link.open = false;
    el.linkModal.hidden = true;
    window.clearInterval(link.timer);
    link.timer = 0;
    el.linkOpen.focus();
  }

  function refreshLink() {
    if (auth.blocked) return;
    api("/api/v1/link").then(function (d) {
      link.data = d;
      renderLink();
    }, function () {
      setDot(el.linkDot, false);
      el.linkStatusText.textContent = "Server unreachable";
    });
  }

  function urlList() {
    var list = [window.location.origin];
    var extra = link.data && Array.isArray(link.data.urls) ? link.data.urls : [];
    extra.forEach(function (u) {
      if (typeof u === "string" && list.indexOf(u) < 0) list.push(u);
    });
    return list;
  }

  function renderLink() {
    var d = link.data;
    if (!d) return;

    var urls = urlList();
    var joined = urls.join("\n");
    if (joined !== link.urlsShown) {
      link.urlsShown = joined;
      var frag = document.createDocumentFragment();
      urls.forEach(function (u, i) {
        var row = document.createElement("div");
        row.className = "copy-row";
        var code = document.createElement("code");
        code.className = "copy-value";
        code.textContent = u;
        hide(code);
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "mc-button streamer-block";
        btn.textContent = "Copy";
        btn.disabled = streamerOn();
        btn.addEventListener("click", function () {
          if (streamerOn()) return;
          copyText(u, code);
        });
        row.appendChild(code);
        if (i === 0) {
          var hint = document.createElement("span");
          hint.className = "note";
          hint.textContent = "this page";
          row.appendChild(hint);
        }
        row.appendChild(btn);
        frag.appendChild(row);
      });
      el.linkUrls.replaceChildren(frag);
    }

    renderKey();

    var list = Array.isArray(d.bots) ? d.bots.filter(function (b) { return b && typeof b === "object"; }) : [];
    var frag2 = document.createDocumentFragment();
    list.forEach(function (b) {
      var row = document.createElement("div");
      row.className = "link-status";
      var dot = document.createElement("span");
      setDot(dot, serverNow() - num(b.lastSeen, 0) < 45000);
      row.appendChild(dot);
      row.appendChild(document.createTextNode((str(b.player) || "addon") + ": " + str(b.stash) + " @ "));
      row.appendChild(hiddenSpan(str(b.server)));
      row.appendChild(document.createTextNode(", last seen " + ago(num(b.lastSeen, null))));
      frag2.appendChild(row);
    });
    el.linkBots.replaceChildren(frag2);
    el.linkStatus.hidden = list.length > 0;
    setDot(el.linkDot, false);
    el.linkStatusText.textContent = "Waiting for addon...";
  }

  function renderKey() {
    var canRotate = !!(link.data && link.data.canRotateKey);
    el.linkRegen.hidden = !canRotate;
    el.linkKey.hidden = !(link.data && link.data.canProvisionAgents);
  }

  function note(text, warn) {
    el.linkCopyNote.textContent = text;
    el.linkCopyNote.classList.toggle("warn", !!warn);
  }

  function selectNode(node) {
    var range = document.createRange();
    range.selectNodeContents(node);
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  }

  /** Clipboard API where allowed (secure contexts only); otherwise select the text for Ctrl+C. */
  function copyText(text, node) {
    function fallback() {
      selectNode(node);
      var ok = false;
      try {
        ok = document.execCommand("copy");
      } catch (e) {
        ok = false;
      }
      note(ok ? "Copied." : "Selected. Press Ctrl+C to copy.", !ok);
    }
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(function () { note("Copied."); }, fallback);
    } else {
      fallback();
    }
  }

  el.linkOpen.addEventListener("click", openLink);
  el.linkClose.addEventListener("click", closeLink);
  el.linkModal.addEventListener("click", function (e) {
    if (e.target === el.linkModal) closeLink();
  });
  document.addEventListener("keydown", function (e) {
    if (e.key !== "Escape") return;
    if (currentWindow) closeWindow();
    else if (link.open) closeLink();
  });
  el.linkRegen.addEventListener("click", function () {
    var sure = window.confirm("Rotate the agent key?\n\nRetrieve the new key from the Manager host and update your agents.");
    if (!sure) return;
    el.linkRegen.disabled = true;
    withSudo(function () { return api("/api/v1/link/regenerate", { method: "POST", body: {} }); }).then(function () {
      if (link.data) { link.data.linked = null; link.data.bots = []; }
      renderLink();
      el.linkKeyNote.textContent = "Key rotated. Retrieve it from the Manager host and update the agent.";
      el.linkKeyNote.hidden = false;
      note("");
    }, function (e) {
      if (e && e.body && e.body.error === "cancelled") return;
      note("Could not regenerate the key" + (e && e.status ? " (HTTP " + e.status + ")" : "") + ".", true);
    }).then(function () {
      el.linkRegen.disabled = false;
    });
  });

  // ---------- creative tabs ----------

  var CREATIVE_TABS = [
    { id: "building_blocks", name: "Building Blocks", icon: "minecraft:bricks" },
    { id: "colored_blocks", name: "Colored Blocks", icon: "minecraft:cyan_wool" },
    { id: "natural_blocks", name: "Natural Blocks", icon: "minecraft:grass_block" },
    { id: "functional_blocks", name: "Functional Blocks", icon: "minecraft:oak_sign" },
    { id: "redstone_blocks", name: "Redstone Blocks", icon: "minecraft:redstone" },
    { id: "tools_and_utilities", name: "Tools & Utilities", icon: "minecraft:diamond_pickaxe" },
    { id: "combat", name: "Combat", icon: "minecraft:netherite_sword" },
    { id: "food_and_drinks", name: "Food & Drinks", icon: "minecraft:golden_apple" },
    { id: "ingredients", name: "Ingredients", icon: "minecraft:iron_ingot" },
    { id: "spawn_eggs", name: "Spawn Eggs", icon: "minecraft:pig_spawn_egg" },
    { id: "op_blocks", name: "Operator Utilities", icon: "minecraft:command_block" },
    { id: "other", name: "Other", icon: "minecraft:chest" }
  ];
  var TAB_ALL = { id: "all", name: "Search", icon: "minecraft:compass" };
  var TAB_SHULKERS = { id: "shulkers", name: "Shulkers", icon: "minecraft:shulker_box" };
  var TAB_ORDERS = { id: "orders", name: "Orders", icon: "minecraft:writable_book" };
  var TAB_INDEX = {};
  CREATIVE_TABS.forEach(function (t, i) { TAB_INDEX[t.id] = i; });

  var tabsShown = "";

  function isCreativeTab(id) {
    return TAB_INDEX.hasOwnProperty(id);
  }

  function isShulkerBoxId(id) {
    return /^minecraft:([a-z_]+_)?shulker_box$/.test(id);
  }

  function tabInfo(id) {
    if (id === "all") return TAB_ALL;
    if (id === "shulkers") return TAB_SHULKERS;
    if (id === "orders") return TAB_ORDERS;
    return CREATIVE_TABS[TAB_INDEX[id]] || TAB_ALL;
  }

  /** The tabs to show: Search, the creative tabs that have items, Shulkers (if any are filled), then Orders. */
  function visibleTabs() {
    var present = {};
    for (var i = 0; i < state.items.length; i++) present[state.items[i].item.tab] = true;
    var out = [TAB_ALL];
    CREATIVE_TABS.forEach(function (t) {
      if (present[t.id]) out.push(t);
    });
    if (state.shulkers.length) out.push(TAB_SHULKERS);
    out.push(TAB_ORDERS);
    return out;
  }

  /** The entries a tab shows (before search). */
  function tabEntries(id) {
    if (id === "shulkers") return state.shulkers;
    if (id === "all" || id === "orders") return state.items;
    return state.items.filter(function (e) { return e.item.tab === id; });
  }

  /** A small icon <img> for tabs, buttons and cart lines, with the same 503 retry as the grid. */
  function iconImg(id, size) {
    var img = document.createElement("img");
    img.alt = "";
    img.width = size || 32;
    img.height = size || 32;
    img.draggable = false;
    img.className = "icon";
    var url = iconUrl(id, state.mc);
    var tries = 0;
    img.addEventListener("error", function () {
      if (img.getAttribute("src") !== PLACEHOLDER) img.src = PLACEHOLDER;
      tries++;
      if (tries > ICON_RETRIES) return;
      var n = tries;
      window.setTimeout(function () {
        if (img.isConnected) img.src = url + "&retry=" + n;
      }, retryDelay(n));
    });
    img.src = url;
    return img;
  }

  function renderTabs() {
    var tabs = visibleTabs();
    var ids = tabs.map(function (t) { return t.id; });
    if (ids.indexOf(state.tab) < 0) state.tab = "all";
    var signature = ids.join(",") + "|" + state.mc;
    if (signature !== tabsShown) {
      tabsShown = signature;
      var left = document.createDocumentFragment();
      var right = document.createDocumentFragment();
      tabs.forEach(function (t) {
        var b = document.createElement("button");
        b.type = "button";
        b.className = "tab" + (t.id === "orders" ? " tab-orders" : "");
        b.setAttribute("role", "tab");
        b.dataset.tab = t.id;
        b.title = t.name;
        b.setAttribute("aria-label", t.name);
        b.appendChild(iconImg(t.icon, 32));
        b.addEventListener("click", function () { selectTab(t.id); });
        (t.id === "orders" ? right : left).appendChild(b);
      });
      el.tabs.replaceChildren(left);
      el.tabsRight.replaceChildren(right);
    }
    var buttons = document.querySelectorAll(".tab");
    for (var i = 0; i < buttons.length; i++) {
      var on = buttons[i].dataset.tab === state.tab;
      buttons[i].classList.toggle("selected", on);
      buttons[i].setAttribute("aria-selected", on ? "true" : "false");
    }
    el.panelLabel.textContent = state.tab === "all" ? "Search Items" : tabInfo(state.tab).name;
  }

  function selectTab(id) {
    if (id === "orders" || dash.page === "orders") {
      dash.page = id === "orders" ? "orders" : "stash";
      var navs = el.dashNav.querySelectorAll(".nav-btn");
      for (var n = 0; n < navs.length; n++) navs[n].classList.toggle("selected", navs[n].dataset.page === dash.page);
    }
    if (id === state.tab) return;
    state.tab = id;
    hideTooltip();
    var orders = id === "orders";
    el.viewItems.hidden = orders;
    el.viewOrders.hidden = !orders;
    if (!orders) {
      el.sort.value = isCreativeTab(id) ? state.sortTab : state.sortAll;
      el.gridWrap.scrollTop = 0;
      renderNow();
    } else {
      renderPicker();
      renderCart();
      scheduleOrdersPoll(0);
    }
    renderTabs();
  }

  // Left/right arrows switch tabs when no text field has focus and no window is open.
  document.addEventListener("keydown", function (e) {
    if ((e.key !== "ArrowLeft" && e.key !== "ArrowRight") || e.altKey || e.ctrlKey || e.metaKey) return;
    var a = document.activeElement;
    if (a && (a.tagName === "INPUT" || a.tagName === "TEXTAREA" || a.tagName === "SELECT")) return;
    if (currentWindow || link.open || !state.items.length || (dash.page !== "stash" && dash.page !== "orders")) return;
    var ids = visibleTabs().map(function (t) { return t.id; });
    var i = ids.indexOf(state.tab);
    var next = ids[(i + (e.key === "ArrowRight" ? 1 : ids.length - 1)) % ids.length];
    e.preventDefault();
    selectTab(next);
  });

  // ---------- shulker groups ----------

  function itemByKey(key) {
    var e = state.byKey.get(key);
    return e ? e.item : null;
  }

  function itemName(key) {
    var it = itemByKey(key);
    return it ? it.name : key;
  }

  /** "Diamond ×1,728, Golden Apple ×64, …" and "N stacks" for a shulker group's tooltip. */
  function shulkerSummary(contents) {
    var totals = new Map();
    contents.forEach(function (c) { totals.set(c.key, (totals.get(c.key) || 0) + c.count); });
    var top = Array.from(totals.entries()).sort(function (a, b) { return b[1] - a[1]; });
    var lines = top.slice(0, 4).map(function (t) { return itemName(t[0]) + " ×" + grouped(t[1]); });
    if (top.length > 4) lines.push("…and " + (top.length - 4) + " more");
    lines.push(plural(String(contents.length), "stack"));
    return lines;
  }

  function normalizeShulker(g) {
    var contents = Array.isArray(g.contents) ? g.contents.filter(function (c) {
      return c && typeof c === "object" && typeof c.key === "string" && num(c.slot, -1) >= 0 && num(c.slot, -1) <= 26;
    }).map(function (c) {
      return { slot: Math.floor(c.slot), key: c.key, count: Math.max(0, Math.floor(num(c.count, 0))) };
    }) : [];
    var id = str(g.id);
    var base = itemByKey(str(g.itemKey) || id);
    return {
      key: str(g.key),
      id: id,
      name: str(g.name) || (base ? base.name : id),
      englishName: base ? base.englishName : str(g.name) || id,
      count: Math.max(0, Math.floor(num(g.count, 0))),
      maxStack: 1,
      details: [],
      enchanted: false,
      tab: "shulkers",
      tabOrder: 0,
      isShulker: true,
      itemKey: str(g.itemKey),
      contents: contents
    };
  }

  /** Builds the Shulkers tab entries, reusing slots by group key like updateItems does for items. */
  function updateShulkers(index, mc) {
    var raw = Array.isArray(index.shulkers) ? index.shulkers : [];
    var prev = state.shulkerByKey || new Map();
    var next = new Map();
    var out = [];
    raw.forEach(function (g) {
      if (!g || typeof g !== "object" || !str(g.key) || next.has(str(g.key))) return;
      var item = normalizeShulker(g);
      item.details = shulkerSummary(item.contents);
      var entry = prev.get(item.key);
      if (!entry || entry.url !== iconUrl(item.id, mc)) {
        entry = createEntry(item, mc);
      } else {
        var old = entry.item;
        entry.item = item;
        if (old.count !== item.count) setCount(entry);
      }
      setFace(entry, shulkerFace(item.contents)); // after the items: names and ids come from them
      entry.hay = haystack(item);
      next.set(item.key, entry);
      out.push(entry);
    });
    state.shulkerByKey = next;
    state.shulkers = out;
    state.hasShulkerData = Array.isArray(index.shulkers);
    updateEmptyCounts();
  }

  /**
   * A shulker-box item counts every box of its kind, filled or not. Where filled groups exist, its slot in the
   * creative tabs and Search shows only the empty ones (plain icon), and the filled groups are listed right
   * after it with their contents icons (see withFilledGroups). Groups match their base item like
   * filledGroupsFor does: by itemKey, or by id for older indexes.
   */
  function updateEmptyCounts() {
    var filled = new Map();
    state.items.forEach(function (e) {
      if (!isShulkerBoxId(e.item.id)) return;
      var groups = filledGroupsFor(e.item);
      if (groups.length) filled.set(e, groups);
    });
    state.items.forEach(function (e) {
      var groups = filled.get(e) || null;
      var before = e.emptyCount;
      e.filledGroups = groups;
      e.emptyCount = groups ? Math.max(0, e.item.count - groups.reduce(function (n, g) { return n + g.item.count; }, 0)) : undefined;
      if (before !== e.emptyCount) setCount(e);
    });
  }

  /**
   * The creative tabs and Search: each shulker-box item with filled groups becomes its empty boxes (if any are
   * left, and it matches the search) followed by its filled groups that match, most first. Groups are found by
   * their own name and contents too, so searching "diamond" shows the shulkers full of diamonds.
   */
  function withFilledGroups(sorted, query) {
    var out = [];
    sorted.forEach(function (e) {
      if (!e.filledGroups) {
        out.push(e);
        return;
      }
      if (e.emptyCount > 0 && matches(e, query)) out.push(e);
      e.filledGroups.filter(function (g) { return matches(g, query); })
        .sort(sorters.count)
        .forEach(function (g) { out.push(g); });
    });
    return out;
  }

  function filledGroupsFor(item) {
    return state.shulkers.filter(function (e) {
      var g = e.item;
      return g.itemKey ? g.itemKey === item.key : g.id === item.id;
    });
  }

  // ---------- windows (shulker preview, chooser) ----------

  var currentWindow = null;

  function closeWindow() {
    if (!currentWindow) return;
    currentWindow.remove();
    currentWindow = null;
    hideTooltip();
  }

  /** A vanilla-GUI-style window on a backdrop, with a clickable X. Escape and the backdrop also close it. */
  function openWindow(title, body, extraClass) {
    closeWindow();
    var backdrop = document.createElement("div");
    backdrop.className = "modal-backdrop";
    var win = document.createElement("div");
    win.className = "panel window" + (extraClass ? " " + extraClass : "");
    win.setAttribute("role", "dialog");
    win.setAttribute("aria-modal", "true");
    var head = document.createElement("div");
    head.className = "window-head";
    var h = document.createElement("div");
    h.className = "window-title";
    h.textContent = title;
    var x = document.createElement("button");
    x.type = "button";
    x.className = "mc-button small";
    x.textContent = "X";
    x.setAttribute("aria-label", "Close");
    x.addEventListener("click", closeWindow);
    head.appendChild(h);
    head.appendChild(x);
    win.appendChild(head);
    win.appendChild(body);
    backdrop.appendChild(win);
    backdrop.addEventListener("mousedown", function (e) {
      if (e.target === backdrop) closeWindow();
    });
    document.body.appendChild(backdrop);
    currentWindow = backdrop;
    x.focus();
    return win;
  }

  function contentEntry(c) {
    var base = itemByKey(c.key);
    var item = base ? Object.assign({}, base, { count: c.count }) : {
      key: c.key, id: c.key.split("#")[0], name: c.key, englishName: c.key, count: c.count,
      maxStack: 64, details: [], enchanted: false, tab: "other", tabOrder: 1000000
    };
    return createEntry(item, state.mc);
  }

  /** The shulker-box preview: the 9x3 grid with contents at their slots, plus "Add to order". */
  function openShulkerPreview(group) {
    var body = document.createElement("div");
    var grid = document.createElement("div");
    grid.className = "shulker-grid";
    var bySlot = {};
    group.contents.forEach(function (c) { bySlot[c.slot] = c; });
    for (var i = 0; i < 27; i++) {
      if (bySlot[i]) {
        grid.appendChild(contentEntry(bySlot[i]).slot);
      } else {
        var empty = document.createElement("div");
        empty.className = "slot empty-slot";
        grid.appendChild(empty);
      }
    }
    body.appendChild(grid);

    var info = document.createElement("div");
    info.className = "window-info";
    info.textContent = group.count === 1 ? "1 shulker like this" : grouped(group.count) + " identical shulkers like this";
    body.appendChild(info);

    var avail = availableLeft("shulker", group.key);
    var row = document.createElement("div");
    row.className = "window-actions";
    var input = document.createElement("input");
    input.type = "number";
    input.className = "mc-input qty-input";
    input.min = "1";
    input.max = String(Math.max(1, avail));
    input.value = "1";
    var add = document.createElement("button");
    add.type = "button";
    add.className = "mc-button";
    add.textContent = "Add to order";
    var msg = document.createElement("span");
    msg.className = "note";
    if (!can("order")) {
      add.hidden = true;
      input.hidden = true;
    } else if (avail < 1) {
      add.disabled = true;
      input.disabled = true;
      msg.textContent = "None left to order (reserved or already in the cart).";
    } else {
      msg.textContent = grouped(avail) + " available";
    }
    add.addEventListener("click", function () {
      var n = clampInt(input.value, 1, availableLeft("shulker", group.key));
      if (n < 1) return;
      if (!addToCart("shulker", group.key, n)) return;
      toast("Added " + n + " × " + group.name + " (packed) to the cart");
      closeWindow();
    });
    row.appendChild(input);
    row.appendChild(add);
    row.appendChild(msg);
    body.appendChild(row);
    openWindow(group.name, body, "shulker-window");
  }

  /** Right-click on an aggregated shulker-box item: choose one of its filled groups. */
  function openShulkerChooser(item) {
    var groups = filledGroupsFor(item);
    var body = document.createElement("div");
    if (!groups.length) {
      var p = document.createElement("p");
      p.className = "window-info";
      p.textContent = state.hasShulkerData
        ? "All empty. None of these shulkers hold items."
        : "This index has no shulker contents. Update the WeSort addon to see what is inside.";
      body.appendChild(p);
      openWindow(item.name, body, "chooser-window");
      return;
    }
    var hint = document.createElement("div");
    hint.className = "window-info";
    hint.textContent = "Filled ones (" + grouped(groups.reduce(function (n, e) { return n + e.item.count; }, 0)) +
      " of " + grouped(item.count) + "). Click one to look inside.";
    body.appendChild(hint);
    var grid = document.createElement("div");
    grid.className = "item-grid chooser-grid";
    groups.forEach(function (g) {
      var e = createEntry(g.item, state.mc);
      e.onActivate = function () { openShulkerPreview(g.item); };
      grid.appendChild(e.slot);
    });
    body.appendChild(grid);
    openWindow(item.name, body, "chooser-window");
  }

  /** What right-click on a slot does (touch: what a tap on a main-grid slot does). */
  function slotContextAction(entry) {
    if (entry.onContext) entry.onContext();
    else if (entry.onActivate) entry.onActivate();
    else if (entry.item.isShulker) openShulkerPreview(entry.item);
    else if (isShulkerBoxId(entry.item.id)) openShulkerChooser(entry.item);
  }

  // Touch equivalents for the main item grid (mouse behaviour is unchanged):
  // tap = right-click (look inside a shulker), long-press = shift-click (add a stack to the cart).
  var LONG_PRESS_MS = 500;
  var LONG_PRESS_SLOP = 10;
  var touchPress = { pointer: "mouse", timer: 0, x: 0, y: 0, firedUntil: 0 };

  document.addEventListener("pointerdown", function (e) { touchPress.pointer = e.pointerType || "mouse"; }, true);

  function cancelLongPress() {
    window.clearTimeout(touchPress.timer);
    touchPress.timer = 0;
  }

  el.grid.addEventListener("pointerdown", function (e) {
    cancelLongPress();
    if (e.pointerType === "mouse" || !e.isPrimary) return;
    var slot = e.target.closest ? e.target.closest(".slot") : null;
    var entry = slot ? slotEntry.get(slot) : null;
    if (!entry || e.target.closest(".slot-add")) return;
    touchPress.x = e.clientX;
    touchPress.y = e.clientY;
    touchPress.timer = window.setTimeout(function () {
      touchPress.timer = 0;
      touchPress.firedUntil = Date.now() + 1000; // swallow the click (and menu) that follow this press
      hideTooltip();
      quickAdd(entry.item);
    }, LONG_PRESS_MS);
  });
  /**
   * Main-grid taps, registered on each slot (createEntry). A listener on the slot itself makes it a tap target
   * for the browser's touch adjustment, which otherwise snaps a tap on the slot onto its nearby "+" button.
   */
  function gridSlotClick(e) {
    var slot = e.currentTarget;
    var entry = el.grid.contains(slot) ? slotEntry.get(slot) : null;
    if (!entry) return;
    if (e.target.closest(".slot-add")) {
      e.preventDefault();
      quickAdd(entry.item);
    } else if (touchPress.firedUntil > Date.now()) {
      touchPress.firedUntil = 0; // the click that ends a long-press
      e.preventDefault();
      hideTooltip(); // the browser's compatibility mouseover showed it again
    } else if (touchPress.pointer !== "mouse" && !e.shiftKey && !entry.onActivate) {
      e.preventDefault();
      slotContextAction(entry);
    }
  }
  el.grid.addEventListener("pointermove", function (e) {
    if (touchPress.timer && Math.hypot(e.clientX - touchPress.x, e.clientY - touchPress.y) > LONG_PRESS_SLOP) cancelLongPress();
  });
  ["pointerup", "pointercancel", "pointerleave"].forEach(function (t) { el.grid.addEventListener(t, cancelLongPress); });
  el.gridWrap.addEventListener("scroll", cancelLongPress, { passive: true });
  window.addEventListener("scroll", cancelLongPress, { passive: true });

  // Right-click opens shulker windows; the browser menu is suppressed only on slots.
  document.addEventListener("contextmenu", function (e) {
    var slot = e.target.closest ? e.target.closest(".slot") : null;
    var entry = slot ? slotEntry.get(slot) : null;
    if (!entry) return;
    e.preventDefault();
    // A touch long-press also raises a context menu: on the main grid the press itself is the action.
    if (touchPress.pointer !== "mouse" && el.grid.contains(slot)) return;
    slotContextAction(entry);
  });

  // Left click: windows' clickable slots, the order picker, and shift-click to add a stack to the cart.
  document.addEventListener("click", function (e) {
    var slot = e.target.closest ? e.target.closest(".slot") : null;
    var entry = slot ? slotEntry.get(slot) : null;
    if (!entry || e.defaultPrevented) return; // handled by the item grid's touch handler
    if (entry.onActivate) {
      entry.onActivate();
      return;
    }
    if (e.shiftKey && el.grid.contains(slot)) {
      e.preventDefault();
      quickAdd(entry.item);
    }
  });

  function quickAdd(item) {
    if (!can("order")) {
      toast("Your account can't place orders.");
      return;
    }
    var type = item.isShulker ? "shulker" : "item";
    var left = availableLeft(type, item.key);
    var n = Math.min(item.isShulker ? 1 : item.maxStack, left);
    if (n < 1) {
      toast("None left to order: " + item.name);
      return;
    }
    if (!addToCart(type, item.key, n)) return;
    if (!plan.toasted) toast("Added " + n + " × " + displayName(type, item) + " to the cart");
  }

  // ---------- toast ----------

  var toastTimer = 0;

  function toast(text) {
    el.toast.textContent = text;
    el.toast.hidden = false;
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () { el.toast.hidden = true; }, 2500);
  }

  // ---------- orders: availability and cart ----------

  var orders = {
    list: [],
    available: null, // { items: {key: n}, shulkers: {key: n} } from the server
    timer: 0,
    loading: false,
    token: 0,
    expanded: new Set(),
    retryErrors: new Map() // order id -> why the last retry was refused
  };
  var cart = { lines: [], badLine: -1 };
  var picker = { level: "types", type: null, entry: null, search: "", slots: [], qtyHost: null };

  function clampInt(v, lo, hi) {
    var n = Math.floor(Number(v));
    if (!isFinite(n)) n = lo;
    return Math.max(lo, Math.min(hi, n));
  }

  /** What the server says can still be ordered (index count minus open orders), before this cart. */
  function availableTotal(type, key) {
    var a = orders.available;
    var map = a ? (type === "shulker" ? a.shulkers : a.items) : null;
    if (map && typeof map[key] === "number") return map[key];
    if (type === "shulker") {
      var g = state.shulkerByKey && state.shulkerByKey.get(key);
      return g ? g.item.count : 0;
    }
    var it = itemByKey(key);
    if (!it) return 0;
    if (isShulkerBoxId(it.id)) {
      var filled = filledGroupsFor(it).reduce(function (n, e) { return n + e.item.count; }, 0);
      return Math.max(0, it.count - filled);
    }
    return it.count;
  }

  function cartCount(type, key) {
    var n = 0;
    cart.lines.forEach(function (l) { if (l.type === type && l.key === key) n += l.count; });
    return n;
  }

  /** Available for a new addition: server availability minus what this cart already holds. */
  function availableLeft(type, key) {
    return Math.max(0, availableTotal(type, key) - cartCount(type, key));
  }

  function lineItem(type, key) {
    if (type === "shulker") {
      var g = state.shulkerByKey && state.shulkerByKey.get(key);
      return g ? g.item : null;
    }
    return itemByKey(key);
  }

  function displayName(type, item) {
    if (type === "shulker") return item.name + " (packed)";
    if (isShulkerBoxId(item.id)) return "Empty " + item.name;
    return item.name;
  }

  function cartStorageKey() {
    return state.selected ? "wesort.cart." + state.selected : null;
  }

  function loadCart() {
    cart.lines = [];
    cart.badLine = -1;
    var k = cartStorageKey();
    if (!k) return;
    try {
      var raw = JSON.parse(window.localStorage.getItem(k) || "[]");
      if (Array.isArray(raw)) {
        raw.forEach(function (l) {
          if (l && (l.type === "item" || l.type === "shulker") && typeof l.key === "string" && num(l.count, 0) >= 1) {
            cart.lines.push({ type: l.type, key: l.key, count: Math.floor(l.count) });
          }
        });
      }
    } catch (e) {
      cart.lines = [];
    }
  }

  function saveCart() {
    var k = cartStorageKey();
    if (!k) return;
    try {
      window.localStorage.setItem(k, JSON.stringify(cart.lines));
    } catch (e) {
      // Storage full or disabled: the cart just isn't remembered.
    }
  }

  // ---------- orders: packing (mirrors the addon's Order.pack) ----------
  // Item lines become slot loads: floor(count / maxStack) full stacks plus one remainder stack. Loads are grouped
  // by item key (keys in ascending UTF-16 order, like Java's TreeMap), the groups are stable-sorted by number of
  // loads (most first), loads inside a group by count (largest first), and the result fills 27-slot shulkers in
  // sequence. Shulker lines and empty-shulker lines are one unit each and come first, in that order.

  var MAX_SHULKERS = 54;
  var SHULKER_SLOTS = 27;

  /** True when an item line for this key means empty shulkers. */
  function isEmptyShulkerLine(l) {
    if (l.type !== "item") return false;
    var it = itemByKey(l.key);
    return !!it && isShulkerBoxId(it.id);
  }

  function lineMaxStack(key) {
    var it = itemByKey(key);
    return it ? it.maxStack : 64;
  }

  /** Shulkers the lines need, without building the plan (cheap, safe for huge counts). */
  function shulkersNeeded(lines) {
    var units = 0;
    var slots = 0;
    lines.forEach(function (l) {
      if (l.type === "shulker" || isEmptyShulkerLine(l)) units += l.count;
      else slots += Math.ceil(l.count / lineMaxStack(l.key));
    });
    return units + Math.ceil(slots / SHULKER_SLOTS);
  }

  /** The planned delivery: units in chest order. null when it needs more than 54 shulkers. */
  function planCart(lines) {
    if (shulkersNeeded(lines) > MAX_SHULKERS) return null;
    var shulkerUnits = [];
    var emptyUnits = [];
    var byKey = {};
    var keys = [];
    lines.forEach(function (l) {
      var i;
      if (l.type === "shulker") {
        for (i = 0; i < l.count; i++) shulkerUnits.push({ kind: "shulker", key: l.key });
      } else if (isEmptyShulkerLine(l)) {
        for (i = 0; i < l.count; i++) emptyUnits.push({ kind: "empty", key: l.key });
      } else {
        var max = Math.max(1, lineMaxStack(l.key));
        if (!Object.prototype.hasOwnProperty.call(byKey, l.key)) {
          byKey[l.key] = [];
          keys.push(l.key);
        }
        for (var left = l.count; left > 0; left -= max) byKey[l.key].push({ key: l.key, count: Math.min(max, left) });
      }
    });
    keys.sort(function (a, b) { return a < b ? -1 : a > b ? 1 : 0; });
    var groups = keys.map(function (k) { return byKey[k]; });
    groups.sort(function (a, b) { return b.length - a.length; }); // Array.prototype.sort is stable
    var bins = [];
    groups.forEach(function (g) {
      g.sort(function (a, b) { return b.count - a.count; });
      g.forEach(function (lot) {
        if (!bins.length || bins[bins.length - 1].lots.length >= SHULKER_SLOTS) bins.push({ kind: "bin", lots: [] });
        bins[bins.length - 1].lots.push(lot);
      });
    });
    return shulkerUnits.concat(emptyUnits, bins);
  }

  function capMessage(n) {
    var now = Math.min(shulkersNeeded(cart.lines), MAX_SHULKERS);
    return "That needs " + n + " shulkers. One order can fill at most " + MAX_SHULKERS + " (one double chest); the cart plans " + now + ".";
  }

  // ---------- orders: cart changes ----------

  /**
   * Adds n of a line, unless the cart would then need more than 54 shulkers (then nothing changes and a
   * message is shown). Returns whether it was added.
   */
  function addToCart(type, key, n) {
    var next = cart.lines.map(function (l) { return { type: l.type, key: l.key, count: l.count }; });
    var found = null;
    next.forEach(function (l) { if (l.type === type && l.key === key) found = l; });
    if (found) found.count += n;
    else next.push({ type: type, key: key, count: n });
    var need = shulkersNeeded(next);
    if (need > MAX_SHULKERS) {
      refuse(capMessage(need));
      return false;
    }
    cart.lines = next;
    cart.badLine = -1;
    plan.selected = null; // follow the last shulker, so the user watches it fill
    plan.watchFull = true;
    saveCart();
    renderCart();
    updatePickerCounts();
    return true;
  }

  /** Removes up to n of a line (the whole line when it reaches 0). */
  function removeFromCart(type, key, n) {
    for (var i = 0; i < cart.lines.length; i++) {
      var l = cart.lines[i];
      if (l.type !== type || l.key !== key) continue;
      l.count -= n;
      if (l.count < 1) cart.lines.splice(i, 1);
      break;
    }
    cart.badLine = -1;
    saveCart();
    renderCart();
    updatePickerCounts();
  }

  function refuse(text) {
    toast(text);
    el.cartStatus.textContent = text;
    el.cartStatus.classList.add("warn");
  }

  function clearStatus() {
    el.cartStatus.textContent = "";
    el.cartStatus.classList.remove("warn");
  }

  /** Double-click or drop: one stack, or with Shift a shulker's worth (27 stacks), clamped to what is left. */
  function addStacks(type, item, shulkerWorth) {
    var left = availableLeft(type, item.key);
    var unit = type === "shulker" ? 1 : item.maxStack;
    var n = Math.min(shulkerWorth && type !== "shulker" ? unit * SHULKER_SLOTS : unit, left);
    if (n < 1) {
      toast("None left to order: " + displayName(type, item));
      return;
    }
    if (addToCart(type, item.key, n)) {
      clearStatus();
      if (!plan.toasted) toast("Added " + grouped(n) + " × " + displayName(type, item));
    }
  }

  function onStashChanged() {
    dlv.token++;
    dlv.key = null;
    dlv.hash = null;
    dlv.data = null;
    deliveryMaybeLoad();
    loadCart();
    orders.list = [];
    orders.available = null;
    orders.token++;
    picker.level = "types";
    picker.type = null;
    picker.entry = null;
    plan.selected = null;
    plan.fullCount = -1;
    resetNames();
    el.cartStatus.textContent = "";
    if (state.tab === "orders") {
      renderPicker();
      renderCart();
    }
    renderBotStatus();
    renderOrderList();
    scheduleOrdersPoll(0);
  }

  /** Re-renders the parts of the Orders tab that depend on the index (after every index refresh). */
  function refreshOrdersView() {
    if (state.tab !== "orders") return;
    if (!el.pickerBody.contains(document.activeElement) && !drag.pe) renderPicker();
    else updatePickerCounts();
    renderCart();
  }

  // ---------- orders: bot status ----------

  function renderBotStatus() {
    var s = currentStash();
    if (!s) {
      el.deliveryStatus.textContent = "";
      el.deliveryStatus.className = "bot-line";
      el.orderBlocker.hidden = true;
      renderCraftInfo();
      return;
    }
    var d = s.delivery;
    if (d && typeof d === "object" && typeof d.x === "number" && typeof d.y === "number" && typeof d.z === "number") {
      el.deliveryStatus.replaceChildren(document.createTextNode("Delivery container: "),
        hiddenSpan(d.x + ", " + d.y + ", " + d.z + (str(d.dimension) ? " (" + str(d.dimension) + ")" : "")));
      el.deliveryStatus.className = "bot-line ok";
    } else if (s.deliveryConfigured) {
      el.deliveryStatus.textContent = "Delivery container configured.";
      el.deliveryStatus.className = "bot-line ok";
    } else {
      el.deliveryStatus.textContent = "No delivery container set: in Meteor open stash-manager → Delivery → " +
        "select-delivery, then right-click a chest.";
      el.deliveryStatus.className = "bot-line warn";
    }
    var blocker = str(s.orderBlocker);
    el.orderBlocker.hidden = !blocker;
    el.orderBlocker.textContent = blocker ? "Bot can't take orders right now: " + blocker : "";
    renderNamesWarn();
  }

  // ---------- orders: shulker names ----------
  // No names, one name for every shulker, or a name per planned shulker (in delivery order, as planCart lays them
  // out). The rows are updated in place so typing isn't interrupted when the cart or the index refreshes; names
  // typed for a shulker are kept by its index while the cart changes.

  var names = { mode: "none", all: "", each: [], rows: [] };

  function resetNames() {
    names.all = "";
    names.each = [];
    el.namesAll.value = "";
    names.rows.forEach(function (r) { r.input.value = ""; });
    setNamesMode("none");
  }

  function setNamesMode(mode) {
    names.mode = mode;
    Array.prototype.forEach.call(el.namesSeg.querySelectorAll(".seg-btn"), function (b) {
      b.setAttribute("aria-pressed", b.getAttribute("data-mode") === mode ? "true" : "false");
    });
    el.namesAll.hidden = mode !== "all";
    el.namesEach.hidden = mode !== "each";
    renderNames();
  }

  el.namesSeg.addEventListener("click", function (ev) {
    var b = ev.target && ev.target.closest ? ev.target.closest(".seg-btn") : null;
    if (!b) return;
    setNamesMode(b.getAttribute("data-mode"));
    if (names.mode === "all") el.namesAll.focus();
  });

  el.namesAll.addEventListener("input", function () {
    names.all = el.namesAll.value;
    renderNamesWarn();
  });

  /** What a planned shulker will hold: "Diamond ×128, Golden Apple ×64", "Packed shulker: …", "Empty red shulker". */
  function unitSummary(u) {
    if (u.kind === "shulker") {
      var g = state.shulkerByKey && state.shulkerByKey.get(u.key);
      return "Packed shulker: " + (g ? g.item.name : u.key);
    }
    if (u.kind === "empty") {
      var it = itemByKey(u.key);
      var colour = (it ? it.id : "").replace(/^minecraft:/, "").replace(/_?shulker_box$/, "").replace(/_/g, " ");
      return "Empty " + (colour ? colour + " " : "") + "shulker";
    }
    var totals = new Map();
    u.lots.forEach(function (lot) { totals.set(lot.key, (totals.get(lot.key) || 0) + lot.count); });
    var top = Array.from(totals.entries()).sort(function (a, b) { return b[1] - a[1]; });
    var text = top.slice(0, 3).map(function (t) { return itemName(t[0]) + " ×" + grouped(t[1]); }).join(", ");
    return top.length > 3 ? text + ", +" + (top.length - 3) + " more" : text;
  }

  function nameRow(i) {
    var row = document.createElement("div");
    row.className = "names-row";
    var label = document.createElement("label");
    label.className = "names-label";
    label.htmlFor = "names-each-" + i;
    label.textContent = "Shulker " + (i + 1);
    var what = document.createElement("span");
    what.className = "names-what";
    var input = document.createElement("input");
    input.type = "text";
    input.id = "names-each-" + i;
    input.className = "mc-input";
    input.maxLength = 50;
    input.placeholder = "unnamed";
    input.autocomplete = "off";
    input.spellcheck = false;
    input.value = names.each[i] || "";
    input.addEventListener("input", function () {
      names.each[i] = input.value;
      renderNamesWarn();
    });
    row.appendChild(label);
    row.appendChild(what);
    row.appendChild(input);
    return { row: row, what: what, input: input, text: null };
  }

  function renderNames() {
    var units = plan.units;
    if (names.mode === "each") {
      while (names.rows.length < units.length) {
        var r = nameRow(names.rows.length);
        names.rows.push(r);
        el.namesRows.appendChild(r.row);
      }
      while (names.rows.length > units.length) {
        var gone = names.rows.pop();
        if (gone.row.contains(document.activeElement)) gone.input.blur();
        gone.row.remove();
      }
      units.forEach(function (u, i) {
        var text = unitSummary(u);
        var r = names.rows[i];
        if (r.text !== text) {
          r.text = text;
          r.what.textContent = text;
          r.what.title = text;
        }
      });
      var hint = el.namesRows.querySelector(".names-empty");
      if (!units.length && !hint) el.namesRows.appendChild(line("note names-empty", "Add items to the cart to name its shulkers."));
      if (units.length && hint) hint.remove();
    }
    renderNamesWarn();
  }

  /** How many planned shulkers would get a name. */
  function namedCount() {
    var n = plan.units.length;
    if (names.mode === "all") return names.all.trim() ? n : 0;
    if (names.mode !== "each") return 0;
    var c = 0;
    for (var i = 0; i < n; i++) if (names.each[i] && names.each[i].trim()) c++;
    return c;
  }

  /** The order's names field: null, {mode: "all", name} or {mode: "each", names: [one per planned shulker]}. */
  function namesBody() {
    if (names.mode === "all") return names.all.trim() ? { mode: "all", name: names.all.trim() } : null;
    if (names.mode !== "each") return null;
    var list = [];
    // At most 54 (the server's cap): a bigger cart is refused for its size anyway, and that error should be the one shown
    for (var i = 0; i < Math.min(plan.units.length, 54); i++) list.push((names.each[i] || "").trim());
    return list.some(function (v) { return v; }) ? { mode: "each", names: list } : null;
  }

  /** Warnings from the stash's bot (heartbeat "naming"). Only a warning: the order can still be placed. */
  function renderNamesWarn() {
    var text = namesWarnText(currentStash(), namedCount());
    if (el.namesWarn.textContent !== text) el.namesWarn.textContent = text;
    el.namesWarn.hidden = !text;
    renderCraftInfo();
  }

  function namesWarnText(s, n) {
    var text = "";
    if (s && n > 0) {
      var nm = s.naming && typeof s.naming === "object" ? s.naming : null;
      if (!nm) {
        text = "This bot can't name shulkers; they'll arrive unnamed.";
      } else if (nm.anvil === false && typeof nm.anvilSource === "string") {
        // A newer bot finds an anvil in the stash area, or places one from stock, when none is set.
        text = "No anvil yet: the bot will look for one in the stash area, or place one from the stash. " +
          "If it can't, the shulkers arrive unnamed.";
      } else if (nm.anvil === false) {
        text = "No anvil set; the shulkers will arrive unnamed. Set one on the bot: in Meteor, stash-manager → select-anvil; " +
          "configure the anvil in the agent settings.";
      } else if (typeof nm.xpLevels === "number" && nm.xpLevels >= 0 && nm.xpLevels < n) {
        text = "May run out of XP: about 1 level per shulker. The bot has " + plural(String(nm.xpLevels), "level") + " for " +
          plural(String(n), "named shulker") + "; any it can't name arrive unnamed.";
      }
    }
    return text;
  }

  /** "3 delivery chests, 1 full, can put up more" from the bot's heartbeat "deliveryStorage", or "". */
  function storageText(ds) {
    if (!ds || typeof ds !== "object") return "";
    var chests = Math.max(0, Math.floor(num(ds.chests, 0)));
    var full = Math.max(0, Math.floor(num(ds.full, 0)));
    if (!chests) return "";
    var text = plural(grouped(chests), "delivery chest") + (full ? ", " + grouped(full) + " full" : "");
    var blocked = str(ds.blocked);
    if (ds.canExpand === true) text += ", can put up more when full";
    else if (blocked) text += full >= chests ? ": waiting for a player to empty one (" + blocked + ")" : " (can't put up more: " + blocked + ")";
    else if (ds.autoExpand === false) text += " (auto expand is off)";
    return text + ".";
  }

  /** Info from the stash's bot (heartbeat "crafting" and "deliveryStorage"). */
  function renderCraftInfo() {
    var s = currentStash();
    var c = s && s.crafting && typeof s.crafting === "object" ? s.crafting : null;
    var text = "";
    if (c && c.enabled === true) {
      // A newer bot says where its table comes from (tableSource); without one yet it can find, place or craft one.
      var src = typeof c.tableSource === "string" ? c.tableSource : "";
      var named = src === "set" || src === "found" || src === "placed" || src === "crafted";
      if (c.table === true) {
        text = "The bot may craft missing empty shulker boxes from the stash's materials" + (named ? " (crafting table: " + src + ")." : ".");
      } else if (src) {
        text = "No crafting table yet: the bot will look for one in the stash area, place one from the stash, or craft one from planks or logs.";
      } else {
        text = "Crafting is on, but no crafting table is set: the bot can't craft shulker boxes.";
      }
    }
    var st = storageText(s && s.deliveryStorage);
    if (st) text = text ? text + " " + st : st;
    if (el.craftInfo.textContent !== text) el.craftInfo.textContent = text;
    el.craftInfo.hidden = !text;
  }

  // ---------- orders: delivery chest and shulker view ----------
  // A fixed pool of 54 chest slots and 27 shulker slots, updated in place. Icons are only reloaded when their
  // URL changes. Pooled slots are registered in slotEntry, so they get the usual tooltip.

  var plan = { units: [], selected: null, fullCount: -1, watchFull: false, toasted: false, chest: [], bin: [], slotUnit: [], unitSlot: [] };

  // ---------- orders: what is already in the delivery storage ----------
  // The bot's latest report (PROTOCOL.md, "Additions: delivery storage contents"), fetched when the stash list's
  // summary changes. Only for the delivery chest preview: never part of the stock counts. Names come from players:
  // textContent only.

  var dlv = { key: null, hash: null, data: null, token: 0 };

  function deliveryMaybeLoad() {
    var s = currentStash();
    var sum = s && s.deliveryContents && typeof s.deliveryContents === "object" ? s.deliveryContents : null;
    var key = s ? stashKey(s) : null;
    if (!sum) {
      if (dlv.data || dlv.key !== key) {
        dlv.key = key;
        dlv.hash = null;
        dlv.data = null;
        if (state.tab === "orders") renderCart();
      }
      return;
    }
    var hash = str(sum.hash) + "|" + num(sum.receivedAt, 0);
    if (dlv.key === key && dlv.hash === hash) return;
    var token = ++dlv.token;
    api("/api/v1/delivery?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash)).then(function (d) {
      if (token !== dlv.token) return;
      dlv.key = key;
      dlv.hash = hash;
      dlv.data = d && typeof d === "object" && Array.isArray(d.chests) ? d : null;
      if (state.tab === "orders") renderCart();
    }, function () {});
  }

  /** The report's chests (in fill order), or [] (an older bot, or no report yet). */
  function deliveryChests() {
    var s = currentStash();
    if (!dlv.data || !s || dlv.key !== stashKey(s)) return [];
    return dlv.data.chests.filter(function (c) { return c && typeof c === "object" && (c.size === 27 || c.size === 54); });
  }

  /** A readable name for an item id: from the stash index when it has the item, else the id. */
  function nameForId(id) {
    for (var i = 0; i < state.items.length; i++) if (state.items[i].id === id) return state.items[i].name;
    return id.replace(/^minecraft:/, "");
  }

  function poolSlot(extraClass, bar) {
    var slot = document.createElement("div");
    slot.className = "slot " + extraClass;
    var img = document.createElement("img");
    img.alt = "";
    img.width = 32;
    img.height = 32;
    img.draggable = false;
    img.hidden = true;
    var countEl = document.createElement("span");
    countEl.className = "count";
    slot.appendChild(img);
    slot.appendChild(countEl);
    var p = { slot: slot, img: img, countEl: countEl, url: null, tries: 0, item: null, fill: null };
    if (bar) {
      var b = document.createElement("div");
      b.className = "fill-bar";
      p.fill = document.createElement("div");
      p.fill.className = "fill";
      b.appendChild(p.fill);
      slot.appendChild(b);
    }
    img.addEventListener("error", function () {
      if (img.getAttribute("src") !== PLACEHOLDER) img.src = PLACEHOLDER;
      p.tries++;
      if (p.tries > ICON_RETRIES) return;
      var n = p.tries;
      var url = p.url;
      window.setTimeout(function () {
        if (p.url === url && img.isConnected) img.src = url + "&retry=" + n;
      }, retryDelay(n));
    });
    return p;
  }

  /** Shows an item in a pooled slot (null clears it). item is what the tooltip shows; contents a shulker's. */
  function fillSlot(p, id, item, countText, contents) {
    setFace(p, id ? shulkerFace(contents) : null);
    var url = id ? iconUrl(id, state.mc) : null;
    if (url !== p.url) {
      p.url = url;
      p.tries = 0;
      if (url) {
        p.img.hidden = false;
        p.img.src = url;
      } else {
        p.img.hidden = true;
        p.img.removeAttribute("src");
      }
    }
    var text = countText || "";
    if (p.countEl.textContent !== text) p.countEl.textContent = text;
    p.item = item;
    setDurability(p.slot, item);
    if (item) slotEntry.set(p.slot, p);
    else slotEntry.delete(p.slot);
    p.slot.classList.toggle("empty-slot", !item);
  }

  function buildPools() {
    if (plan.chest.length) return;
    var i;
    for (i = 0; i < MAX_SHULKERS; i++) {
      (function (index) {
        var p = poolSlot("chest-slot", true);
        p.onActivate = function () {
          var ui = plan.slotUnit[index];
          if (ui == null || ui < 0) return;
          plan.selected = ui;
          renderPlan();
        };
        p.onContext = function () {
          var ui = plan.slotUnit[index];
          var u = ui == null || ui < 0 ? null : plan.units[ui];
          if (!u) return;
          if (u.kind === "bin") {
            plan.selected = ui;
            renderPlan();
          } else {
            removeFromCart(u.kind === "shulker" ? "shulker" : "item", u.key, 1);
          }
        };
        plan.chest.push(p);
        el.chestGrid.appendChild(p.slot);
      })(i);
    }
    for (i = 0; i < SHULKER_SLOTS; i++) {
      (function (index) {
        var p = poolSlot("bin-slot", false);
        p.onContext = function () {
          var u = plan.units[shownUnit()];
          if (u && u.kind === "bin" && u.lots[index]) removeFromCart("item", u.lots[index].key, u.lots[index].count);
        };
        plan.bin.push(p);
        el.binGrid.appendChild(p.slot);
      })(i);
    }
  }

  /** The unit shown in the 9x3 view: the clicked one, or the last one. */
  function shownUnit() {
    if (!plan.units.length) return -1;
    if (plan.selected !== null && plan.selected < plan.units.length) return plan.selected;
    return plan.units.length - 1;
  }

  function unitInfo(u, index, total) {
    var where = "Shulker " + (index + 1) + " of " + total;
    if (u.kind === "shulker") {
      var g = state.shulkerByKey && state.shulkerByKey.get(u.key);
      var gi = g ? g.item : null;
      return {
        id: gi ? gi.id : "minecraft:shulker_box",
        used: gi ? gi.contents.length : 0,
        contents: gi ? gi.contents : [],
        title: (gi ? gi.name : u.key) + " (packed, as it is)",
        details: [where, "Right-click to remove it"]
      };
    }
    if (u.kind === "empty") {
      var it = itemByKey(u.key);
      return {
        id: it ? it.id : "minecraft:shulker_box",
        used: 0,
        contents: [],
        title: "Empty " + (it ? it.name : "Shulker Box"),
        details: [where, "Right-click to remove it"]
      };
    }
    return {
      id: "minecraft:shulker_box",
      used: u.lots.length,
      contents: u.lots.map(function (lot, i) { return { slot: i, key: lot.key, count: lot.count }; }),
      title: "Shulker Box (packed for you)",
      details: [where].concat(shulkerSummary(u.lots), ["Click to look inside"])
    };
  }

  function tipItem(id, name, details) {
    return { key: id, id: id, name: name, englishName: name, count: 1, maxStack: 1, details: details, enchanted: false, plain: true };
  }

  /**
   * Where things go in the 54-slot preview: what chest 1 already holds stays in its slots, the planned shulkers take
   * the free slots after them (those past the chest's free room are marked: they go to the next chest).
   */
  function layoutPlan(units) {
    var chests = deliveryChests();
    var c0 = chests.length && chests[0].n === 0 && Array.isArray(chests[0].items) ? chests[0] : null;
    var existing = {};
    var size = c0 ? c0.size : MAX_SHULKERS;
    if (c0) c0.items.forEach(function (it) { if (it && typeof it.slot === "number") existing[it.slot] = it; });
    var slotUnit = [];
    var unitSlot = [];
    var overflow = {};
    var next = 0;
    // Free slots of the chest first, then the rest of the grid for what doesn't fit (the next chest).
    var order = [];
    var i;
    for (i = 0; i < MAX_SHULKERS; i++) if (i < size && !existing[i]) order.push(i);
    for (i = 0; i < MAX_SHULKERS; i++) if (i >= size) order.push(i);
    var freeInChest = order.filter(function (k) { return k < size; }).length;
    for (i = 0; i < MAX_SHULKERS; i++) slotUnit.push(-1);
    units.forEach(function (u, ui) {
      if (next >= order.length) {
        unitSlot.push(-1);
        return;
      }
      var slot = order[next++];
      slotUnit[slot] = ui;
      unitSlot.push(slot);
      if (c0 && (slot >= size || ui >= freeInChest)) overflow[slot] = true;
    });
    return { existing: existing, slotUnit: slotUnit, unitSlot: unitSlot, overflow: overflow, size: c0 ? size : 0 };
  }

  function existingTip(it) {
    var id = str(it.id);
    var name = str(it.name) || nameForId(id);
    var count = Math.max(1, Math.floor(num(it.count, 1)));
    var details = ["Already in the delivery chest", grouped(count) + " \u00d7 " + name];
    if (it.shulker === true) {
      if (Array.isArray(it.contents)) {
        if (!it.contents.length) details.push("Empty");
        it.contents.slice(0, 12).forEach(function (c) {
          if (c && typeof c.id === "string") details.push(grouped(Math.floor(num(c.count, 0))) + " \u00d7 " + nameForId(c.id));
        });
        if (it.contents.length > 12) details.push("and " + (it.contents.length - 12) + " more");
      } else {
        details.push("Contents not reported");
      }
    }
    var tip = tipItem(id, name, details);
    tip.damage = Math.max(0, Math.floor(num(it.damage, 0)));
    tip.maxDamage = Math.max(0, Math.floor(num(it.maxDamage, 0)));
    return tip;
  }

  function renderPlan() {
    buildPools();
    var units = plan.units;
    var shown = shownUnit();
    var lay = layoutPlan(units);
    plan.slotUnit = lay.slotUnit;
    plan.unitSlot = lay.unitSlot;
    for (var i = 0; i < MAX_SHULKERS; i++) {
      var p = plan.chest[i];
      var ex = lay.existing[i];
      var ui = lay.slotUnit[i];
      var u = ui >= 0 ? units[ui] : null;
      p.slot.classList.toggle("existing", !!ex);
      p.slot.classList.toggle("overflow", !!lay.overflow[i]);
      p.slot.classList.toggle("outside", lay.size > 0 && i >= lay.size && !u);
      if (ex) {
        var contents = Array.isArray(ex.contents) ? ex.contents.map(function (c, k) { return { slot: k, key: str(c.id), count: num(c.count, 1) }; }) : null;
        var cnt = Math.floor(num(ex.count, 1));
        fillSlot(p, str(ex.id), existingTip(ex), cnt > 1 ? String(cnt) : "", ex.shulker === true ? contents : null);
        p.fill.style.transform = "scaleX(0)";
        p.slot.classList.remove("full", "selected", "bar-on");
        continue;
      }
      if (!u) {
        fillSlot(p, null, null, "");
        p.fill.style.transform = "scaleX(0)";
        p.slot.classList.remove("full", "selected", "bar-on");
        continue;
      }
      var info = unitInfo(u, ui, units.length);
      var extra = lay.overflow[i] ? ["Doesn't fit in this chest: goes to the next one"] : [];
      fillSlot(p, info.id, tipItem(info.id, info.title, info.details.concat([info.used + "/" + SHULKER_SLOTS + " slots"], extra)), "", info.contents);
      p.fill.style.transform = "scaleX(" + info.used / SHULKER_SLOTS + ")";
      p.slot.classList.add("bar-on");
      p.slot.classList.toggle("full", info.used >= SHULKER_SLOTS);
      p.slot.classList.toggle("selected", ui === shown);
    }
    renderDeliveryFit(units.length, lay);

    var su = shown >= 0 ? units[shown] : null;
    var sinfo = su ? unitInfo(su, shown, units.length) : null;
    var bySlot = {};
    if (sinfo) sinfo.contents.forEach(function (c) { bySlot[c.slot] = c; });
    for (var j = 0; j < SHULKER_SLOTS; j++) {
      var c = bySlot[j];
      if (!c) {
        fillSlot(plan.bin[j], null, null, "");
        continue;
      }
      var base = itemByKey(c.key) ||
        { key: c.key, id: c.key.split("#")[0], name: c.key, englishName: c.key, maxStack: 64, details: [], enchanted: false };
      var shownItem = Object.assign({}, base, { count: c.count });
      if (su.kind === "bin") shownItem.details = base.details.concat(["Right-click to remove this stack"]);
      fillSlot(plan.bin[j], base.id, shownItem, c.count === 1 ? "" : String(c.count));
    }
    var kind = su ? (su.kind === "bin" ? "" : su.kind === "empty" ? "empty · " : "packed · ") : "";
    el.binTitle.textContent = sinfo
      ? "Shulker " + (shown + 1) + " of " + units.length + " · " + kind + sinfo.used + "/" + SHULKER_SLOTS + " slots"
      : "Shulker Box · nothing planned yet";
    el.binGrid.parentNode.classList.toggle("full", !!sinfo && su.kind === "bin" && sinfo.used >= SHULKER_SLOTS);
    el.cartCount.textContent = units.length + " / " + MAX_SHULKERS + " shulkers";
    el.cartCount.classList.toggle("warn", units.length >= MAX_SHULKERS);
    refreshTooltip();
  }

  /**
   * "Delivery chest: N of 54 slots used, M free; this order needs K (as of X ago)", totals over several chests, and a
   * warning when the order won't fit (what the bot will do then, from the heartbeat's deliveryStorage).
   */
  function renderDeliveryFit(need, lay) {
    var chests = deliveryChests();
    var text = "";
    var warn = "";
    if (chests.length) {
      var known = chests.filter(function (c) { return Array.isArray(c.items); });
      var unknown = chests.length - known.length;
      var used = 0;
      var total = 0;
      known.forEach(function (c) {
        used += c.items.length;
        total += c.size;
      });
      var free = total - used;
      var c0 = chests[0];
      var asOf = typeof dlv.data.readAt === "number" && dlv.data.readAt > 0 ? " (as of " + ago(dlv.data.readAt) + ")" : "";
      var needText = need ? "; this order needs " + need : "";
      if (chests.length === 1) {
        text = Array.isArray(c0.items)
          ? "Delivery chest: " + c0.items.length + " of " + c0.size + " slots used, " + (c0.size - c0.items.length) + " free" + needText + asOf + "."
          : "Delivery chest: not seen yet" + needText + ".";
      } else {
        var first = Array.isArray(c0.items)
          ? "the first (filled first) has " + (c0.size - c0.items.length) + " of " + c0.size + " free"
          : "the first is not seen yet";
        text = chests.length + " delivery chests: " + (known.length ? used + " of " + total + " slots used, " + free + " free" : "none seen yet") +
          (unknown && known.length ? " (" + unknown + " not seen yet)" : "") + "; " + first + needText + asOf + ".";
      }
      if (need > free && !unknown) {
        var s = currentStash();
        var ds = s && s.deliveryStorage && typeof s.deliveryStorage === "object" ? s.deliveryStorage : null;
        warn = "This order needs " + need + " slots but only " + free + " are free: " + (ds && ds.autoExpand === true
          ? "the bot will put up more chests (if it can)."
          : "the order will pause until a chest is emptied.");
      } else if (lay && Object.keys(lay.overflow).length) {
        warn = "Not all of it fits in the first chest: the rest goes into the next one.";
      }
    }
    if (el.deliveryFit.textContent !== text) el.deliveryFit.textContent = text;
    el.deliveryFit.hidden = !text;
    if (el.deliveryWarn.textContent !== warn) el.deliveryWarn.textContent = warn;
    el.deliveryWarn.hidden = !warn;
  }

  function flash(node) {
    node.classList.remove("flash");
    void node.offsetWidth; // restart the animation
    node.classList.add("flash");
  }

  /** "Shulker 2 full" when an addition filled another planned shulker. */
  function noticeFull() {
    var full = [];
    plan.units.forEach(function (u, i) { if (u.kind === "bin" && u.lots.length >= SHULKER_SLOTS) full.push(i); });
    plan.toasted = false;
    if (plan.watchFull && plan.fullCount >= 0 && full.length > plan.fullCount) {
      var last = full[full.length - 1];
      toast("Shulker " + (last + 1) + " full (27/27)");
      plan.toasted = true;
      var lastSlot = plan.unitSlot[last];
      if (lastSlot != null && lastSlot >= 0 && plan.chest[lastSlot]) flash(plan.chest[lastSlot].slot);
      if (shownUnit() === last) flash(el.binGrid.parentNode);
    }
    plan.fullCount = full.length;
    plan.watchFull = false;
  }

  // ---------- orders: cart pane ----------

  function renderCart() {
    var frag = document.createDocumentFragment();
    var packed = 0;
    var empties = 0;
    var totalItems = 0;
    if (!cart.lines.length) {
      var empty = document.createElement("div");
      empty.className = "note";
      empty.textContent = "The cart is empty. Drag items here, double-click them, or shift-click items in the other tabs.";
      frag.appendChild(empty);
    }
    cart.lines.forEach(function (l, i) {
      var item = lineItem(l.type, l.key);
      var row = document.createElement("div");
      row.className = "cart-line" + (i === cart.badLine ? " bad" : "");
      var cartIcon = item ? iconWithContents(item.id, l.type === "shulker" ? item.contents : null, 32) : iconImg("minecraft:barrier", 32);
      if (item && durabilityLeft(item) >= 0) {
        if (!cartIcon.classList.contains("icon-wrap")) {
          var wrapIcon = document.createElement("span");
          wrapIcon.className = "icon-wrap";
          wrapIcon.appendChild(cartIcon);
          cartIcon = wrapIcon;
        }
        setDurability(cartIcon, item);
      }
      row.appendChild(cartIcon);
      var name = document.createElement("span");
      name.className = "cart-name";
      name.textContent = item ? displayName(l.type, item) : l.key + " (no longer in the stash)";
      row.appendChild(name);
      var input = document.createElement("input");
      input.type = "number";
      input.className = "mc-input qty-input";
      input.min = "1";
      var max = Math.max(1, availableTotal(l.type, l.key) - (cartCount(l.type, l.key) - l.count));
      input.max = String(max);
      input.value = String(l.count);
      input.setAttribute("aria-label", "Count");
      input.addEventListener("change", function () {
        var maxNow = Math.max(1, availableTotal(l.type, l.key) - (cartCount(l.type, l.key) - l.count));
        var n = clampInt(input.value, 1, maxNow);
        var old = l.count;
        l.count = n;
        var need = shulkersNeeded(cart.lines);
        if (need > MAX_SHULKERS && n > old) {
          l.count = old;
          input.value = String(old);
          refuse(capMessage(need));
          return;
        }
        cart.badLine = -1;
        plan.watchFull = n > old;
        saveCart();
        renderCart();
        updatePickerCounts();
      });
      row.appendChild(input);
      var rm = document.createElement("button");
      rm.type = "button";
      rm.className = "mc-button small danger";
      rm.textContent = "×";
      rm.setAttribute("aria-label", "Remove");
      rm.addEventListener("click", function () {
        cart.lines.splice(i, 1);
        cart.badLine = -1;
        saveCart();
        renderCart();
        updatePickerCounts();
      });
      row.appendChild(rm);
      frag.appendChild(row);
      if (l.type === "shulker") packed += l.count;
      else if (isEmptyShulkerLine(l)) empties += l.count;
      else totalItems += l.count;
    });
    el.cartLines.replaceChildren(frag);

    var units = planCart(cart.lines);
    var need = shulkersNeeded(cart.lines);
    plan.units = units || [];
    renderPlan();
    renderNames();
    noticeFull();
    if (!units) {
      // A stored cart (or a new index with a smaller maxStack) can be over the limit.
      el.cartCount.textContent = need + " / " + MAX_SHULKERS + " shulkers";
      el.cartCount.classList.add("warn");
    }
    var parts = [];
    if (totalItems) parts.push(grouped(totalItems) + (totalItems === 1 ? " item" : " items"));
    if (packed) parts.push(plural(String(packed), "packed shulker"));
    if (empties) parts.push(plural(String(empties), "empty shulker"));
    el.cartTotals.textContent = cart.lines.length
      ? parts.join(" + ") + " · " + plural(String(need), "shulker") + " to deliver" + (units ? "" : ". Too many: one order can fill at most " + MAX_SHULKERS + " (one double chest).")
      : "";
    el.cartTotals.classList.toggle("warn", !units);
    $("order-purpose-row").hidden = !can("guildOrders");
    $("order-recipient-row").hidden = !can("guildOrders");
    el.cartPlace.disabled = !cart.lines.length || !state.items.length || !units;
    el.cartClear.disabled = !cart.lines.length;
    var s = currentStash();
    el.cartOffline.hidden = !(s && !s.online);
  }

  el.cartClear.addEventListener("click", function () {
    if (!cart.lines.length || !window.confirm("Remove everything from the cart?")) return;
    cart.lines = [];
    cart.badLine = -1;
    plan.selected = null;
    clearStatus();
    saveCart();
    renderCart();
    updatePickerCounts();
  });

  var pendingSubmission = null;

  function submissionKey(body) {
    var serialized = JSON.stringify(body);
    if (!pendingSubmission || pendingSubmission.body !== serialized) {
      var random = window.crypto.getRandomValues(new Uint8Array(16));
      var key = Array.from(random, function (byte) { return byte.toString(16).padStart(2, "0"); }).join("");
      pendingSubmission = { body: serialized, key: key };
    }
    return pendingSubmission.key;
  }

  el.cartPlace.addEventListener("click", function () {
    var s = currentStash();
    if (!s || !cart.lines.length) return;
    el.cartPlace.disabled = true;
    el.cartStatus.textContent = "Placing the order...";
    el.cartStatus.classList.remove("warn");
    var draft = { server: s.server, stash: s.stash, names: namesBody(), lines: cart.lines.slice(),
      purpose: can("guildOrders") ? $("order-purpose").value : "SELF", comment: $("order-comment").value };
    if (can("guildOrders") && $("order-recipient").value.trim()) {
      draft.recipient = $("order-recipient").value.trim();
    }
    api("/api/v1/orders", {
      method: "POST", body: draft, idempotencyKey: submissionKey(draft)
    }).then(function () {
      pendingSubmission = null;
      cart.lines = [];
      cart.badLine = -1;
      plan.selected = null;
      saveCart();
      resetNames();
      $("order-purpose").value = "SELF";
      $("order-recipient").value = "";
      $("order-comment").value = "";
      el.cartStatus.textContent = s.online ? "Order placed." : "Order placed. It will start when the bot reconnects.";
      toast("Order placed");
      renderCart();
      updatePickerCounts();
      scheduleOrdersPoll(0);
    }, function (e) {
      var body = e && e.body;
      cart.badLine = body && typeof body.line === "number" ? body.line : -1;
      el.cartStatus.textContent = body && body.error ? body.error : "Could not place the order" + (e && e.status ? " (HTTP " + e.status + ")" : "") + ".";
      el.cartStatus.classList.add("warn");
      renderCart();
      scheduleOrdersPoll(0);
    });
  });

  // ---------- orders: drag and drop, double-click ----------
  // Pointer events instead of HTML5 drag and drop: the slots and their tooltips stay as they are, and the ghost
  // is moved with a transform once per animation frame.

  var drag = { pe: null, active: false, x0: 0, y0: 0, x: 0, y: 0, shift: false, raf: 0, over: false, suppressClick: false };
  var DRAG_THRESHOLD = 5;

  function pickEntryFrom(target) {
    var slot = target && target.closest ? target.closest(".picker-grid .slot") : null;
    var pe = slot ? slotEntry.get(slot) : null;
    return pe && pe.pick ? pe : null;
  }

  el.pickerBody.addEventListener("pointerdown", function (e) {
    if (e.button !== 0 || !e.isPrimary || e.pointerType === "touch") return;
    var pe = pickEntryFrom(e.target);
    if (!pe) return;
    drag.pe = pe;
    drag.active = false;
    drag.x0 = e.clientX;
    drag.y0 = e.clientY;
  });

  function startDrag() {
    drag.active = true;
    hideTooltip();
    var img = document.createElement("img");
    img.alt = "";
    img.width = 32;
    img.height = 32;
    img.src = drag.pe.img.getAttribute("src") || PLACEHOLDER;
    var label = document.createElement("span");
    label.className = "ghost-label";
    el.dragGhost.replaceChildren(img, label);
    el.dragGhost.hidden = false;
    document.body.classList.add("dragging");
    el.cartDrop.classList.add("drop-zone");
  }

  function applyDrag() {
    drag.raf = 0;
    if (!drag.active) return;
    el.dragGhost.style.transform = "translate(" + Math.round(drag.x - 16) + "px, " + Math.round(drag.y - 16) + "px)";
    var t = document.elementFromPoint(drag.x, drag.y);
    var over = !!t && el.cartDrop.contains(t);
    if (over !== drag.over) {
      drag.over = over;
      el.cartDrop.classList.toggle("drop-over", over);
    }
    var label = el.dragGhost.lastChild;
    var text = drag.pe.pick.type === "shulker" ? "+1" : drag.shift ? "+1 shulker" : "+1 stack";
    if (label && label.textContent !== text) label.textContent = text;
  }

  function endDrag() {
    drag.pe = null;
    drag.active = false;
    drag.over = false;
    el.dragGhost.hidden = true;
    el.dragGhost.replaceChildren();
    document.body.classList.remove("dragging");
    el.cartDrop.classList.remove("drop-zone", "drop-over");
  }

  document.addEventListener("pointermove", function (e) {
    if (!drag.pe) return;
    if (!drag.active) {
      if (Math.abs(e.clientX - drag.x0) + Math.abs(e.clientY - drag.y0) < DRAG_THRESHOLD) return;
      startDrag();
    }
    drag.x = e.clientX;
    drag.y = e.clientY;
    drag.shift = e.shiftKey;
    if (!drag.raf) drag.raf = window.requestAnimationFrame(applyDrag);
  }, { passive: true });

  document.addEventListener("pointerup", function (e) {
    if (!drag.pe) return;
    var pe = drag.pe;
    var wasActive = drag.active;
    endDrag();
    if (!wasActive) return;
    // The click that follows this pointerup must not also select the item.
    drag.suppressClick = true;
    window.setTimeout(function () { drag.suppressClick = false; }, 0);
    var t = document.elementFromPoint(e.clientX, e.clientY);
    if (t && el.cartDrop.contains(t)) addStacks(pe.pick.type, pe.pick.item, e.shiftKey);
  });

  document.addEventListener("pointercancel", function () {
    if (drag.pe) endDrag();
  });
  window.addEventListener("blur", function () {
    if (drag.pe) endDrag();
  });

  el.pickerBody.addEventListener("dblclick", function (e) {
    var pe = pickEntryFrom(e.target);
    if (!pe) return;
    e.preventDefault();
    var sel = window.getSelection && window.getSelection();
    if (sel && sel.removeAllRanges) sel.removeAllRanges();
    addStacks(pe.pick.type, pe.pick.item, e.shiftKey);
  });

  // ---------- orders: picker pane ----------

  function crumb(text, onClick) {
    if (!onClick) {
      var span = document.createElement("span");
      span.className = "crumb current";
      span.textContent = text;
      return span;
    }
    var b = document.createElement("button");
    b.type = "button";
    b.className = "crumb";
    b.textContent = text;
    b.addEventListener("click", onClick);
    return b;
  }

  function pickerTypes() {
    var out = visibleTabs().filter(function (t) { return isCreativeTab(t.id); });
    if (state.shulkers.length) out.push({ id: "packed", name: "Shulker boxes (packed)", icon: "minecraft:shulker_box" });
    return out;
  }

  function renderCrumbs() {
    var crumbs = document.createDocumentFragment();
    var toTypes = function () { picker.level = "types"; picker.type = null; picker.entry = null; renderPicker(); };
    if (picker.level === "types") {
      crumbs.appendChild(crumb("Item type", null));
    } else {
      var type = picker.type === "packed" ? { name: "Shulker boxes (packed)" } : tabInfo(picker.type);
      crumbs.appendChild(crumb("Item type", toTypes));
      crumbs.appendChild(document.createTextNode(" › "));
      if (!picker.entry) {
        crumbs.appendChild(crumb(type.name, null));
      } else {
        crumbs.appendChild(crumb(type.name, function () { selectPick(null); }));
        crumbs.appendChild(document.createTextNode(" › "));
        crumbs.appendChild(crumb(displayName(picker.entry.type, picker.entry.item), null));
      }
    }
    el.crumbs.replaceChildren(crumbs);
  }

  function renderPicker() {
    var body = document.createDocumentFragment();
    picker.slots = [];
    picker.qtyHost = null;
    if (!state.items.length) {
      el.crumbs.replaceChildren();
      var none = document.createElement("div");
      none.className = "note";
      none.textContent = "No stash index yet.";
      el.pickerBody.replaceChildren(none);
      return;
    }
    if (picker.level === "types") {
      var list = document.createElement("div");
      list.className = "type-buttons";
      pickerTypes().forEach(function (t) {
        var b = document.createElement("button");
        b.type = "button";
        b.className = "mc-button type-button";
        b.appendChild(iconImg(t.icon, 32));
        var label = document.createElement("span");
        label.textContent = t.name;
        b.appendChild(label);
        b.addEventListener("click", function () {
          picker.level = "items";
          picker.type = t.id;
          picker.entry = null;
          picker.search = "";
          renderPicker();
        });
        list.appendChild(b);
      });
      body.appendChild(list);
    } else {
      body.appendChild(pickerItems());
      var host = document.createElement("div");
      host.className = "qty-host";
      picker.qtyHost = host;
      body.appendChild(host);
    }
    renderCrumbs();
    el.pickerBody.replaceChildren(body);
    renderQty();
    if (state.hovered && !state.hovered.slot.isConnected) hideTooltip();
    var search = el.pickerBody.querySelector(".picker-search");
    if (search && picker.focusSearch) {
      search.focus();
      search.setSelectionRange(search.value.length, search.value.length);
    }
    picker.focusSearch = false;
  }

  /** Selects an item in the grid (or nothing) and shows its quantity panel under the grid; the grid itself stays. */
  function selectPick(pick) {
    picker.entry = pick;
    picker.slots.forEach(function (pe) {
      pe.slot.classList.toggle("picked", !!pick && pe.pick.type === pick.type && pe.pick.item.key === pick.item.key);
    });
    renderCrumbs();
    renderQty();
  }

  function renderQty() {
    if (!picker.qtyHost) return;
    picker.qtyHost.replaceChildren(picker.entry ? pickerQuantity() : document.createDocumentFragment());
  }

  /** Writes what is left to order into the picker's slots in place, and refreshes the quantity panel. */
  function updatePickerCounts() {
    if (!picker.slots) return;
    picker.slots.forEach(function (pe) {
      var left = availableLeft(pe.pick.type, pe.pick.item.key);
      if (pe.item.count !== left) {
        pe.item.count = left;
        setCount(pe);
      }
      pe.slot.classList.toggle("unavailable", left < 1);
    });
    if (picker.qtyHost && !picker.qtyHost.contains(document.activeElement)) renderQty();
  }

  /** The items of the chosen type as slots, with a search box. Counts show what is orderable. */
  function pickerItems() {
    var wrap = document.createElement("div");
    wrap.className = "picker-items";
    var search = document.createElement("input");
    search.type = "search";
    search.className = "mc-input picker-search";
    search.placeholder = "Filter";
    search.value = picker.search;
    var searchTimer = 0;
    search.addEventListener("input", function () {
      picker.search = search.value;
      window.clearTimeout(searchTimer);
      searchTimer = window.setTimeout(function () {
        picker.focusSearch = true;
        renderPicker();
      }, SEARCH_DEBOUNCE_MS);
    });
    wrap.appendChild(search);

    var packed = picker.type === "packed";
    var source = packed ? state.shulkers : tabEntries(picker.type);
    var query = parseQuery(picker.search);
    var list = source.filter(function (e) { return matches(e, query); }).slice();
    list.sort(packed ? sorters.count : sorters.creative);
    var grid = document.createElement("div");
    grid.className = "item-grid picker-grid";
    list.forEach(function (e) {
      var type = packed ? "shulker" : "item";
      var left = availableLeft(type, e.item.key);
      var label = displayName(type, e.item);
      // Keep the italic "custom name" style only for items that really have a custom name.
      var shown = Object.assign({}, e.item, {
        count: left, name: label, englishName: e.item.name === e.item.englishName ? label : e.item.englishName
      });
      var pe = createEntry(shown, state.mc);
      pe.pick = { type: type, item: e.item };
      if (left < 1) pe.slot.classList.add("unavailable");
      if (picker.entry && picker.entry.type === type && picker.entry.item.key === e.item.key) pe.slot.classList.add("picked");
      pe.onActivate = function () {
        if (drag.suppressClick) return;
        selectPick({ type: type, item: e.item });
      };
      picker.slots.push(pe);
      grid.appendChild(pe.slot);
    });
    if (!list.length) {
      var none = document.createElement("div");
      none.className = "note";
      none.textContent = "Nothing matches.";
      wrap.appendChild(none);
    }
    wrap.appendChild(grid);
    return wrap;
  }

  /** "= 3 stacks + 12 · ≈ 0.1 shulker" */
  function packReadout(n, maxStack, type) {
    if (type === "shulker") return n ? "= " + plural(String(n), "packed shulker") : "";
    var stacks = Math.floor(n / maxStack);
    var rest = n % maxStack;
    var parts = [];
    if (stacks) parts.push(plural(String(stacks), "stack"));
    if (rest || !stacks) parts.push(String(rest));
    return "= " + parts.join(" + ") + " · ≈ " + plural(approx(n / (maxStack * 27)), "shulker");
  }

  /** The quantity panel for the selected item, under the grid. */
  function pickerQuantity() {
    var type = picker.entry.type;
    var item = lineItem(type, picker.entry.item.key) || picker.entry.item;
    var wrap = document.createElement("div");
    wrap.className = "qty-panel";
    var head = document.createElement("div");
    head.className = "qty-head";
    head.appendChild(iconWithContents(item.id, type === "shulker" ? item.contents : null, 32));
    var name = document.createElement("div");
    name.className = "qty-name";
    name.textContent = displayName(type, item);
    head.appendChild(name);
    wrap.appendChild(head);

    var left = availableLeft(type, item.key);
    var info = document.createElement("div");
    info.className = "note";
    var inCart = cartCount(type, item.key);
    info.textContent = grouped(left) + " available" + (inCart ? " (" + grouped(inCart) + " already in the cart)" : "");
    wrap.appendChild(info);

    var row = document.createElement("div");
    row.className = "qty-row";
    var input = document.createElement("input");
    input.type = "number";
    input.className = "mc-input qty-input";
    input.min = "0";
    input.max = String(left);
    input.value = String(Math.min(left, type === "shulker" ? 1 : item.maxStack));
    row.appendChild(input);
    var readout = document.createElement("div");
    readout.className = "qty-readout";
    var add = document.createElement("button");
    var update = function () {
      var n = clampInt(input.value, 0, left);
      readout.textContent = packReadout(n, item.maxStack, type);
      add.disabled = n < 1;
    };
    var quick = function (label, fn) {
      var b = document.createElement("button");
      b.type = "button";
      b.className = "mc-button small";
      b.textContent = label;
      b.addEventListener("click", function () {
        input.value = String(clampInt(fn(clampInt(input.value, 0, left)), 0, left));
        update();
      });
      row.appendChild(b);
    };
    quick("+1", function (n) { return n + 1; });
    if (type === "shulker") {
      quick("+5", function (n) { return n + 5; });
    } else {
      quick("+1 stack", function (n) { return n + item.maxStack; });
      quick("+1 shulker", function (n) { return n + item.maxStack * 27; });
    }
    quick("Max", function () { return left; });
    quick("Clear", function () { return 0; });
    wrap.appendChild(row);
    wrap.appendChild(readout);

    add.type = "button";
    add.className = "mc-button";
    add.textContent = "Add to cart";
    add.addEventListener("click", function () {
      var n = clampInt(input.value, 0, availableLeft(type, item.key));
      if (n < 1) return;
      if (addToCart(type, item.key, n)) {
        clearStatus();
        if (!plan.toasted) toast("Added " + grouped(n) + " × " + displayName(type, item) + " to the cart");
        renderQty();
      }
    });
    input.addEventListener("input", update);
    input.addEventListener("keydown", function (e) {
      if (e.key === "Enter") add.click();
    });
    wrap.appendChild(add);
    if (left < 1) {
      input.disabled = true;
      var none = document.createElement("div");
      none.className = "note warn";
      none.textContent = "None left: the rest is reserved by open orders or already in the cart.";
      wrap.appendChild(none);
    }
    update();
    return wrap;
  }

  // ---------- orders: list ----------

  var STATUS_LABEL = {
    pending: "Pending", accepted: "Accepted", in_progress: "In progress",
    delivered: "Delivered", packed: "Ready for pickup", needs_reconciliation: "Needs reconciliation",
    failed: "Failed", cancelled: "Cancelled"
  };
  var ORDER_STATE_LABEL = {
    CREATED: "Created", RESERVED: "Reserved", PACKING: "Packing", PICKUP_READY: "Ready for pickup",
    PICKED_UP: "Picked up", IN_TRANSIT: "In transit", AWAITING_CUSTOMER: "Awaiting recipient",
    DELIVERED: "Delivered", NEEDS_RECONCILIATION: "Needs reconciliation", FAILED: "Failed", CANCELLED: "Cancelled"
  };
  var OPEN_STATUS = { pending: true, accepted: true, in_progress: true };

  function scheduleOrdersPoll(delay) {
    window.clearTimeout(orders.timer);
    orders.timer = window.setTimeout(pollOrders, delay);
  }

  function pollOrders() {
    var s = currentStash();
    var next = state.tab === "orders" && !document.hidden ? 3000 : 15000;
    if (!s || orders.loading || auth.blocked) {
      scheduleOrdersPoll(next);
      return;
    }
    orders.loading = true;
    var token = orders.token;
    api("/api/v1/orders?server=" + encodeURIComponent(s.server) + "&stash=" + encodeURIComponent(s.stash))
      .then(function (d) {
        if (token !== orders.token) return;
        orders.list = d && Array.isArray(d.orders) ? d.orders : [];
        var available = d && d.available && typeof d.available === "object" ? d.available : null;
        var changed = JSON.stringify(available) !== JSON.stringify(orders.available);
        orders.available = available;
        renderOrderList();
        // Only redraw the cart and picker when availability changed, and never under the user's cursor/focus.
        if (state.tab === "orders" && changed) {
          if (!el.cartLines.contains(document.activeElement)) renderCart();
          updatePickerCounts();
        }
      }, function () {
        // Keep the last list; the header already shows "Server unreachable" when that is the problem.
      })
      .then(function () {
        orders.loading = false;
        scheduleOrdersPoll(state.tab === "orders" && !document.hidden ? 3000 : 15000);
      });
  }

  function linesSummary(o) {
    return o.lines.map(function (l) {
      var item = lineItem(l.type, l.key);
      return grouped(l.count) + " × " + (item ? displayName(l.type, item) : l.key);
    });
  }

  var FINISHED_STATUS = { delivered: true, failed: true, cancelled: true };

  function finishedCount() {
    return orders.list.filter(function (o) {
      return o && (o.state ? ["DELIVERED", "FAILED", "CANCELLED"].indexOf(o.state) >= 0 : FINISHED_STATUS[o.status]);
    }).length;
  }

  el.ordersClear.addEventListener("click", function () {
    var s = currentStash();
    var n = finishedCount();
    if (!s || !n) return;
    if (!window.confirm("Remove " + n + " finished order" + (n === 1 ? "" : "s") + " from the list? Open orders are kept.")) return;
    el.ordersClear.disabled = true;
    api("/api/v1/orders/clear", { method: "POST", body: { server: s.server, stash: s.stash } }).then(function (r) {
      var removed = r && typeof r.removed === "number" ? r.removed : 0;
      toast("Cleared " + removed + " order" + (removed === 1 ? "" : "s"));
      scheduleOrdersPoll(0);
    }, function (e) {
      toast("Could not clear the history" + (e && e.body && e.body.error ? ": " + e.body.error : e && e.status ? " (HTTP " + e.status + ")" : ""));
      renderOrderList();
    });
  });

  var FORCE_CANCEL_AFTER_MS = 60000;

  /** A requested cancel the bot hasn't confirmed within a minute (or the bot is offline). */
  function canForceCancel(o) {
    if (!o.cancelRequested || (o.status !== "accepted" && o.status !== "in_progress")) return false;
    var s = currentStash();
    if (s && !s.online) return true;
    var since = num(o.cancelRequestedAt, 0) || num(o.updatedAt, 0);
    return serverNow() - since >= FORCE_CANCEL_AFTER_MS;
  }

  function renderOrderList() {
    el.ordersClear.hidden = !can("ordersAll");
    el.ordersClear.disabled = !finishedCount();
    var frag = document.createDocumentFragment();
    if (!orders.list.length) {
      var none = document.createElement("div");
      none.className = "note";
      none.textContent = "No orders yet for this stash.";
      frag.appendChild(none);
    }
    orders.list.forEach(function (o) {
      if (!o || typeof o !== "object" || !Array.isArray(o.lines)) return;
      var card = document.createElement("div");
      card.className = "order-card";
      var head = document.createElement("div");
      head.className = "order-head";
      var badge = document.createElement("span");
      badge.className = "badge st-" + str(o.status);
      badge.textContent = (ORDER_STATE_LABEL[o.state] || STATUS_LABEL[o.status] || str(o.status)) + (o.cancelRequested && OPEN_STATUS[o.status] ? " (cancel requested)" : "");
      head.appendChild(badge);
      var when = document.createElement("span");
      when.className = "note";
      when.textContent = new Date(num(o.createdAt, 0)).toLocaleString() + " · " + str(o.id);
      head.appendChild(when);
      var by = o.placedBy && typeof o.placedBy === "object" ? str(o.placedBy.name) : "";
      if (by) head.appendChild(line("note order-by", "by " + by));
      if (o.purpose === "GUILD") head.appendChild(line("note", "Guild order"));
      var manage = canManageOrder(o);
      if (o.approvalState === "PENDING" && o.status === "pending") {
        head.appendChild(line("note", "Awaiting approval"));
        if (can("ordersAll")) ["approve", "reject"].forEach(function (decision) {
          var action = document.createElement("button");
          action.type = "button";
          action.className = "mc-button small";
          action.textContent = decision === "approve" ? "Approve" : "Reject";
          action.addEventListener("click", function () {
            action.disabled = true;
            api("/api/v1/orders/" + encodeURIComponent(o.id) + "/approval", {
              method: "POST", body: {decision: decision}
            }).then(function () {
              scheduleOrdersPoll(0);
            }).catch(function (error) {
              toast(error.body && error.body.error || error.message);
              action.disabled = false;
            });
          });
          head.appendChild(action);
        });
      }
      if (manage && OPEN_STATUS[o.status] && !o.cancelRequested) {
        var cancel = document.createElement("button");
        cancel.type = "button";
        cancel.className = "mc-button small danger";
        cancel.textContent = "Cancel";
        cancel.addEventListener("click", function () {
          var running = o.status !== "pending";
          if (!window.confirm(running ? "Ask the bot to stop this order at a safe point?" : "Cancel this order?")) return;
          cancel.disabled = true;
          api("/api/v1/orders/" + encodeURIComponent(o.id) + "/cancel", { method: "POST", body: {} }).then(function () {
            toast(running ? "Cancel requested" : "Order cancelled");
            scheduleOrdersPoll(0);
          }, function (e) {
            toast("Could not cancel" + (e && e.body && e.body.error ? ": " + e.body.error : ""));
            scheduleOrdersPoll(0);
          });
        });
        head.appendChild(cancel);
      }
      if (manage && (o.status === "failed" || o.status === "cancelled") && !o.retriedAs) {
        var retry = document.createElement("button");
        retry.type = "button";
        retry.className = "mc-button small";
        retry.textContent = "Retry";
        retry.title = "Place this order again as a new order";
        retry.addEventListener("click", function () {
          retry.disabled = true;
          orders.retryErrors.delete(o.id);
          api("/api/v1/orders/" + encodeURIComponent(o.id) + "/retry", { method: "POST", body: {} }).then(function () {
            toast("Order placed again");
            scheduleOrdersPoll(0);
          }, function (e) {
            var why = e && e.body && e.body.error ? str(e.body.error) : "HTTP " + (e && e.status ? e.status : "error");
            orders.retryErrors.set(o.id, why);
            toast("Could not retry: " + why);
            renderOrderList();
          });
        });
        head.appendChild(retry);
      }
      if (manage && canForceCancel(o)) {
        var force = document.createElement("button");
        force.type = "button";
        force.className = "mc-button small danger";
        force.textContent = "Force cancel";
        force.addEventListener("click", function () {
          if (!window.confirm("The bot hasn't confirmed. Mark this order cancelled anyway? If the bot is still working on it, " +
            "it will stop when it next reports.")) return;
          force.disabled = true;
          api("/api/v1/orders/" + encodeURIComponent(o.id) + "/force-cancel", { method: "POST", body: {} }).then(function () {
            toast("Order cancelled");
            scheduleOrdersPoll(0);
          }, function (e) {
            toast("Could not force cancel" + (e && e.body && e.body.error ? ": " + e.body.error : ""));
            scheduleOrdersPoll(0);
          });
        });
        head.appendChild(force);
      }
      card.appendChild(head);
      if (o.recipient) card.appendChild(line("note", (o.deliveryMode === "PLAYER" ? "Confirmed recipient · " : "Requested recipient · ") + o.recipient));
      if (o.comment) card.appendChild(line("note", o.comment));


      if (o.phase || o.progress) {
        var phase = document.createElement("div");
        phase.className = "order-phase";
        phase.textContent = str(o.phase);
        hide(phase);
        card.appendChild(phase);
      }
      if (o.progress && typeof o.progress === "object" && num(o.progress.total, 0) > 0) {
        var bar = document.createElement("div");
        bar.className = "progress";
        var fill = document.createElement("div");
        fill.className = "progress-fill";
        // A transform on the CSSOM (allowed by the CSP) instead of an inline width style.
        fill.style.transform = "scaleX(" + Math.max(0, Math.min(1, num(o.progress.done, 0) / num(o.progress.total, 1))) + ")";
        bar.appendChild(fill);
        bar.title = num(o.progress.done, 0) + " / " + num(o.progress.total, 0);
        card.appendChild(bar);
      }
      if (o.message) {
        var msg = document.createElement("div");
        msg.className = "order-message" + (o.status === "failed" ? " warn" : "");
        msg.textContent = str(o.message);
        hide(msg);
        card.appendChild(msg);
      }
      var nm = o.names && typeof o.names === "object" ? o.names : null;
      if (nm && nm.mode === "all" && typeof nm.name === "string" && nm.name) {
        card.appendChild(line("order-names", "Named: " + nm.name));
      } else if (nm && nm.mode === "each" && Array.isArray(nm.names)) {
        var named = [];
        nm.names.forEach(function (v, i) { if (typeof v === "string" && v.trim()) named.push("Shulker " + (i + 1) + ": " + v); });
        if (named.length) {
          var nd = document.createElement("details");
          nd.className = "order-names";
          var openKey = "names:" + o.id;
          nd.open = orders.expanded.has(openKey);
          nd.addEventListener("toggle", function () {
            if (nd.open) orders.expanded.add(openKey);
            else orders.expanded.delete(openKey);
          });
          var ns = document.createElement("summary");
          ns.textContent = "Named each (" + named.length + ")";
          nd.appendChild(ns);
          named.forEach(function (t) { nd.appendChild(line("order-line", t)); });
          card.appendChild(nd);
        }
      }
      if (orders.retryErrors.has(o.id)) card.appendChild(line("note warn", "Retry refused: " + orders.retryErrors.get(o.id)));

      var lines = linesSummary(o);
      var details = document.createElement("details");
      details.className = "order-lines";
      details.open = orders.expanded.has(o.id);
      details.addEventListener("toggle", function () {
        if (details.open) orders.expanded.add(o.id);
        else orders.expanded.delete(o.id);
      });
      var summary = document.createElement("summary");
      summary.textContent = lines.slice(0, 3).join(", ") + (lines.length > 3 ? ", +" + (lines.length - 3) + " more" : "");
      details.appendChild(summary);
      lines.forEach(function (t) { details.appendChild(line("order-line", t)); });
      if (o.result && typeof o.result === "object" && (o.state === "PICKUP_READY" || o.state === "DELIVERED")) {
        var resultLabel = o.state === "PICKUP_READY" ? "Ready in " : "Delivered in ";
        details.appendChild(line("order-line note", resultLabel + plural(String(num(o.result.shulkers, 0)), "shulker")));
      }
      card.appendChild(details);
      frag.appendChild(card);
    });
    el.orderList.replaceChildren(frag);
  }

  document.addEventListener("visibilitychange", function () {
    if (!document.hidden && state.tab === "orders") scheduleOrdersPoll(0);
  });

  // ---------- dashboard: pages, bot picker ----------
  // Stash and Orders are the inventory view with its creative tabs; Bots, Settings and Console are dashboard pages

  var BOT_KEY = "wesort.bot";
  var PAGE_KEY = "wesort.page";
  var dash = {
    page: "stash",
    bots: [],
    botId: null,
    botsTimer: 0,
    // settings
    s: null,          // last GET settings reply
    sEtag: null,
    sBot: null,       // bot the form was built for
    sSchemaHash: null,
    fields: new Map(), // path -> field
    edits: new Map(),  // path -> new value
    jobs: new Map(),   // job id -> path
    sTimer: 0,
    jobTimer: 0,
    cBot: null,
    cRev: 0,
    entries: new Map(), // seq -> { data, el }
    cTimer: 0,
    history: [],
    histPos: -1
  };

  try {
    dash.botId = window.localStorage.getItem(BOT_KEY);
  } catch (e) {
    dash.botId = null;
  }

  function currentBot() {
    for (var i = 0; i < dash.bots.length; i++) if (dash.bots[i].botId === dash.botId) return dash.bots[i];
    return null;
  }

  function botName(b) {
    if (!str(b.player) && forcedStreamer()) {
      // Forced streamer mode: no player name and only an opaque id from the server; "Bot 1" reads better.
      var i = dash.bots.indexOf(b);
      return "Bot " + (i >= 0 ? i + 1 : 1);
    }
    return str(b.player) || str(b.botId);
  }

  function showPage(page) {
    if (!pageAllowed(page)) page = "stash";
    var inventory = page === "stash" || page === "orders";
    dash.page = page;
    el.inventory.hidden = !inventory;
    el.tabsWrap.hidden = !inventory;
    el.pageBots.hidden = page !== "bots";
    el.pageSettings.hidden = page !== "settings";
    el.pageActivity.hidden = page !== "activity";
    var navs = el.dashNav.querySelectorAll(".nav-btn");
    for (var i = 0; i < navs.length; i++) navs[i].classList.toggle("selected", navs[i].dataset.page === page);
    hideTooltip();
    if (page === "orders") selectTab("orders");
    else if (page === "stash" && state.tab === "orders") selectTab("all");
    if (page === "bots") renderDashBots();
    window.clearTimeout(dash.cTimer);
    settingsForm.stop();
    if (page === "settings") {
      settingsForm.load(true);
      if (me.owner) loadUsers();
      $("kit-admin").hidden = !me.owner || forcedStreamer();
      $("guild-admin").hidden = !me.owner;
      $("physical-admin").hidden = !me.owner || forcedStreamer();
      if (!$("physical-admin").hidden) loadPhysicalOperations();
    }

    window.clearTimeout(act.timer);
    if (page === "activity") loadActivity();
  }

  el.dashNav.addEventListener("click", function (e) {
    var b = e.target.closest ? e.target.closest(".nav-btn") : null;
    if (b) {
      // Remembered per browser: the page to open next time.
      try {
        window.localStorage.setItem(PAGE_KEY, b.dataset.page);
      } catch (err) {
        // not remembered
      }
      showPage(b.dataset.page);
    }
  });

  function pollBots() {
    window.clearTimeout(dash.botsTimer);
    dash.botsTimer = window.setTimeout(pollBots, 10000);
    if (auth.blocked) return;
    // Without "activity" the server refuses the bot list: no bots for this user.
    var request = can("activity") ? api("/api/v1/bots") : Promise.resolve([]);
    request.then(function (list) {
      dash.bots = Array.isArray(list) ? list.filter(function (b) { return b && typeof b === "object" && typeof b.botId === "string"; }) : [];
      if (!currentBot() && dash.bots.length) dash.botId = dash.bots[0].botId;
      var first = !dash.botsLoaded;
      dash.botsLoaded = true;
      if (first && !state.unreachable) maybeAutoOpenLink();
      renderBotSelect();
      if (dash.page === "bots") renderDashBots();
      updateDashTitles();
      updateActivityNav(first);

    }, function () {});
  }

  function renderBotSelect() {
    var frag = document.createDocumentFragment();
    if (!dash.bots.length) {
      var none = document.createElement("option");
      none.value = "";
      none.textContent = "(no bots)";
      frag.appendChild(none);
    }
    dash.bots.forEach(function (b) {
      var o = document.createElement("option");
      o.value = b.botId;
      o.textContent = (b.online ? "● " : "○ ") + botName(b);
      if (str(b.summary) && !streamerOn()) o.title = str(b.summary);
      frag.appendChild(o);
    });
    el.botSelect.replaceChildren(frag);
    el.botSelect.value = dash.botId || "";
    var cb = currentBot();
    el.botSelect.title = cb && str(cb.summary) && !streamerOn() ? "Now: " + str(cb.summary) : "";
    el.botSelect.disabled = dash.bots.length <= 1;
  }

  function selectBot(id) {
    if (!id || id === dash.botId) return;
    dash.botId = id;
    try {
      window.localStorage.setItem(BOT_KEY, id);
    } catch (e) {
      // not remembered
    }
    el.botSelect.value = id;
    updateDashTitles();
    updateActivityNav(false);
    if (dash.page === "activity") loadActivity();
    if (dash.page === "settings") settingsForm.load(true);

    if (dash.page === "bots") renderDashBots();
  }

  el.botSelect.addEventListener("change", function () { selectBot(el.botSelect.value); });

  function updateDashTitles() {
    var b = currentBot();
    el.settingsTitle.textContent = "Settings" + (b ? " · " + botName(b) : "");
    el.activityTitle.textContent = "Activity" + (b ? " · " + botName(b) : "");
  }

  function offlineText(b) {
    return b ? botName(b) + " is offline (last seen " + ago(num(b.lastSeen, null)) + "). " : "No bot selected. ";
  }

  // ---------- dashboard: Bots page ----------

  function renderDashBots() {
    var frag = document.createDocumentFragment();
    if (!dash.bots.length) {
      frag.appendChild(line("note", "No warehouse agents connected yet."));
    }
    dash.bots.forEach(function (b) {
      var card = document.createElement("div");
      card.className = "dash-bot" + (b.botId === dash.botId ? " selected" : "");
      var head = document.createElement("div");
      head.className = "dash-bot-head";
      var dot = document.createElement("span");
      setDot(dot, !!b.online);
      dot.classList.add("only-desktop");
      head.appendChild(dot);
      var name = document.createElement("span");
      name.className = "dash-bot-name";
      name.textContent = botName(b);
      head.appendChild(name);
      var chip = document.createElement("span");
      chip.className = "status-chip " + (b.online ? "ok" : "off");
      chip.textContent = b.online ? "Online" : "Offline";
      head.appendChild(chip);
      head.appendChild(line("note", "last seen " + ago(num(b.lastSeen, null))));
      card.appendChild(head);
      if (str(b.summary)) {
        var doing = line("dash-bot-doing", "Now: ");
        doing.appendChild(hiddenSpan(str(b.summary)));
        card.appendChild(doing);
      }
      var plugin = b.plugin && typeof b.plugin === "object" ? b.plugin : {};
      card.appendChild(line("note dash-bot-meta", "Agent " + str(plugin.id) + " " + str(plugin.version) + " · id " + str(b.botId)));
      var stashes = Array.isArray(b.stashes) ? b.stashes : [];
      var st = document.createElement("div");
      st.className = "dash-bot-stashes";
      st.appendChild(document.createTextNode(stashes.length ? "Stashes: " : "No stash linked to this player."));
      stashes.forEach(function (s) {
        var link = document.createElement("button");
        link.type = "button";
        link.className = "crumb";
        link.textContent = str(s.stash) + " @ ";
        link.appendChild(hiddenSpan(str(s.server)));
        link.addEventListener("click", function () {
          selectStash(stashKey(s));
          showPage("stash");
        });
        st.appendChild(link);
        st.appendChild(document.createTextNode(" "));
      });
      card.appendChild(st);
      var actions = document.createElement("div");
      actions.className = "dash-bot-actions";
      var caps = Array.isArray(b.caps) ? b.caps : [];
      [["activity", "Activity"], ["settings", "Settings"]].forEach(function (p) {
        if (caps.indexOf(p[0]) < 0 || !pageAllowed(p[0])) return;
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "mc-button small";
        btn.textContent = p[1];
        btn.addEventListener("click", function () {
          selectBot(b.botId);
          showPage(p[0]);
        });
        actions.appendChild(btn);
      });
      card.appendChild(actions);
      frag.appendChild(card);
    });
    el.dashBots.replaceChildren(frag);
    bots.body = el.stashBots;
    renderBots();
  }

  // Manager owns definitions. A browser edit never asserts physical inventory or starts local client work.
  var kitEditor = { server: "", revision: 0, fingerprint: "", entries: [], variants: [], destinations: [] };
  function kitMessage(message) { $("kit-status").textContent = message; }
  function kitFailure(error) { kitMessage(error.status === 409 ? "This kit changed. Reload before editing again." : str(error.body && error.body.error) || "Kit request failed. Reload and try again."); }
  function editKit(entry) {
    var template = entry && entry.template;
    kitEditor.revision = entry ? entry.revision : 0;
    kitEditor.fingerprint = template ? template.fingerprint : "";
    $("kit-name").value = template ? template.name : "";
    $("kit-name").readOnly = !!template;
    $("kit-version").textContent = entry ? "Version " + entry.revision + " · " + template.fingerprint : "New guild template";
    $("kit-delete").disabled = !entry;
    $("kit-pack").hidden = !entry;
    var holder = $("kit-slots"); holder.replaceChildren();
    for (let slot = 0; slot < 27; slot++) {
      var row = document.createElement("label"); row.className = "kit-slot";
      var caption = document.createElement("span"); caption.textContent = "Slot " + (slot + 1); row.appendChild(caption);
      let select = document.createElement("select"); select.setAttribute("aria-label", "Item in slot " + (slot + 1));
      var empty = document.createElement("option"); empty.value = ""; empty.textContent = "Empty"; select.appendChild(empty);
      kitEditor.variants.forEach(function (variant) {
        var option = document.createElement("option"); option.value = variant.fingerprint;
        option.textContent = variant.id + " · " + variant.fingerprint.slice(0, 12);
        option.title = variant.components; select.appendChild(option);
      });
      let quantity = document.createElement("input"); quantity.type = "number"; quantity.min = "1"; quantity.max = "64"; quantity.value = "1";
      quantity.setAttribute("aria-label", "Count in slot " + (slot + 1));
      var detail = document.createElement("details"); var summary = document.createElement("summary"); summary.textContent = "Exact components";
      let metadata = document.createElement("pre"); detail.append(summary, metadata);
      if (template) template.slots.forEach(function (saved) {
        if (saved[0] !== slot) return;
        quantity.value = String(saved[2]);
      });
      if (entry && entry.slotFingerprints && entry.slotFingerprints[slot]) select.value = entry.slotFingerprints[slot];
      function updateKitSlot() {
        var variant = kitEditor.variants.filter(function (value) { return value.fingerprint === select.value; })[0];
        quantity.disabled = !variant; quantity.max = String(variant ? variant.maxStack : 64);
        metadata.textContent = variant ? variant.components : "Empty";
      }
      select.addEventListener("change", updateKitSlot); updateKitSlot();
      row.append(select, quantity, detail); holder.appendChild(row);
    }
    $("kit-editor").hidden = false;
  }
  function loadKitEditor() {
    var server = $("kit-server").value.trim(); if (!server) { kitMessage("Enter the Minecraft server identity."); return; }
    kitMessage("Loading guild templates…");
    api("/api/v1/kits/editor?server=" + encodeURIComponent(server)).then(function (body) {
      kitEditor.server = server; kitEditor.entries = body.templates; kitEditor.variants = body.variants; kitEditor.destinations = body.destinations;
      var destinations = $("kit-destination"); destinations.replaceChildren();
      body.destinations.forEach(function (destination) {
        var option = document.createElement("option"); option.value = destination.id;
        option.textContent = destination.stash + " · " + destination.dimension + " " + JSON.stringify(destination.parts); destinations.appendChild(option);
      });
      var list = $("kit-list"); list.replaceChildren();
      body.templates.forEach(function (entry) {
        var button = document.createElement("button"); button.type = "button"; button.className = "mc-button";
        button.textContent = entry.template.name + " · v" + entry.revision;
        button.addEventListener("click", function () { editKit(entry); }); list.appendChild(button);
      });
      editKit(null); kitMessage(body.templates.length + " guild templates. Select a kit to edit, or create a new one.");
    }).catch(kitFailure);
  }
  $("kit-load").addEventListener("click", loadKitEditor);
  $("kit-new").addEventListener("click", function () { editKit(null); });
  $("kit-editor").addEventListener("submit", function (event) {
    event.preventDefault(); var layout = [];
    Array.prototype.forEach.call($("kit-slots").children, function (row, slot) {
      var variant = row.querySelector("select").value;
      if (variant) layout.push({ slot: slot, variant: variant, count: Number(row.querySelector("input").value) });
    });
    api("/api/v1/kits", { method: "POST", body: { action: "publish", server: kitEditor.server, name: $("kit-name").value, layout: layout, expectedRevision: kitEditor.revision } }).then(function () { loadKitEditor(); }).catch(kitFailure);
  });
  $("kit-delete").addEventListener("click", function () {
    if (!kitEditor.revision) return;
    api("/api/v1/kits", { method: "POST", body: { action: "delete", server: kitEditor.server, name: $("kit-name").value, fingerprint: kitEditor.fingerprint, expectedRevision: kitEditor.revision } }).then(function () { loadKitEditor(); }).catch(kitFailure);
  });

  function loadKitJobs() {
    if (!kitEditor.server) return;
    api("/api/v1/kits/jobs?server=" + encodeURIComponent(kitEditor.server)).then(function (body) {
      var holder = $("kit-jobs"); holder.replaceChildren();
      body.jobs.forEach(function (job) {
        var row = document.createElement("p"); row.textContent = job.name + " · v" + job.revision + " · " + job.state + " · " + job.id + (job.message ? " · " + job.message : ""); holder.appendChild(row);
        if (job.agentEstimate) {
          var estimate = job.agentEstimate; var detail = document.createElement("p"); detail.className = "note";
          detail.textContent = "Agent observation: " + estimate.built + " finished; " + (estimate.exact ? "" : "at least ") + estimate.possible + " buildable. " + (estimate.shortage || "") + " Observed " + new Date(estimate.at).toLocaleString(); holder.appendChild(detail);
        }
      });
    }).catch(kitFailure);
  }
  $("kit-jobs-refresh").addEventListener("click", loadKitJobs);
  $("kit-pack").addEventListener("submit", function (event) {
    event.preventDefault();
    var destination = kitEditor.destinations.filter(function (value) { return value.id === $("kit-destination").value; })[0];
    if (!kitEditor.revision || !destination) { kitMessage("Select a saved kit version and a fresh destination."); return; }
    api("/api/v1/kits/jobs", { method: "POST", body: {
      server: kitEditor.server, stash: destination.stash, destination: destination.id,
      name: $("kit-name").value, revision: kitEditor.revision, fingerprint: kitEditor.fingerprint,
      workspace: { dimension: $("kit-dimension").value, x: Number($("kit-work-x").value), y: Number($("kit-work-y").value), z: Number($("kit-work-z").value) }
    } }).then(function () { kitMessage("Packing job assigned. Completion requires fresh destination verification."); loadKitJobs(); }).catch(kitFailure);
  });

  var guildRevision = 0;
  function showGuildProfile(profile) {
    if (typeof profile.name !== "string" || !Number.isSafeInteger(profile.revision)) return;
    guildRevision = profile.revision;
    $("guild-heading").textContent = profile.name;
    $("guild-stock-title").textContent = profile.name + " · Shared stock";
    var description = $("guild-description-view");
    description.textContent = profile.description || "";
    description.hidden = !profile.description;
    $("guild-name").value = profile.name;
    $("guild-description").value = profile.description || "";
    $("guild-approval").value = profile.approvalPolicy || "DIRECT";
    $("guild-save").disabled = !me.owner;
  }

  function loadGuildProfile() {
    return api("/api/v1/guild").then(showGuildProfile).catch(function (error) {
      $("guild-status").textContent = error.message;
    });
  }

  $("guild-form").addEventListener("submit", function (event) {
    event.preventDefault();
    if (!me.owner || !guildRevision) return;
    var save = $("guild-save");
    save.disabled = true;
    api("/api/v1/guild", {method: "POST", body: {
      name: $("guild-name").value, description: $("guild-description").value,
      approvalPolicy: $("guild-approval").value, expectedRevision: guildRevision
    }}).then(function (profile) {
      showGuildProfile(profile);
      $("guild-status").textContent = "Guild profile saved.";
    }).catch(function (error) {
      $("guild-status").textContent = error.message;
      if (error.status === 409) loadGuildProfile();
      else save.disabled = false;
    });
  });

  function loadPhysicalOperations() {
    var status = $("physical-status");
    var holder = $("physical-list");
    holder.replaceChildren();
    api("/api/v1/physical").then(function (reply) {
      if ($("physical-admin").hidden || forcedStreamer()) return;
      status.textContent = reply.operations.length ? "" : "No physical operations recorded.";
      reply.operations.forEach(function (op) {
        var row = document.createElement("div");
        row.className = "inset";
        var title = document.createElement("p");
        title.textContent = op.kind + " · " + op.managerState + " · " + (op.interruptedPhase || op.phase);
        var detail = document.createElement("p");
        detail.textContent = "Operation " + op.id + (op.orderId ? " · Order " + op.orderId : "") + (op.jobId ? " · Kit job " + op.jobId : "");
        row.append(title, detail);
        op.possibleOrphans.forEach(function (orphan) {
          var location = document.createElement("p");
          location.textContent = "Possible orphaned shulker · ";
          location.appendChild(hiddenSpan(op.server + " · " + op.dimension + " · " + [orphan.x, orphan.y, orphan.z].join(", ")));
          row.appendChild(location);
        });
        if (op.inspectionState) {
          var inspection = document.createElement("p");
          inspection.textContent = "Inspection " + op.inspectionState + " " + (op.inspectionDetail || "");
          row.appendChild(inspection);
        }
        if (op.managerState === "NEEDS_RECONCILIATION") {
          var note = document.createElement("p");
          note.textContent = "Automation is paused. Bring the original agent within reach of the recorded source to request a read-only inspection.";
          row.appendChild(note);
          var button = document.createElement("button");
          button.type = "button";
          button.className = "mc-button";
          button.textContent = "Request inspection";
          button.disabled = op.inspectionState === "REQUESTED";
          button.addEventListener("click", function () {
            button.disabled = true;
            api("/api/v1/physical/inspect", { method: "POST", body: {
              operation: op.id, revision: op.revision, checksum: op.checksum
            } }).then(loadPhysicalOperations).catch(function (error) {
              status.textContent = error.message;
              button.disabled = false;
            });
          });
          row.appendChild(button);
        }
        holder.appendChild(row);
      });
    }).catch(function (error) { status.textContent = error.message; });
  }
  $("physical-refresh").addEventListener("click", loadPhysicalOperations);

  // ---------- dashboard: settings forms ----------
  // tree can have thousands of settings, so the Proxy page only renders the selected section (or the search hits).
  // Edits are kept by path, so they survive switching sections; Save sends them all in one batch.

  function jsonEq(a, b) {
    return JSON.stringify(a === undefined ? null : a) === JSON.stringify(b === undefined ? null : b);
  }

  function fmtValue(v) {
    if (v === null || v === undefined) return "(none)";
    if (Array.isArray(v)) return v.length ? v.join(", ") : "(empty)";
    if (v === true) return "ON";
    if (v === false) return "OFF";
    return String(v);
  }

  function lastSegment(path) {
    var i = path.lastIndexOf(".");
    return i < 0 ? path : path.slice(i + 1);
  }

  /**
   * opts: { page, scope() -> scope, sectioned, sudo(scope) -> bool, els: { title, status, save, revert, offline, body,
   * nav?, search?, scopeSelect? }, titleText }
   */
  function makeForm(opts) {
    var F = {
      s: null, etag: null, bot: null, scope: null, schemaHash: null,
      fields: new Map(),   // rendered fields only: path -> field
      edits: new Map(),    // path -> new value (undefined = invalid)
      jobs: new Map(),     // job id -> { path, sent }
      notes: new Map(),    // path -> { kind, text } shown next to the field (kept while not rendered)
      busy: new Set(),     // paths with a job in flight
      timer: 0, jobTimer: 0,
      leaves: [], byPath: new Map(), sections: [], section: null, query: ""
    };
    var E = opts.els;

    function active() {
      return dash.page === opts.page;
    }

    /** The user's permissions allow reading but not saving (the server refuses a save anyway). */
    function readOnly() {
      return !!(opts.readOnly && opts.readOnly());
    }

    function serverValue(path) {
      var v = F.s && F.s.values ? F.s.values[path] : undefined;
      return v === undefined ? null : v;
    }

    function online() {
      var b = currentBot();
      return F.s ? !!F.s.online : !!(b && b.online);
    }

    function reset() {
      F.s = null;
      F.etag = null;
      F.schemaHash = null;
      F.edits.clear();
      F.fields.clear();
      F.notes.clear();
      F.busy.clear();
      F.jobs.clear();
      F.leaves = [];
      F.byPath = new Map();
      F.sections = [];
      F.section = null;
      E.body.replaceChildren();
      if (E.nav) E.nav.replaceChildren();
    }

    function load(force) {
      window.clearTimeout(F.timer);
      if (!active()) return;
      var b = currentBot();
      var scope = opts.scope();
      if (F.bot !== dash.botId || F.scope !== scope) {
        reset();
        F.bot = dash.botId;
        F.scope = scope;
      }
      if (force) F.etag = null;
      var denied = opts.denied ? opts.denied() : "";
      if (denied) {
        // Not polled: a permission change reloads the page (applyPerms).
        E.body.replaceChildren(line("note", denied));
        if (E.nav) E.nav.replaceChildren();
        E.offline.hidden = true;
        updateBar();
        return;
      }
      if (b && opts.cap && !hasCap(b, opts.cap)) {
        E.body.replaceChildren(capHint(opts.cap));
        if (E.nav) E.nav.replaceChildren();
        E.offline.hidden = true;
        updateBar();
        F.timer = window.setTimeout(function () { load(false); }, 5000);
        return;
      }
      if (!b || !scope) {
        E.body.replaceChildren(line("note", !b ? "No dashboard bot yet." : "This bot has no settings here."));
        E.offline.hidden = true;
        updateBar();
        F.timer = window.setTimeout(function () { load(false); }, 5000);
        return;
      }
      var botId = b.botId;
      var headers = {};
      if (F.etag) headers["If-None-Match"] = F.etag;
      fetch("/api/v1/bots/" + encodeURIComponent(botId) + "/settings?scope=" + encodeURIComponent(scope), {
        credentials: "same-origin", cache: "no-store", headers: headers
      }).then(function (res) {
        if (res.status === 401) {
          loginRequired();
          return null;
        }
        if (res.status === 304) return null;
        if (res.status === 403) {
          return res.json().then(function (body) { return body; }, function () { return null; }).then(function (body) {
            if (body && body.mustChange === true) {
              mustChangeRequired();
            } else if (body && typeof body.permission === "string") {
              // The user's permissions don't cover these settings (they may have just changed).
              E.body.replaceChildren(line("note", sentence(str(body.error)) || "Your account can't see these settings."));
              permsStale();
            } else {
              // Sudo mode needed to see these settings: offer the password prompt instead of polling.
              E.body.replaceChildren(unlockBox("Confirm your password to manage agent settings.", function () { load(true); }));
            }
            if (E.nav) E.nav.replaceChildren();
            return { locked: true };
          });
        }
        return res.json().then(function (body) { return { status: res.status, body: body, etag: res.headers.get("ETag") }; });
      }).then(function (r) {
        if (r && r.locked) {
          F.locked = true;
          return;
        }
        F.locked = false;
        if (!r || botId !== dash.botId || scope !== opts.scope() || !active()) return;
        if (r.status === 200) {
          F.s = r.body;
          F.etag = r.etag;
          apply();
        } else if (r.status === 404) {
          F.s = null;
          E.body.replaceChildren(line("note", r.body && r.body.loading ? "Asking the bot for these settings..." :
            r.body && r.body.online === false ? "The bot is offline; its settings show here once it is back." : "This bot hasn't sent these settings yet."));
        }
      }, function () {}).then(function () {
        updateOffline();
        if (active() && !F.locked) F.timer = window.setTimeout(function () { load(false); }, F.s ? 3000 : 1000);
      });
    }

    function updateOffline() {
      var b = currentBot();
      var on = online();
      E.offline.hidden = on || !b;
      E.offline.textContent = offlineText(b) + "Settings are read-only until it is back.";
      var ro = readOnly();
      F.fields.forEach(function (f) { f.setDisabled(!on || f.locked || ro || F.busy.has(f.node.path)); });
      updateBar();
    }

    // ----- the tree -----

    function collect(nodes, trail, top, out) {
      nodes.forEach(function (n) {
        if (!n || typeof n !== "object" || typeof n.path !== "string") return;
        if (n.type === "group") {
          var label = str(n.label) || n.path;
          collect(Array.isArray(n.children) ? n.children : [], top === null ? trail : trail.concat([label]), top === null ? label : top, out);
          return;
        }
        var section = str(n.section) || top || "Other";
        out.push({
          node: n, section: section, trail: trail,
          hay: (n.path + "\n" + str(n.label) + "\n" + str(n.help) + "\n" + section).toLowerCase()
        });
      });
    }

    function apply() {
      var s = F.s;
      if (!s || !Array.isArray(s.schema)) return;
      if (F.schemaHash !== s.schemaHash) {
        F.schemaHash = s.schemaHash;
        F.leaves = [];
        collect(s.schema, [], null, F.leaves);
        F.byPath = new Map();
        F.leaves.forEach(function (l) { F.byPath.set(l.node.path, l); });
        var seen = new Map();
        F.sections = [];
        F.leaves.forEach(function (l) {
          if (!seen.has(l.section)) {
            seen.set(l.section, { name: l.section, count: 0 });
            F.sections.push(seen.get(l.section));
          }
          seen.get(l.section).count++;
        });
        F.edits.forEach(function (v, path) {
          var l = F.byPath.get(path);
          if (!l || l.node.readOnly === true || l.node.secret === true) F.edits.delete(path);
        });
        if (!F.section || !seen.has(F.section)) F.section = F.sections.length ? F.sections[0].name : null;
        applyPending(false);
        render();
      } else {
        F.fields.forEach(function (f, path) {
          if (F.edits.has(path) || F.busy.has(path) || f.row.contains(document.activeElement)) return;
          f.set(serverValue(path));
        });
      }
    }

    function render() {
      F.fields.clear();
      if (opts.sectioned) renderNav();
      var leaves;
      if (!opts.sectioned) {
        leaves = F.leaves;
      } else if (F.query) {
        leaves = F.leaves.filter(function (l) { return l.hay.indexOf(F.query) >= 0; }).slice(0, 300);
      } else {
        leaves = F.leaves.filter(function (l) { return l.section === F.section; });
      }
      var frag = document.createDocumentFragment();
      if (readOnly()) frag.appendChild(line("note read-only-note", "Read only: your account can't change these settings."));
      if (opts.sectioned && !leaves.length) frag.appendChild(line("note", F.query ? "Nothing matches." : "No settings."));
      // Module switches first, highlighted ("enabled" toggles at the top of a section's tree)
      if (opts.sectioned && !F.query) {
        var minDepth = Infinity;
        leaves.forEach(function (l) { minDepth = Math.min(minDepth, l.trail.length); });
        var toggles = leaves.filter(function (l) {
          var seg = lastSegment(l.node.path).toLowerCase();
          return l.node.type === "bool" && (seg === "enabled" || seg === "enable") && l.trail.length === minDepth;
        });
        if (toggles.length) {
          var box = document.createElement("div");
          box.className = "module-toggles";
          toggles.forEach(function (l) { box.appendChild(buildField(l, "module-enable")); });
          frag.appendChild(box);
          leaves = leaves.filter(function (l) { return toggles.indexOf(l) < 0; });
        }
      }
      var sec = null;
      var groupKey = null;
      var holder = null;
      leaves.forEach(function (l) {
        var key = (F.query || !opts.sectioned ? l.section + "\u0000" : "") + l.trail.join(" › ");
        if (!opts.sectioned && l.section !== sec) {
          sec = l.section;
          holder = document.createElement("section");
          holder.className = "inset settings-section";
          holder.appendChild(line("section-title", sec));
          frag.appendChild(holder);
          groupKey = null;
        }
        if (!holder) {
          holder = document.createElement("section");
          holder.className = "inset settings-section";
          frag.appendChild(holder);
        }
        if (key !== groupKey) {
          groupKey = key;
          var title = (F.query && opts.sectioned ? l.section + (l.trail.length ? " › " : "") : "") + l.trail.join(" › ");
          if (title) holder.appendChild(line("settings-group-title", title));
        }
        holder.appendChild(buildField(l, ""));
      });
      E.body.replaceChildren(frag);
      if (F.query && leaves.length === 300) E.body.appendChild(line("note", "Showing the first 300 matches. Type more to narrow it down."));
      updateOffline();
    }

    function renderNav() {
      if (!E.nav) return;
      var counts = new Map();
      if (F.query) F.leaves.forEach(function (l) { if (l.hay.indexOf(F.query) >= 0) counts.set(l.section, (counts.get(l.section) || 0) + 1); });
      var dirty = new Map();
      F.edits.forEach(function (v, path) {
        var l = F.byPath.get(path);
        if (l) dirty.set(l.section, (dirty.get(l.section) || 0) + 1);
      });
      var frag = document.createDocumentFragment();
      F.sections.forEach(function (sct) {
        if (F.query && !counts.has(sct.name)) return;
        var b = document.createElement("button");
        b.type = "button";
        b.className = "section-item" + (!F.query && sct.name === F.section ? " selected" : "");
        var name = document.createElement("span");
        name.className = "section-name";
        name.textContent = sct.name;
        b.appendChild(name);
        var n = document.createElement("span");
        n.className = "section-count";
        n.textContent = String(F.query ? counts.get(sct.name) : sct.count);
        b.appendChild(n);
        if (dirty.get(sct.name)) {
          var d = document.createElement("span");
          d.className = "section-dirty";
          d.textContent = "●" + dirty.get(sct.name);
          d.title = dirty.get(sct.name) + " unsaved";
          b.appendChild(d);
        }
        b.addEventListener("click", function () {
          F.section = sct.name;
          if (F.query) {
            F.query = "";
            if (E.search) E.search.value = "";
          }
          render();
          E.body.scrollTop = 0;
        });
        frag.appendChild(b);
      });
      E.nav.replaceChildren(frag);
    }

    function buildField(l, extraClass) {
      var node = l.node;
      var row = document.createElement("div");
      row.className = "setting" + (extraClass ? " " + extraClass : "");
      var labelBox = document.createElement("div");
      labelBox.className = "setting-label";
      labelBox.appendChild(line("setting-name", str(node.label) || node.path));
      if (str(node.help)) labelBox.appendChild(line("setting-help", str(node.help)));
      if (opts.sectioned) labelBox.appendChild(line("setting-path", node.path));
      row.appendChild(labelBox);
      var box = document.createElement("div");
      box.className = "setting-control";
      var status = document.createElement("div");
      status.className = "setting-status";
      var locked = node.readOnly === true || node.secret === true;
      var f = { node: node, row: row, status: status, locked: locked };
      if (node.secret === true) {
        var badge = document.createElement("span");
        badge.className = "secret-badge " + (node.set ? "set" : "unset");
        badge.textContent = node.set ? "secret · set" : "secret · not set";
        box.appendChild(badge);
        f.set = function () {};
        f.get = function () { return { ok: true, value: null }; };
        f.setDisabled = function () {};
      } else {
        makeControl(f, box, function () { edited(f); }, serverValue);
        f.set(F.edits.has(node.path) && F.edits.get(node.path) !== undefined ? F.edits.get(node.path) : serverValue(node.path));
        if (F.edits.has(node.path)) row.classList.add("dirty");
      }
      if (node.secret === true || sensitivePath(node.path)) hide(box);
      if (locked && str(node.reason) && node.secret !== true) box.appendChild(line("setting-reason", str(node.reason)));
      if (locked) row.classList.add("locked");
      row.appendChild(box);
      row.appendChild(status);
      var note = F.notes.get(node.path);
      if (F.busy.has(node.path)) note = { kind: "busy", text: "applying" };
      if (note) setNote(f, note.kind, note.text);
      F.fields.set(node.path, f);
      return row;
    }

    function setNote(f, kind, text) {
      f.status.className = "setting-status" + (kind ? " " + kind : "");
      f.status.textContent = text || "";
    }

    function note(path, kind, text) {
      if (text) F.notes.set(path, { kind: kind, text: text });
      else F.notes.delete(path);
      var f = F.fields.get(path);
      if (f) setNote(f, kind, text);
    }

    function edited(f) {
      var path = f.node.path;
      var got = f.get();
      if (!got.ok) {
        F.edits.set(path, undefined);
        f.row.classList.add("dirty", "invalid");
        note(path, "error", got.error);
      } else {
        f.row.classList.remove("invalid");
        if (jsonEq(got.value, serverValue(path))) {
          F.edits.delete(path);
          f.row.classList.remove("dirty");
        } else {
          F.edits.set(path, got.value);
          f.row.classList.add("dirty");
        }
        note(path, "", "");
      }
      updateBar();
      if (opts.sectioned) renderNav();
    }

    function updateBar() {
      var n = F.edits.size;
      var invalid = false;
      F.edits.forEach(function (v) { if (v === undefined) invalid = true; });
      var busy = F.jobs.size > 0;
      E.save.hidden = readOnly();
      E.revert.hidden = readOnly();
      E.save.disabled = !n || invalid || !online() || busy;
      E.revert.disabled = !n || busy;
      E.save.textContent = n ? "Save (" + n + ")" : "Save";
      E.status.textContent = busy ? "Applying..." : invalid ? "Fix the marked values first." : n ? n + " unsaved change" + (n === 1 ? "" : "s") : "";
    }

    E.revert.addEventListener("click", function () {
      F.edits.forEach(function (v, path) {
        note(path, "", "");
        var f = F.fields.get(path);
        if (!f) return;
        f.set(serverValue(path));
        f.row.classList.remove("dirty", "invalid");
      });
      F.edits.clear();
      updateBar();
      if (opts.sectioned) renderNav();
    });

    E.save.addEventListener("click", function () {
      var b = currentBot();
      if (!b || !F.edits.size) return;
      var changes = [];
      F.edits.forEach(function (v, path) { changes.push({ path: path, value: v, expect: serverValue(path) }); });
      var botId = b.botId;
      var scope = F.scope;
      E.save.disabled = true;
      E.status.textContent = "Saving...";
      var post = function () {
        return api("/api/v1/bots/" + encodeURIComponent(botId) + "/settings", { method: "POST", body: { scope: scope, changes: changes } });
      };
      (opts.sudo(scope) ? withSudo(post) : post()).then(function (r) {
        (r && Array.isArray(r.jobs) ? r.jobs : []).forEach(function (j) {
          F.jobs.set(j.id, { path: j.path, sent: F.edits.get(j.path) });
          F.busy.add(j.path);
          note(j.path, "busy", "applying");
          var f = F.fields.get(j.path);
          if (f) f.setDisabled(true);
        });
        updateBar();
        pollJobs(botId);
      }, function (e) {
        var body = e && e.body;
        if (body && body.error === "cancelled") {
          updateBar();
          return;
        }
        if (e && e.status === 409) {
          E.status.textContent = body && body.error === "bot offline" ? "The bot is offline: nothing was changed." : (body && body.error) || "Refused.";
          load(true);
        } else if (body && body.path) {
          note(body.path, "error", body.error);
          var l = F.byPath.get(body.path);
          if (opts.sectioned && l && !F.fields.has(body.path)) {
            F.section = l.section;
            F.query = "";
            if (E.search) E.search.value = "";
            render();
          }
          E.status.textContent = "Not saved: fix the marked value.";
        } else {
          E.status.textContent = "Not saved: " + ((body && body.error) || "HTTP " + (e && e.status));
        }
        updateBar();
      });
    });

    function pollJobs(botId) {
      window.clearTimeout(F.jobTimer);
      if (!F.jobs.size) return;
      var ids = Array.from(F.jobs.keys());
      api("/api/v1/bots/" + encodeURIComponent(botId) + "/jobs?ids=" + encodeURIComponent(ids.join(","))).then(function (list) {
        var conflict = false;
        (Array.isArray(list) ? list : []).forEach(function (j) {
          var job = F.jobs.get(j.id);
          if (!job || j.status === "queued" || j.status === "sent") return;
          F.jobs.delete(j.id);
          F.busy.delete(job.path);
          var f = F.fields.get(job.path);
          var value = j.value === undefined ? null : j.value;
          if (j.status === "done" || (j.status === "error" && j.error === "conflict")) {
            if (F.s && F.s.values) F.s.values[job.path] = value;
          }
          if (j.status === "done") {
            if (jsonEq(F.edits.get(job.path), job.sent)) {
              F.edits.delete(job.path);
              if (f) {
                f.row.classList.remove("dirty");
                f.set(value);
              }
            }
            note(job.path, "ok", "applied ✓ " + fmtValue(value));
          } else if (j.status === "error" && j.error === "conflict") {
            conflict = true;
            F.edits.delete(job.path);
            if (f) {
              f.row.classList.remove("dirty");
              f.set(value);
            }
            note(job.path, "error", "changed elsewhere meanwhile; reloaded (now " + fmtValue(value) + ")");
          } else if (j.status === "error") {
            note(job.path, "error", str(j.error) || "failed");
          } else {
            note(job.path, "error", j.status === "expired" ? "the bot didn't pick it up" : "no answer from the bot");
          }
          if (f) f.setDisabled(!online() || f.locked || readOnly());
        });
        updateBar();
        if (opts.sectioned) renderNav();
        if (conflict) load(true);
        if (F.jobs.size) F.jobTimer = window.setTimeout(function () { pollJobs(botId); }, 700);
      }, function () {
        F.jobTimer = window.setTimeout(function () { pollJobs(botId); }, 2000);
      });
    }

    if (E.search) {
      var searchTimer = 0;
      E.search.addEventListener("input", function () {
        window.clearTimeout(searchTimer);
        searchTimer = window.setTimeout(function () {
          F.query = E.search.value.trim().toLowerCase();
          render();
        }, 150);
      });
    }

    /** Open a section by name (case-insensitive) now or once the schema is in. */
    function jump(name) {
      F.pending = String(name || "").toLowerCase();
      F.query = "";
      if (E.search) E.search.value = "";
      if (F.sections.length) applyPending(true);
    }

    function applyPending(rerender) {
      if (!F.pending) return;
      for (var i = 0; i < F.sections.length; i++) {
        if (F.sections[i].name.toLowerCase() === F.pending) {
          F.section = F.sections[i].name;
          break;
        }
      }
      F.pending = null;
      if (rerender) render();
    }

    return { load: load, state: F, jump: jump, stop: function () { window.clearTimeout(F.timer); } };
  }

  /**
   * Builds the control for a node into box and gives f set(v), get() -> {ok, value, error}, setDisabled(bool).
   * onEdit runs on every change; serverValue(path) is the value the server has.
   */
  /** Settings whose value is a server address, port, URL or IP: blurred in streamer mode. Matched on whole
   *  words of the last path segment ("port", "serverPort", "managerUrl"), never inside one ("autoReport"). */
  var SENSITIVE_SECTION = /(^|\.)(server\.bind|client\.server|connectionProxy)(\.|$)/i;
  var SENSITIVE_WORD = /^(address|addresses|host|hostname|ip|ips|port|url|urls|uri|domain|endpoint)$/i;
  var SENSITIVE_SUFFIX = /[a-z0-9](Address|Addresses|Host|Hostname|Ip|IP|Ips|IPs|Port|Url|URL|Urls|Uri|URI|Domain|Endpoint)$/;

  function sensitivePath(path) {
    path = String(path || "");
    var last = path.slice(path.lastIndexOf(".") + 1);
    return SENSITIVE_SECTION.test(path) || SENSITIVE_WORD.test(last) || SENSITIVE_SUFFIX.test(last);
  }

  function makeControl(f, box, onEdit, serverValue) {
    var node = f.node;
    var type = node.type;
    var nullable = node.nullable === true;
    if (type === "bool") {
      var t = document.createElement("button");
      t.type = "button";
      t.className = "mc-button toggle";
      t.setAttribute("role", "switch");
      t.setAttribute("aria-label", str(node.label) || node.path);
      var tText = document.createElement("span");
      tText.className = "toggle-text";
      t.appendChild(tText);
      var cur = false;
      var isNull = false;
      var show = function () {
        tText.textContent = isNull ? "(none)" : cur ? "ON" : "OFF";
        t.classList.toggle("on", !isNull && cur);
        t.classList.toggle("is-null", isNull);
        t.setAttribute("aria-checked", !isNull && cur ? "true" : "false");
      };
      t.addEventListener("click", function () {
        cur = isNull ? true : !cur;
        isNull = false;
        show();
        onEdit();
      });
      box.appendChild(t);
      f.set = function (v) { isNull = v === null || v === undefined; cur = v === true; show(); };
      f.get = function () { return { ok: true, value: isNull ? null : cur }; };
      f.setDisabled = function (d) { t.disabled = d; };
      return;
    }
    if (type === "enum") {
      var sel = document.createElement("select");
      sel.className = "mc-select";
      if (nullable) {
        var none = document.createElement("option");
        none.value = "\u0000";
        none.textContent = "(none)";
        sel.appendChild(none);
      }
      (Array.isArray(node.options) ? node.options : []).forEach(function (o) {
        var opt = document.createElement("option");
        opt.value = String(o);
        opt.textContent = String(o);
        sel.appendChild(opt);
      });
      sel.addEventListener("change", onEdit);
      box.appendChild(sel);
      f.set = function (v) { sel.value = v === null || v === undefined ? "\u0000" : String(v); };
      f.get = function () { return { ok: true, value: sel.value === "\u0000" ? null : sel.value }; };
      f.setDisabled = function (d) { sel.disabled = d; };
      return;
    }
    if (type === "stringList") {
      chipEditor(f, box, onEdit, serverValue);
      return;
    }
    // Numbers and strings: a text field, plus "unset" for nullable ones.
    var input = document.createElement("input");
    input.className = "mc-input setting-input";
    var numeric = type === "int" || type === "long" || type === "double";
    if (numeric) {
      input.type = type === "long" ? "text" : "number";
      input.inputMode = type === "double" ? "decimal" : "numeric";
      if (type !== "long") {
        if (typeof node.min === "number") input.min = String(node.min);
        if (typeof node.max === "number") input.max = String(node.max);
        input.step = type === "double" ? "any" : "1";
      }
      var range = [];
      if (typeof node.min === "number") range.push("min " + node.min);
      if (typeof node.max === "number" && Math.abs(node.max) < 1e15) range.push("max " + node.max);
      if (range.length) input.title = range.join(", ");
    } else {
      input.type = "text";
      input.maxLength = 1000;
    }
    input.spellcheck = false;
    input.addEventListener("input", onEdit);
    box.appendChild(input);
    var unset = null;
    if (nullable) {
      var lab = document.createElement("label");
      lab.className = "unset";
      unset = document.createElement("input");
      unset.type = "checkbox";
      lab.appendChild(unset);
      lab.appendChild(document.createTextNode(" unset"));
      unset.addEventListener("change", function () {
        input.disabled = unset.checked || f.disabled;
        onEdit();
      });
      box.appendChild(lab);
    }
    f.set = function (v) {
      var isNull = v === null || v === undefined;
      if (unset) unset.checked = isNull;
      input.value = isNull ? "" : String(v);
      input.disabled = !!f.disabled || (unset ? unset.checked : false);
    };
    f.get = function () {
      if (unset && unset.checked) return { ok: true, value: null };
      var text = input.value.trim();
      if (!numeric) return { ok: true, value: input.value };
      if (text === "") return nullable ? { ok: true, value: null } : { ok: false, error: "enter a number" };
      var n;
      if (type === "double") {
        n = Number(text);
        if (!isFinite(n)) return { ok: false, error: "not a number" };
      } else {
        if (!/^-?\d+$/.test(text)) return { ok: false, error: "a whole number" };
        n = Number(text);
        if (!Number.isSafeInteger(n)) return { ok: false, error: "too large for this page" };
      }
      if (typeof node.min === "number" && n < node.min) return { ok: false, error: "at least " + node.min };
      if (typeof node.max === "number" && n > node.max) return { ok: false, error: "at most " + node.max };
      return { ok: true, value: n };
    };
    f.setDisabled = function (d) {
      f.disabled = d;
      input.disabled = d || (unset ? unset.checked : false);
      if (unset) unset.disabled = d;
    };
  }

  /** A list of strings as chips; Enter or comma adds the typed text, × removes one, Backspace on empty the last. */
  function chipEditor(f, box, onEdit, serverValue) {
    var wrap = document.createElement("div");
    wrap.className = "chips";
    var input = document.createElement("input");
    input.className = "chip-input";
    input.type = "text";
    input.maxLength = 1000;
    input.placeholder = "add...";
    input.spellcheck = false;
    var items = [];
    var isNull = false;
    var render = function () {
      var frag = document.createDocumentFragment();
      items.forEach(function (text, i) {
        var chip = document.createElement("span");
        chip.className = "chip";
        chip.appendChild(document.createTextNode(text));
        var x = document.createElement("button");
        x.type = "button";
        x.className = "chip-x";
        x.textContent = "×";
        x.setAttribute("aria-label", "Remove " + text);
        x.disabled = !!f.disabled;
        x.addEventListener("click", function () {
          items.splice(i, 1);
          render();
          onEdit();
        });
        chip.appendChild(x);
        frag.appendChild(chip);
      });
      frag.appendChild(input);
      wrap.replaceChildren(frag);
    };
    var add = function () {
      var t = input.value.replace(/,+$/, "").trim();
      input.value = "";
      if (!t || items.length >= 500) return;
      items.push(t);
      isNull = false;
      render();
      onEdit();
      input.focus();
    };
    input.addEventListener("keydown", function (e) {
      if (e.key === "Enter" || e.key === ",") {
        e.preventDefault();
        add();
      } else if (e.key === "Backspace" && !input.value && items.length) {
        items.pop();
        render();
        onEdit();
      }
    });
    input.addEventListener("blur", function () { if (input.value.trim()) add(); });
    wrap.addEventListener("click", function (e) { if (e.target === wrap) input.focus(); });
    box.appendChild(wrap);
    f.set = function (v) {
      isNull = !Array.isArray(v);
      items = Array.isArray(v) ? v.filter(function (x) { return typeof x === "string"; }) : [];
      render();
    };
    f.get = function () { return { ok: true, value: isNull && !items.length ? serverValue(f.node.path) : items.slice() }; };
    f.setDisabled = function (d) {
      f.disabled = d;
      input.disabled = d;
      render();
    };
  }

  var settingsForm = makeForm({
    page: "settings",
    sectioned: false,
    scope: function () { return "plugin"; },
    sudo: function () { return false; },
    denied: function () {
      if (forcedStreamer()) return "Streamer mode is enforced by the owner: the bot's settings are not shown (they can hold coordinates).";
      return can("activity") ? "" : "Your account can't see the bot's settings; ask the owner.";
    },
    readOnly: function () { return !can("settings"); },
    els: { status: el.settingsStatus, save: el.settingsSave, revert: el.settingsRevert, offline: el.settingsOffline, body: el.settingsBody }
  });

  // ---------- admin (sudo) indicator ----------
  // While the password was typed in the last 15 minutes, the header says so, with a button to end it now.

  var sudo = { until: 0, timer: 0 };

  /** until: when admin mode ends (local ms), 0 = off. */
  function sudoState(until) {
    sudo.until = until || 0;
    renderSudo();
  }

  function renderSudo() {
    window.clearTimeout(sudo.timer);
    var left = sudo.until - Date.now();
    el.sudoBox.hidden = left <= 0 || auth.blocked;
    // Mobile chip: "Admin · N min — Lock" while unlocked, "Unlock admin" otherwise.
    var chip = $("admin-chip");
    chip.hidden = !!auth.blocked;
    chip.classList.toggle("on", left > 0);
    chip.textContent = left > 0 ? "Admin \u00b7 " + Math.ceil(left / 60000) + " min \u2014 Lock" : "Unlock admin";
    chip.title = left > 0 ? "Admin mode is on. Tap to lock it now." : "Type the password to unlock admin mode for 15 minutes.";
    if (left <= 0) return;
    var min = Math.ceil(left / 60000);
    el.sudoText.textContent = "Admin unlocked for " + min + " min";
    sudo.timer = window.setTimeout(renderSudo, Math.min(30000, left % 60000 || 60000));
  }

  /** Also picks up changes to the logged-in user (name, permissions, mustChange) made by the owner meanwhile. */
  function refreshSudo() {
    api("/api/v1/auth/state").then(function (st) {
      // elevatedUntil is server time; turn it into local time
      sudoState(st && st.elevatedUntil ? num(st.elevatedUntil, 0) - state.clockSkew : 0);
      if (!st || !st.loggedIn || auth.blocked) return;
      var oldPerms = JSON.stringify(me.perms);
      if (!setUser(st.user)) return;
      if (me.mustChange) mustChangeRequired();
      else applyPerms(JSON.stringify(me.perms) !== oldPerms);
    }, function () {});
  }

  el.sudoLock.addEventListener("click", function () {
    api("/api/v1/auth/lock", { method: "POST", body: {} }).then(function () {
      sudoState(0);
      toast("Admin locked. Account administration requires your password again.");
    }, function () {});
  });

  // ---------- streamer mode toggle ----------

  /** relabel: also redraw the pickers whose text depends on the mode (not on startup: no data yet). */
  function renderStreamer(relabel) {
    if (forcedStreamer()) {
      document.documentElement.classList.add("streamer");
    } else if (el.streamerBtn.classList.contains("locked")) {
      // No longer forced: back to this browser's own choice.
      var mine = false;
      try {
        mine = window.localStorage.getItem(STREAMER_KEY) === "1";
      } catch (e) {
        mine = false;
      }
      document.documentElement.classList.toggle("streamer", mine);
    }
    var on = streamerOn();
    var locked = forcedStreamer();
    el.streamerBtn.setAttribute("aria-pressed", on ? "true" : "false");
    el.streamerBtn.setAttribute("aria-disabled", locked ? "true" : "false");
    el.streamerBtn.classList.toggle("locked", locked);
    el.streamerBtn.title = locked ? "Streamer mode is enforced by the owner" : "Blur coordinates, keys, addresses and events (this browser only)";
    el.streamerState.textContent = locked ? ": on (enforced)" : on ? ": on" : ": off";
    Array.prototype.forEach.call(el.linkUrls.querySelectorAll(".streamer-block"), function (b) { b.disabled = on; });
    if (on) {
      var sel = window.getSelection && window.getSelection();
      if (sel) sel.removeAllRanges(); // a selection made before turning it on would still copy
    }
    if (!relabel) return;
    updateSelect();
    renderBotSelect();
  }

  el.streamerBtn.addEventListener("click", function () {
    if (forcedStreamer()) {
      toast("Streamer mode is enforced by the owner.");
      renderStreamer(true);
      return;
    }
    var on = !streamerOn();
    document.documentElement.classList.toggle("streamer", on);
    try {
      window.localStorage.setItem(STREAMER_KEY, on ? "1" : "0");
    } catch (e) {
      // storage blocked: it still applies until the page is reloaded
    }
    renderStreamer(true);
  });

  // ---------- appearance: theme picker (this browser only) ----------
  // The choice lives in localStorage ("wesort.theme") through theme-init.js, which applies it before the CSS loads.
  // It is not a bot setting: not part of the Settings form, never sent to the server.

  var THEME_CHOICES = [
    { id: "classic", name: "Classic", desc: "Blocky bevels, like now" },
    { id: "modern", name: "Modern", desc: "Soft rounded cards" },
    { id: "light", name: "Light", desc: "Flat and calm" },
    { id: "dark", name: "Dark", desc: "Easy on the eyes" },
    { id: "midnight", name: "Midnight", desc: "OLED black, stream-safe" },
    { id: "mac", name: "Mac", desc: "Smoky glass, peach pills" },
    { id: "contrast", name: "High contrast", desc: "Maximum legibility" },
    { id: "system", name: "Match system", desc: "" }
  ];
  var THEME_NAMES = {};
  THEME_CHOICES.forEach(function (c) { THEME_NAMES[c.id] = c.name; });
  var themeCards = [];

  /** A swatch circle in a theme's own colours: the data-theme attribute scopes that theme's tokens to it. */
  function themeSwatch(theme, token) {
    var s = document.createElement("span");
    s.className = "swatch sw-" + token;
    s.setAttribute("data-theme", theme);
    return s;
  }

  /** Spans, not divs: a button holds phrasing content only. */
  function themeSpan(cls, text) {
    var s = document.createElement("span");
    s.className = cls;
    s.textContent = text;
    return s;
  }

  function buildThemeCards() {
    var grid = $("theme-grid");
    if (!grid) return;
    var themes = window.SM_THEMES;
    if (!themes) {
      grid.appendChild(line("note", "Themes are unavailable: theme-init.js did not load."));
      return;
    }
    THEME_CHOICES.forEach(function (c) {
      var b = document.createElement("button");
      b.type = "button";
      b.className = "theme-card";
      b.dataset.choice = c.id;
      var sw = document.createElement("span");
      sw.className = "swatches";
      sw.setAttribute("aria-hidden", "true");
      if (c.id === "system") {
        sw.appendChild(themeSwatch("classic", "surface"));
        sw.appendChild(themeSwatch("dark", "surface"));
        sw.appendChild(themeSwatch("dark", "accent"));
      } else {
        sw.appendChild(themeSwatch(c.id, "bg"));
        sw.appendChild(themeSwatch(c.id, "surface"));
        sw.appendChild(themeSwatch(c.id, "accent"));
      }
      b.appendChild(sw);
      var text = document.createElement("span");
      text.className = "theme-text";
      text.appendChild(themeSpan("theme-name", c.name));
      var desc = themeSpan("theme-desc", c.desc);
      text.appendChild(desc);
      b.appendChild(text);
      b.addEventListener("click", function () { themes.set(c.id); });
      grid.appendChild(b);
      themeCards.push({ id: c.id, button: b, desc: desc });
    });
    themes.onChange(renderThemeCards);
    renderThemeCards(themes.saved());
  }

  function renderThemeCards(pref) {
    themeCards.forEach(function (card) {
      card.button.setAttribute("aria-pressed", card.id === pref ? "true" : "false");
      if (card.id === "system") {
        card.desc.textContent = "Follows this device: " + THEME_NAMES[window.SM_THEMES.resolve("system")] + " now";
      }
    });
  }

  buildThemeCards();

  // Header palette button: open Settings the way its nav button does, then show the Appearance section.
  $("theme-btn").addEventListener("click", function () {
    var nav = el.dashNav.querySelector('.nav-btn[data-page="settings"]');
    if (nav) nav.click();
    var section = $("appearance");
    if (section) section.scrollIntoView({ block: "start" });
    var pressed = document.querySelector('#theme-grid .theme-card[aria-pressed="true"]') || document.querySelector("#theme-grid .theme-card");
    if (pressed) pressed.focus({ preventScroll: true });
  });

  // ---------- mobile layout: bottom tabs, More sheet, account menu, admin chip ----------
  // Extra triggers only: every one of them calls the same control or function as the desktop layout.

  var mobile = {
    nav: $("bottom-nav"),
    more: $("bnav-more"),
    sheet: $("more-sheet"),
    rescan: $("more-rescan"),
    account: $("account-btn"),
    accountMenu: $("account-menu")
  };
  var MORE_PAGES = ["settings"];

  /** Opens a page exactly as its desktop nav button does (that button stays the single source of truth). */
  function openNavPage(page) {
    var nav = el.dashNav.querySelector('.nav-btn[data-page="' + page + '"]');
    if (nav) nav.click();
    window.scrollTo(0, 0);
  }

  /** Mirrors the desktop nav (selected page, hidden pages) onto the bottom tabs and the More sheet. */
  function syncMobileNav() {
    var sel = el.dashNav.querySelector(".nav-btn.selected");
    var page = sel ? sel.dataset.page : "";
    var mirror = function (btn) {
      var top = el.dashNav.querySelector('.nav-btn[data-page="' + btn.dataset.page + '"]');
      btn.hidden = !top || top.hidden;
      if (btn.dataset.page === page) btn.setAttribute("aria-current", "page");
      else btn.removeAttribute("aria-current");
    };
    Array.prototype.forEach.call(mobile.nav.querySelectorAll(".bnav-btn[data-page]"), mirror);
    Array.prototype.forEach.call(mobile.sheet.querySelectorAll(".sheet-item[data-page]"), mirror);
    if (MORE_PAGES.indexOf(page) >= 0) mobile.more.setAttribute("aria-current", "page");
    else mobile.more.removeAttribute("aria-current");
  }
  new MutationObserver(syncMobileNav).observe(el.dashNav, { subtree: true, attributes: true, attributeFilter: ["class", "hidden"] });
  syncMobileNav();

  mobile.nav.addEventListener("click", function (e) {
    var b = e.target.closest ? e.target.closest(".bnav-btn[data-page]") : null;
    if (b) openNavPage(b.dataset.page);
  });

  function sheetFocusables() {
    return Array.prototype.filter.call(mobile.sheet.querySelectorAll("button"), function (b) { return !b.hidden && !b.disabled; });
  }

  function openSheet() {
    mobile.rescan.hidden = el.rescanBox.hidden;
    mobile.rescan.disabled = el.rescanBtn.disabled;
    mobile.sheet.hidden = false;
    mobile.more.setAttribute("aria-expanded", "true");
    var f = sheetFocusables();
    var cur = mobile.sheet.querySelector(".sheet-item[aria-current]");
    (cur || f[1] || f[0]).focus();
  }

  function closeSheet(refocus) {
    if (mobile.sheet.hidden) return;
    mobile.sheet.hidden = true;
    mobile.more.setAttribute("aria-expanded", "false");
    if (refocus) mobile.more.focus();
  }

  mobile.more.addEventListener("click", function () {
    if (mobile.sheet.hidden) openSheet();
    else closeSheet(true);
  });
  $("more-close").addEventListener("click", function () { closeSheet(true); });
  mobile.sheet.addEventListener("click", function (e) {
    if (e.target === mobile.sheet) {
      closeSheet(true); // backdrop
      return;
    }
    var item = e.target.closest ? e.target.closest(".sheet-item") : null;
    if (!item) return;
    closeSheet(false);
    if (item === mobile.rescan) el.rescanBtn.click();
    else if (item.id === "more-users") openUsersSection();
    else openNavPage(item.dataset.page);
  });
  mobile.sheet.addEventListener("keydown", function (e) {
    if (e.key !== "Tab") return;
    var f = sheetFocusables();
    if (!f.length) return;
    var first = f[0];
    var last = f[f.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  });

  function setAccountMenu(open, refocus) {
    mobile.accountMenu.hidden = !open;
    mobile.account.setAttribute("aria-expanded", open ? "true" : "false");
    if (open) mobile.accountMenu.querySelector("button:not([hidden])").focus();
    else if (refocus) mobile.account.focus();
  }

  mobile.account.addEventListener("click", function () { setAccountMenu(mobile.accountMenu.hidden, false); });
  $("account-link").addEventListener("click", function () {
    setAccountMenu(false, false);
    el.linkOpen.click();
  });
  $("account-logout").addEventListener("click", function () {
    setAccountMenu(false, false);
    el.logoutBtn.click();
  });
  document.addEventListener("click", function (e) {
    if (!mobile.accountMenu.hidden && !(e.target.closest && e.target.closest(".account"))) setAccountMenu(false, false);
  });
  document.addEventListener("keydown", function (e) {
    if (e.key !== "Escape") return;
    if (!mobile.sheet.hidden) closeSheet(true);
    if (!mobile.accountMenu.hidden) setAccountMenu(false, true);
  });

  $("admin-chip").addEventListener("click", function () {
    if (sudo.until > Date.now()) el.sudoLock.click(); // the existing "Lock now"
    else askPassword().then(function () {}, function () {}); // the existing password prompt
  });

  // A selection that only starts outside a hidden value would still copy it: refuse that copy.
  document.addEventListener("copy", function (e) {
    if (!streamerOn()) return;
    var sel = window.getSelection && window.getSelection();
    if (!sel || !sel.rangeCount) return;
    var marked = document.querySelectorAll("[data-sensitive]");
    for (var i = 0; i < marked.length; i++) {
      if (sel.containsNode(marked[i], true)) {
        e.preventDefault();
        toast("Streamer mode is on: hidden values can't be copied.");
        return;
      }
    }
  });

  renderStreamer(false);

  // ---------- permissions: what this user sees ----------
  // Hides the tabs and buttons the logged-in user can't use (the server refuses them anyway). The owner sees
  // everything, as before sub-users. pagesChanged: the permissions changed while logged in, so the open page is
  // loaded again (it may now be read-only, or gone).

  function applyPerms(pagesChanged) {
    var name = me.name;
    el.userName.hidden = !name;
    el.userName.textContent = name;
    el.userName.title = name ? "Logged in as " + name + (me.owner ? " (owner)" : "") : "";
    el.accountName.hidden = !name;
    el.accountName.textContent = name ? "Logged in as " + name : "";
    var view = can("view");
    var activity = can("activity");
    var stashPicker = document.querySelector(".picker-stash");
    if (stashPicker) stashPicker.hidden = !view;
    var botPicker = document.querySelector(".picker-bot");
    if (botPicker) botPicker.hidden = !activity;
    el.botsOpen.hidden = !activity;
    renderStreamer(false); // forced streamer mode: on and locked
    updateActivityNav(false);
    el.linkOpen.hidden = !can("link");
    $("account-link").hidden = !can("link");
    if (!can("link") && link.open) closeLink();
    var order = can("order");
    el.orderPicker.hidden = !order;
    el.cartDrop.hidden = !order;
    document.documentElement.classList.toggle("no-order", !order);
    el.accountSection.hidden = me.owner;
    el.accountWho.textContent = name;
    el.usersSection.hidden = !me.owner;
    $("more-users").hidden = !me.owner;
    updateRescan();
    renderOrderList();
    if (!view) showEmpty();
    if (!pageAllowed(dash.page)) showPage("stash");
    else if (pagesChanged && auth.started) showPage(dash.page);
  }

  /** Console "list" mode: the allowed command prefixes, under the input. */


  // ---------- settings > users (owner only) ----------
  // Sub-user management. Every write needs Admin unlocked: withSudo asks for the password on 403 "confirm password"
  // and runs the write again. The forms are inline (not windows), so the password prompt doesn't close them.
  // Usernames, audit details and the like come from other people: textContent only.

  var USER_NAME_RE = /^[A-Za-z0-9_.-]{1,32}$/;
  var PERM_SWITCHES = [
    ["view", "View stashes", "Stash list, stash index, item icons and the order list."],
    ["order", "Place orders", "Place orders; cancel, retry or force-cancel their own orders."],
    ["ordersAll", "Manage all orders", "Cancel, retry or force-cancel anyone's orders; Clear history."],
    ["guildOrders", "Place guild orders", "Choose a guild purpose or declare a separate recipient when placing an order."],
    ["positions", "View stash positions", "See coordinates and physical slot details for permitted stashes."],
    ["activity", "Bots and activity", "Bots tab, Activity, job status and the plugin settings (read only)."],
    ["settings", "Change plugin settings", "Save the WeSort plugin settings."],
    ["rescan", "Rescan stashes", "Ask the bot to re-read every container of a stash."],
    ["link", "Link addon", "The Link addon panel (linked bots in scope) and releasing a stash. The Manager host provides the agent key."],
    ["streamer", "Force streamer mode", "A restriction: the server never sends this user coordinates, player names, server addresses, chat or the bots' free text. Bot settings are refused. The user can't turn it off."]
  ];
  var PRESET_ORDER = ["viewer", "member", "operator", "officer"];
  var PRESET_NAMES = { viewer: "Viewer", member: "Member", orderer: "Orderer", operator: "Operator", officer: "Officer" };
  var IMPLIED_HINT = "Placing or managing orders also turns on View stashes. Changing agent settings also turns on Bots and activity.";
  var SUDO_USERS_TEXT = "Managing users needs Admin unlocked. Type your password again; it is not asked for again for 15 minutes.";
  var users = { data: null, error: "", loading: false, form: null, busy: false, seq: 0 };

  /** The permission object in a fixed shape (and key order, so two can be compared with jsonEq). */
  function normPerms(p) {
    p = p && typeof p === "object" ? p : {};
    var out = {};
    PERM_SWITCHES.forEach(function (s) { out[s[0]] = p[s[0]] === true; });
    out.stashes = Array.isArray(p.stashes)
      ? p.stashes.filter(function (s) { return s && typeof s === "object"; }).map(function (s) { return { server: str(s.server), stash: str(s.stash) }; })
      : null;
    out.bots = Array.isArray(p.bots) ? p.bots.filter(function (b) { return typeof b === "string"; }) : null;
    return out;
  }

  /** The server's implied-permission rule, applied the same way here for the labels and the switches. */
  function withImplied(p) {
    if (p.order || p.ordersAll) p.view = true;
    if (p.settings) p.activity = true;
    return p;
  }

  function presetShape(p) {
    var n = withImplied(normPerms(p));
    delete n.stashes;
    delete n.bots;
    return n;
  }

  function presetList() {
    var presets = users.data && users.data.presets && typeof users.data.presets === "object" ? users.data.presets : {};
    var names = PRESET_ORDER.filter(function (n) { return presets[n] && typeof presets[n] === "object"; });
    Object.keys(presets).forEach(function (n) {
      if (!(n === "orderer" && presets.member) && names.indexOf(n) < 0 && presets[n] && typeof presets[n] === "object") names.push(n);
    });
    return names.map(function (n) { return { id: n, name: PRESET_NAMES[n] || n, perms: presets[n] }; });
  }

  /** "Viewer", "Orderer", ... or "Custom". */
  function presetLabel(perms) {
    var shape = presetShape(perms);
    var list = presetList();
    for (var i = 0; i < list.length; i++) {
      if (jsonEq(presetShape(list[i].perms), shape)) return list[i].name;
    }
    return "Custom";
  }

  function scopeText(perms) {
    var p = normPerms(perms);
    var parts = [];
    if (p.stashes) parts.push(plural(String(p.stashes.length), "stash").replace(/stashs$/, "stashes"));
    if (p.bots) parts.push(plural(String(p.bots.length), "bot"));
    return parts.length ? "only " + parts.join(", ") : "";
  }

  function failText(e, what) {
    var b = e && e.body;
    if (b && b.error === "cancelled") return "";
    if (e && e.status === 429) return tooMany(b);
    if (b && b.error) return sentence(str(b.error));
    return what + (e && e.network ? ": server unreachable." : e && e.status ? " (HTTP " + e.status + ")." : ".");
  }

  function userWrite(path, body) {
    return withSudo(function () { return api(path, { method: "POST", body: body || {} }); }, SUDO_USERS_TEXT);
  }

  function loadUsers() {
    if (!me.owner || users.loading) return;
    users.loading = true;
    api("/api/v1/users").then(function (d) {
      users.data = d && typeof d === "object" ? d : { users: [] };
      users.error = "";
    }, function (e) {
      if (e && e.status === 403 && e.body && e.body.error === "confirm password") {
        users.data = null;
        users.error = "locked";
      } else {
        users.error = failText(e, "Could not load the users");
      }
    }).then(function () {
      users.loading = false;
      renderUsers();
    });
  }

  function openUsersSection() {
    openNavPage("settings");
    var section = el.usersSection;
    if (section && !section.hidden) section.scrollIntoView({ block: "start" });
  }

  /** A labelled text or password input in the auth-field style. */
  function textField(labelText, type, opts) {
    opts = opts || {};
    var label = document.createElement("label");
    label.className = "auth-field";
    var span = document.createElement("span");
    span.className = "field-label";
    span.textContent = labelText;
    var input = document.createElement("input");
    input.type = type;
    input.className = "mc-input";
    input.maxLength = opts.maxLength || 1024;
    input.autocomplete = opts.autocomplete || "off";
    input.spellcheck = false;
    if (opts.value) input.value = opts.value;
    if (type === "text") input.setAttribute("autocapitalize", "off");
    label.appendChild(span);
    label.appendChild(input);
    return { node: label, input: input };
  }

  /** A checkbox with its text, like the Activity page's "enabled only". */
  function checkField(text, checked) {
    var label = document.createElement("label");
    label.className = "unset user-check";
    var input = document.createElement("input");
    input.type = "checkbox";
    input.checked = !!checked;
    label.appendChild(input);
    label.appendChild(document.createTextNode(" " + text));
    return { node: label, input: input };
  }

  function selectField(options, value, ariaLabel) {
    var sel = document.createElement("select");
    sel.className = "mc-select";
    sel.setAttribute("aria-label", ariaLabel);
    options.forEach(function (o) {
      var opt = document.createElement("option");
      opt.value = o[0];
      opt.textContent = o[1];
      sel.appendChild(opt);
    });
    sel.value = value;
    return sel;
  }

  /** A row in the settings-form style: name and help on the left, the control, then a status column. */
  function permRow(name, help) {
    var row = document.createElement("div");
    row.className = "setting";
    var labelBox = document.createElement("div");
    labelBox.className = "setting-label";
    labelBox.appendChild(line("setting-name", name));
    if (help) labelBox.appendChild(line("setting-help", help));
    row.appendChild(labelBox);
    var control = document.createElement("div");
    control.className = "setting-control";
    row.appendChild(control);
    var status = document.createElement("div");
    status.className = "setting-status";
    row.appendChild(status);
    return { row: row, control: control, status: status };
  }

  /** The same ON / OFF switch as a bool setting. */
  function switchControl(label, on, onChange) {
    var t = document.createElement("button");
    t.type = "button";
    t.className = "mc-button toggle";
    t.setAttribute("role", "switch");
    t.setAttribute("aria-label", label);
    var text = document.createElement("span");
    text.className = "toggle-text";
    t.appendChild(text);
    var cur = !!on;
    var show = function () {
      text.textContent = cur ? "ON" : "OFF";
      t.classList.toggle("on", cur);
      t.setAttribute("aria-checked", cur ? "true" : "false");
    };
    t.addEventListener("click", function () {
      cur = !cur;
      show();
      onChange();
    });
    show();
    return { node: t, get: function () { return cur; }, set: function (v) { cur = !!v; show(); } };
  }

  /**
   * "All <things>" or a checklist. items: [{ key, value, label: Node }]; selected: null (all) or values, matched
   * with keyOf. Selected values that aren't in items (a stash or bot not seen now) stay listed and ticked.
   */
  function scopePicker(allText, someText, items, selected, keyOf, unknownLabel) {
    var box = document.createElement("div");
    box.className = "scope-picker";
    var group = "scope-" + (++users.seq);
    var mk = function (text, checked) {
      var label = document.createElement("label");
      label.className = "unset user-check";
      var r = document.createElement("input");
      r.type = "radio";
      r.name = group;
      r.checked = checked;
      label.appendChild(r);
      label.appendChild(document.createTextNode(" " + text));
      box.appendChild(label);
      return r;
    };
    var all = mk(allText, selected === null);
    var some = mk(someText, selected !== null);
    var list = document.createElement("div");
    list.className = "scope-list";
    var picked = {};
    (selected || []).forEach(function (v) { picked[keyOf(v)] = v; });
    var rows = [];
    var seen = {};
    items.forEach(function (it) {
      seen[it.key] = true;
      rows.push({ value: it.value, label: it.label, checked: picked.hasOwnProperty(it.key) });
    });
    Object.keys(picked).forEach(function (k) {
      if (!seen[k]) rows.push({ value: picked[k], label: unknownLabel(picked[k]), checked: true });
    });
    var boxes = [];
    rows.forEach(function (r) {
      var label = document.createElement("label");
      label.className = "unset user-check";
      var c = document.createElement("input");
      c.type = "checkbox";
      c.checked = r.checked;
      label.appendChild(c);
      label.appendChild(document.createTextNode(" "));
      label.appendChild(r.label);
      list.appendChild(label);
      boxes.push({ input: c, value: r.value });
    });
    if (!rows.length) list.appendChild(line("note", "None known yet."));
    box.appendChild(list);
    var sync = function () { list.hidden = !some.checked; };
    all.addEventListener("change", sync);
    some.addEventListener("change", sync);
    sync();
    return {
      node: box,
      get: function () {
        if (all.checked) return null;
        return boxes.filter(function (b) { return b.input.checked; }).map(function (b) { return b.value; });
      }
    };
  }

  function stashScopeLabel(s) {
    var span = document.createElement("span");
    span.appendChild(document.createTextNode(str(s.stash) + " @ "));
    span.appendChild(hiddenSpan(str(s.server)));
    return span;
  }

  function permsEditor(initial) {
    var p = normPerms(initial);
    var box = document.createElement("div");
    box.className = "perm-editor";
    var sw = {};
    var refresh = function () {
      var forceView = sw.order.get() || sw.ordersAll.get();
      var forceActivity = sw.settings.get();
      [["view", forceView], ["activity", forceActivity]].forEach(function (f) {
        var s = sw[f[0]];
        if (f[1]) s.set(true);
        s.node.disabled = f[1];
        s.status.textContent = f[1] ? "on (implied)" : "";
      });
    };
    PERM_SWITCHES.forEach(function (s) {
      var r = permRow(s[1], s[2]);
      var c = switchControl(s[1], p[s[0]], function () { refresh(); });
      r.control.appendChild(c.node);
      c.status = r.status;
      sw[s[0]] = c;
      box.appendChild(r.row);
    });
    var stashItems = state.stashes.map(function (s) {
      return { key: stashKey(s), value: { server: str(s.server), stash: str(s.stash) }, label: stashScopeLabel(s) };
    });
    var stashRow = permRow("Stashes", "The stashes this user may see and use.");
    var stashPick = scopePicker("All stashes", "Only these stashes:", stashItems, p.stashes, stashKey, function (v) {
      var l = stashScopeLabel(v);
      l.appendChild(document.createTextNode(" (not seen now)"));
      return l;
    });
    stashRow.control.appendChild(stashPick.node);
    box.appendChild(stashRow.row);
    var botItems = dash.bots.map(function (b) {
      var span = document.createElement("span");
      span.textContent = botName(b) + (botName(b) !== b.botId ? " (" + b.botId + ")" : "");
      return { key: b.botId, value: b.botId, label: span };
    });
    var botRow = permRow("Bots", "The dashboard bots this user may see and use.");
    var botPick = scopePicker("All bots", "Only these bots:", botItems, p.bots, function (v) { return v; }, function (v) {
      var span = document.createElement("span");
      span.textContent = v + " (not seen now)";
      return span;
    });
    botRow.control.appendChild(botPick.node);
    box.appendChild(botRow.row);
    box.appendChild(line("note", IMPLIED_HINT));
    refresh();

    return {
      node: box,
      get: function () {
        var out = {};
        PERM_SWITCHES.forEach(function (s) { out[s[0]] = sw[s[0]].get(); });
        out.stashes = stashPick.get();
        out.bots = botPick.get();
        return withImplied(out);
      }
    };
  }

  function formButtons(form, saveText, onCancel) {
    var row = document.createElement("div");
    row.className = "user-form-actions";
    var save = document.createElement("button");
    save.type = "submit";
    save.className = "mc-button primary";
    save.textContent = saveText;
    var cancel = document.createElement("button");
    cancel.type = "button";
    cancel.className = "mc-button";
    cancel.textContent = "Cancel";
    cancel.addEventListener("click", onCancel);
    row.appendChild(save);
    row.appendChild(cancel);
    form.appendChild(row);
    var msg = line("note warn", "");
    msg.setAttribute("role", "status");
    form.appendChild(msg);
    return { save: save, msg: msg };
  }

  function newForm(title) {
    var form = document.createElement("form");
    form.className = "user-form";
    form.noValidate = true;
    if (title) form.appendChild(line("settings-group-title", title));
    return form;
  }

  function closeUserForm() {
    users.form = null;
    renderUsers();
  }

  /** Runs a user-management write from a form: busy state, message on failure, reload on success. */
  function submitUserWrite(btn, msg, path, body, okText, failWhat) {
    btn.disabled = true;
    msg.textContent = "Saving...";
    userWrite(path, body).then(function () {
      users.form = null;
      toast(okText);
      loadUsers();
    }, function (e) {
      btn.disabled = false;
      var t = failText(e, failWhat);
      msg.textContent = t;
      if (t && !msg.isConnected) toast(t);
    });
  }

  function checkNewPassword(pw, again) {
    if (pw.length < MIN_PASSWORD) return "The password needs at least " + MIN_PASSWORD + " characters.";
    if (pw !== again) return "The two passwords are different.";
    return "";
  }

  function checkUserName(name, selfId) {
    if (!USER_NAME_RE.test(name)) return "A username is 1 to 32 letters, digits, _ . or -.";
    var lower = name.toLowerCase();
    var d = users.data || {};
    if (selfId !== "owner" && d.owner && str(d.owner.name).toLowerCase() === lower) return "That is the owner's name.";
    var clash = (Array.isArray(d.users) ? d.users : []).some(function (u) { return u && u.id !== selfId && str(u.name).toLowerCase() === lower; });
    return clash ? "Another user already has that name." : "";
  }

  function addUserForm() {
    var form = newForm("Add user");
    var name = textField("Username (1-32 letters, digits, _ . -)", "text", { maxLength: 32 });
    var pw = textField("Temporary password (at least " + MIN_PASSWORD + " characters)", "password", { autocomplete: "new-password" });
    var again = textField("Temporary password again", "password", { autocomplete: "new-password" });
    form.appendChild(name.node);
    form.appendChild(pw.node);
    form.appendChild(again.node);
    var presets = presetList();
    var options = presets.map(function (p) { return [p.id, p.name]; }).concat([["custom", "Custom"]]);
    var presetLabelEl = document.createElement("label");
    presetLabelEl.className = "auth-field";
    presetLabelEl.appendChild(line("field-label", "Permissions"));
    var sel = selectField(options, presets.length ? presets[0].id : "custom", "Permissions");
    presetLabelEl.appendChild(sel);
    form.appendChild(presetLabelEl);
    var presetHint = line("note", "");
    form.appendChild(presetHint);
    var editorHost = document.createElement("div");
    form.appendChild(editorHost);
    var editor = null;
    var last = presets.length ? presets[0].perms : { view: true };
    var describe = function (perms) {
      var p = withImplied(normPerms(perms));
      var on = PERM_SWITCHES.filter(function (s) { return p[s[0]]; }).map(function (s) { return s[1]; });
      return on.length ? on.join(", ") + "." : "Nothing.";
    };
    var sync = function () {
      var preset = null;
      presets.forEach(function (p) { if (p.id === sel.value) preset = p; });
      if (preset) {
        last = preset.perms;
        editor = null;
        editorHost.replaceChildren();
        presetHint.hidden = false;
        presetHint.textContent = describe(preset.perms);
      } else {
        presetHint.hidden = true;
        if (!editor) {
          editor = permsEditor(last); // starts from the preset picked before
          editorHost.replaceChildren(editor.node);
        }
      }
    };
    sel.addEventListener("change", sync);
    sync();
    var must = checkField("Must change the password at first login", true);
    form.appendChild(must.node);
    var b = formButtons(form, "Add user", closeUserForm);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var n = name.input.value.trim();
      var bad = checkUserName(n, null) || checkNewPassword(pw.input.value, again.input.value);
      if (bad) return void (b.msg.textContent = bad);
      var body = { name: n, password: pw.input.value, mustChange: must.input.checked };
      if (editor) body.perms = editor.get();
      else body.preset = sel.value;
      submitUserWrite(b.save, b.msg, "/api/v1/users", body, "Added " + n, "Could not add the user");
    });
    return { node: form, focus: name.input };
  }

  function editUserForm(u) {
    var form = newForm("Edit " + str(u.name));
    var name = textField("Username", "text", { maxLength: 32, value: str(u.name) });
    form.appendChild(name.node);
    var editor = permsEditor(u.perms);
    form.appendChild(editor.node);
    var b = formButtons(form, "Save", closeUserForm);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var n = name.input.value.trim();
      var body = { perms: editor.get() };
      if (n !== str(u.name)) {
        var bad = checkUserName(n, u.id);
        if (bad) return void (b.msg.textContent = bad);
        body.name = n;
      }
      submitUserWrite(b.save, b.msg, "/api/v1/users/" + encodeURIComponent(u.id), body, "Saved " + n, "Could not save the user");
    });
    return { node: form, focus: name.input };
  }

  function resetPasswordForm(u) {
    var form = newForm("Reset the password of " + str(u.name));
    var pw = textField("New temporary password (at least " + MIN_PASSWORD + " characters)", "password", { autocomplete: "new-password" });
    var again = textField("New temporary password again", "password", { autocomplete: "new-password" });
    form.appendChild(pw.node);
    form.appendChild(again.node);
    var must = checkField("Must change the password at next login", true);
    form.appendChild(must.node);
    form.appendChild(line("note", "Every session of " + str(u.name) + " ends now."));
    var b = formButtons(form, "Reset password", closeUserForm);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var bad = checkNewPassword(pw.input.value, again.input.value);
      if (bad) return void (b.msg.textContent = bad);
      submitUserWrite(b.save, b.msg, "/api/v1/users/" + encodeURIComponent(u.id) + "/password",
        { password: pw.input.value, mustChange: must.input.checked }, "Password reset for " + str(u.name), "Could not reset the password");
    });
    return { node: form, focus: pw.input };
  }

  function renameOwnerForm(current) {
    var form = newForm("Rename the owner");
    var name = textField("Owner name (1-32 letters, digits, _ . -)", "text", { maxLength: 32, value: current });
    form.appendChild(name.node);
    form.appendChild(line("note", "The owner can still log in with a blank username."));
    var b = formButtons(form, "Rename", closeUserForm);
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var n = name.input.value.trim();
      if (n === current) return void closeUserForm();
      var bad = checkUserName(n, "owner");
      if (bad) return void (b.msg.textContent = bad);
      submitUserWrite(b.save, b.msg, "/api/v1/users/owner", { name: n }, "The owner is now " + n, "Could not rename the owner");
      refreshSudoSoon();
    });
    return { node: form, focus: name.input };
  }

  function minecraftAccountForm(id, name, current) {
    var form = newForm("Minecraft account for " + name);
    var account = textField("Confirmed Minecraft username", "text", { maxLength: 16, value: str(current) });
    form.appendChild(account.node);
    form.appendChild(line("note", "Confirm this account belongs to the member before saving. Leave blank to remove the link."));
    var buttons = formButtons(form, "Save account", closeUserForm);
    form.addEventListener("submit", function (event) {
      event.preventDefault();
      submitUserWrite(buttons.save, buttons.msg, "/api/v1/users/" + encodeURIComponent(id) + "/minecraft",
        { account: account.input.value.trim() }, "Minecraft account saved", "Could not save the Minecraft account");
    });
    return { node: form, focus: account.input };
  }

  /** The header shows the owner's name: read it again once the rename went through. */
  function refreshSudoSoon() {
    window.setTimeout(refreshSudo, 1500);
  }

  /** A one-click action (Disable, Log out everywhere, Delete): confirmed first, then written. */
  function userAction(btn, confirmText, path, body, okText, failWhat) {
    if (confirmText && !window.confirm(confirmText)) return;
    btn.disabled = true;
    userWrite(path, body).then(function (r) {
      toast(typeof okText === "function" ? okText(r) : okText);
      loadUsers();
    }, function (e) {
      btn.disabled = false;
      var t = failText(e, failWhat);
      if (t) toast(t);
    });
  }

  function smallButton(text, cls, onClick) {
    var b = document.createElement("button");
    b.type = "button";
    b.className = "mc-button small" + (cls ? " " + cls : "");
    b.textContent = text;
    b.addEventListener("click", function () { onClick(b); });
    return b;
  }

  function toggleForm(kind, id) {
    var f = users.form;
    users.form = f && f.kind === kind && f.id === id ? null : { kind: kind, id: id };
    renderUsers();
  }

  function userRow(u, focusTarget) {
    var row = document.createElement("div");
    row.className = "user-row" + (u.disabled ? " is-disabled" : "");
    var head = document.createElement("div");
    head.className = "user-head";
    head.appendChild(themeSpan("user-title", str(u.name)));
    head.appendChild(themeSpan("badge", presetLabel(u.perms)));
    if (u.disabled === true) head.appendChild(themeSpan("badge st-failed", "Disabled"));
    if (u.mustChange === true) head.appendChild(themeSpan("badge st-accepted", "Must change password"));
    row.appendChild(head);
    var last = typeof u.lastLoginAt === "number" ? u.lastLoginAt : null;
    var meta = ["Last login " + (last === null ? "never" : ago(last)), plural(String(num(u.sessions, 0)), "session")];
    var scope = scopeText(u.perms);
    if (scope) meta.push(scope);
    var metaLine = line("note user-meta", meta.join(" · "));
    if (last !== null) metaLine.title = "Last login " + new Date(last).toLocaleString();
    row.appendChild(metaLine);
    var actions = document.createElement("div");
    actions.className = "user-actions";
    var id = encodeURIComponent(u.id);
    var name = str(u.name);
    actions.appendChild(smallButton("Edit", "", function () { toggleForm("edit", u.id); }));
    actions.appendChild(smallButton("Minecraft account", "", function () { toggleForm("minecraft", u.id); }));
    actions.appendChild(smallButton(u.disabled ? "Enable" : "Disable", "", function (b) {
      userAction(b, u.disabled ? "" : "Disable " + name + "? Every session of " + name + " ends now, and they can't log in until you enable the account again.",
        "/api/v1/users/" + id, { disabled: !u.disabled }, (u.disabled ? "Enabled " : "Disabled ") + name, "Could not change the account");
    }));
    actions.appendChild(smallButton("Reset password", "", function () { toggleForm("reset", u.id); }));
    actions.appendChild(smallButton("Log out everywhere", "", function (b) {
      userAction(b, "Log " + name + " out of every browser?", "/api/v1/users/" + id + "/logout", {}, function (r) {
        var n = r && typeof r.ended === "number" ? r.ended : 0;
        return name + ": " + plural(String(n), "session") + " ended";
      }, "Could not log the user out");
    }));
    actions.appendChild(smallButton("Delete", "danger", function (b) {
      userAction(b, "Delete the user " + name + "?\n\nThis can't be undone. Their sessions end now; their orders stay.",
        "/api/v1/users/" + id + "/delete", {}, "Deleted " + name, "Could not delete the user");
    }));
    row.appendChild(actions);
    var f = users.form;
    if (f && f.id === u.id) {
      var built = f.kind === "edit" ? editUserForm(u) : f.kind === "reset" ? resetPasswordForm(u)
        : minecraftAccountForm(u.id, name, u.minecraftAccount);
      if (built) {
        row.appendChild(built.node);
        focusTarget.input = built.focus;
      }
    }
    return row;
  }

  function renderUsers() {
    if (!el.usersBody) return;
    var frag = document.createDocumentFragment();
    var d = users.data;
    if (!d) {
      if (users.error === "locked") {
        frag.appendChild(unlockBox("The users are only shown after you confirm your password.", function () {
          users.error = "";
          loadUsers();
        }));
      } else {
        frag.appendChild(line(users.error ? "note warn" : "note", users.error || "Loading..."));
      }
      el.usersBody.replaceChildren(frag);
      return;
    }
    var focusTarget = { input: null };
    var list = (Array.isArray(d.users) ? d.users : []).filter(function (u) { return u && typeof u === "object" && typeof u.id === "string"; });
    var ownerName = d.owner && typeof d.owner === "object" ? str(d.owner.name) : "";

    var ownerRow = document.createElement("div");
    ownerRow.className = "user-row owner-row";
    var ownerHead = document.createElement("div");
    ownerHead.className = "user-head";
    ownerHead.appendChild(themeSpan("user-title", ownerName || "owner"));
    ownerHead.appendChild(themeSpan("badge", "Owner"));
    ownerRow.appendChild(ownerHead);
    ownerRow.appendChild(line("note user-meta", "Every permission, always. Logs in with this name or a blank username."));
    var ownerActions = document.createElement("div");
    ownerActions.className = "user-actions";
    ownerActions.appendChild(smallButton("Rename", "", function () { toggleForm("owner", "owner"); }));
    ownerActions.appendChild(smallButton("Minecraft account", "", function () { toggleForm("minecraft", "owner"); }));
    ownerRow.appendChild(ownerActions);
    if (users.form && users.form.id === "owner") {
      var rf = users.form.kind === "owner" ? renameOwnerForm(ownerName)
        : minecraftAccountForm("owner", ownerName || "owner", d.owner && d.owner.minecraftAccount);
      ownerRow.appendChild(rf.node);
      focusTarget.input = rf.focus;
    }
    frag.appendChild(ownerRow);

    var max = num(d.maxUsers, 50);
    var bar = document.createElement("div");
    bar.className = "users-bar";
    bar.appendChild(line("note", list.length + " of " + max + " sub-users"));
    var add = smallButton("Add user", "primary", function () { toggleForm("add", null); });
    add.disabled = list.length >= max;
    if (add.disabled) add.title = "At most " + max + " sub-users";
    bar.appendChild(add);
    frag.appendChild(bar);
    if (users.form && users.form.kind === "add") {
      var af = addUserForm();
      frag.appendChild(af.node);
      focusTarget.input = af.focus;
    }

    if (!list.length) frag.appendChild(line("note", "No sub-users yet. Add one to let someone else in with fewer permissions."));
    list.forEach(function (u) { frag.appendChild(userRow(u, focusTarget)); });
    frag.appendChild(line("note", IMPLIED_HINT));
    el.usersBody.replaceChildren(frag);
    if (focusTarget.input) focusTarget.input.focus({ preventScroll: false });
  }

  // ----- audit log -----

  function loadAudit() {
    if (!me.owner) return;
    el.auditStatus.textContent = "Loading...";
    el.auditRefresh.disabled = true;
    api("/api/v1/audit?limit=200").then(function (d) {
      var entries = d && Array.isArray(d.entries) ? d.entries.filter(function (x) { return x && typeof x === "object"; }) : [];
      renderAudit(entries);
      el.auditStatus.textContent = entries.length ? plural(String(entries.length), "entry").replace(/entrys$/, "entries") + ", newest first" : "";
    }, function (e) {
      el.auditStatus.textContent = failText(e, "Could not load the audit log");
    }).then(function () {
      el.auditRefresh.disabled = false;
    });
  }

  function renderAudit(entries) {
    var frag = document.createDocumentFragment();
    if (!entries.length) frag.appendChild(line("note", "Nothing logged yet."));
    entries.forEach(function (x) {
      var row = document.createElement("div");
      row.className = "audit-row" + (/failed$/.test(str(x.action)) ? " audit-fail" : "");
      var at = num(x.at, 0);
      var time = themeSpan("audit-time", at ? new Date(at).toLocaleString() : "");
      row.appendChild(time);
      row.appendChild(themeSpan("audit-user", str(x.user) || "–"));
      row.appendChild(themeSpan("audit-action", str(x.action)));
      var detail = hide(themeSpan("audit-detail", str(x.detail)));
      if (str(x.address)) {
        var addr = hiddenSpan(" · " + str(x.address));
        addr.className = "audit-address";
        detail.appendChild(addr);
      }
      row.appendChild(detail);
      frag.appendChild(row);
    });
    el.auditList.replaceChildren(frag);
  }

  el.audit.addEventListener("toggle", function () {
    if (el.audit.open) loadAudit();
  });
  el.auditRefresh.addEventListener("click", loadAudit);

  // ---------- dashboard: Activity page ----------
  // Polled every 2 s while visible (the bot then pushes every 2 s), every 15 s otherwise. textContent only.

  var act = { timer: 0, data: null, bot: null, levelFilter: "", sourceFilter: "", moduleQuery: "", enabledOnly: false };
  var LEVEL_COLORS = { info: "lvl-info", warn: "lvl-warn", error: "lvl-error" };

  function durationText(ms) {
    var s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + "s";
    var m = Math.floor(s / 60);
    if (m < 60) return m + "m";
    var h = Math.floor(m / 60);
    if (h < 48) return h + "h " + (m % 60) + "m";
    return Math.floor(h / 24) + "d " + (h % 24) + "h";
  }

  function loadActivity() {
    window.clearTimeout(act.timer);
    var b = currentBot();
    var visible = dash.page === "activity" && !document.hidden;
    if (dash.page === "activity" && b && hasCap(b, "activity") && !auth.blocked) {
      var botId = b.botId;
      api("/api/v1/bots/" + encodeURIComponent(botId) + "/activity").then(function (d) {
        if (botId !== dash.botId || dash.page !== "activity") return;
        if (act.bot !== botId) {
          act.bot = botId;
          act.sourceFilter = "";
        }
        act.data = d;
        renderActivity();
      }, function () {});
    } else if (dash.page === "activity") {
      el.actBody.hidden = true;
      el.actEmpty.hidden = false;
      el.actEmpty.textContent = !b ? "No dashboard bot yet." : "This bot doesn't send its activity (update the WeSort plugin).";
    }
    if (dash.page === "activity") act.timer = window.setTimeout(loadActivity, visible ? 2000 : 15000);
  }

  document.addEventListener("visibilitychange", function () {
    if (!document.hidden && dash.page === "activity") loadActivity();
  });

  /** Ten pixel "hearts" or "drumsticks" for a 0-20 value, with half steps. */
  function pips(value, cls) {
    var box = document.createElement("span");
    box.className = "pips " + cls;
    var v = Math.max(0, Math.min(20, Math.round(num(value, 0))));
    for (var i = 0; i < 10; i++) {
      var p = document.createElement("span");
      var fill = v - i * 2;
      p.className = "pip" + (fill >= 2 ? " full" : fill === 1 ? " half" : "");
      box.appendChild(p);
    }
    box.title = v + " / 20";
    return box;
  }

  /** Health / food: pips (desktop), a thin bar (mobile) and "n / 20". */
  function vital(value, cls) {
    var v = Math.max(0, Math.min(20, Math.round(num(value, 0))));
    var box = document.createElement("span");
    box.className = "vital vital-" + cls;
    var p = pips(value, cls);
    p.classList.add("only-desktop");
    box.appendChild(p);
    var meter = document.createElement("span");
    meter.className = "meter only-mobile";
    var fill = document.createElement("span");
    fill.className = "meter-fill";
    fill.style.width = v * 5 + "%"; // CSSOM, allowed by the CSP
    meter.appendChild(fill);
    box.appendChild(meter);
    var n = document.createElement("span");
    n.className = "vital-num";
    n.textContent = v + " / 20";
    box.appendChild(n);
    return box;
  }

  /** A sensitive value that reads "Hidden (streamer)" in streamer mode (the value itself is not rendered then). */
  function streamerValue(text) {
    var wrap = document.createElement("span");
    wrap.className = "sv";
    var v = hiddenSpan(text);
    v.classList.add("sv-value");
    wrap.appendChild(v);
    var h = document.createElement("span");
    h.className = "sv-hidden";
    h.textContent = "Hidden (streamer)";
    wrap.appendChild(h);
    return wrap;
  }

  function stat(label, valueNode) {
    var d = document.createElement("div");
    d.className = "act-stat";
    d.appendChild(line("act-stat-label", label));
    var v = document.createElement("div");
    v.className = "act-stat-value";
    if (typeof valueNode === "string") v.textContent = valueNode;
    else v.appendChild(valueNode);
    d.appendChild(v);
    return d;
  }

  function renderActivity() {
    var d = act.data;
    var a = d && d.activity && typeof d.activity === "object" ? d.activity : null;
    el.actEmpty.hidden = !!a;
    el.actBody.hidden = !a;
    if (!a) {
      el.actEmpty.textContent = "Waiting for the bot's first activity report...";
      return;
    }
    // --- status card ---
    var c = a.connection && typeof a.connection === "object" ? a.connection : {};
    var head = document.createDocumentFragment();
    var actBot = currentBot();
    if (actBot) head.appendChild(line("act-bot only-mobile", botName(actBot)));
    var badge = document.createElement("span");
    var stateName = str(c.state) || "offline";
    badge.className = "conn-badge conn-" + stateName;
    badge.textContent = stateName === "queue" ? "In queue" + (typeof c.queuePosition === "number" ? " #" + c.queuePosition : "") +
      (str(c.queueEta) ? " · ETA " + str(c.queueEta) : "") : stateName.charAt(0).toUpperCase() + stateName.slice(1);
    head.appendChild(badge);
    if (str(c.server)) {
      var srv = document.createElement("div");
      srv.className = "act-server";
      srv.appendChild(streamerValue(str(c.server)));
      head.appendChild(srv);
    }
    var age = d.receivedAt ? serverNow() - num(d.receivedAt, 0) : null;
    var upd = line("note act-updated" + (age !== null && age > 60000 ? " warn" : ""), age === null ? "" :
      "last update " + durationText(age) + " ago" + (age > 60000 ? " (stale: the bot isn't reporting)" : ""));
    head.appendChild(upd);
    el.actHead.replaceChildren(head);

    var stats = document.createDocumentFragment();
    if (str(c.dimension)) stats.appendChild(stat("Dimension", str(c.dimension).replace(/^minecraft:/, "")));
    if (typeof c.x === "number") {
      stats.appendChild(stat("Position", streamerValue(Math.round(c.x) + ", " + Math.round(num(c.y, 0)) + ", " + Math.round(num(c.z, 0)))));
    }
    if (typeof c.health === "number") stats.appendChild(stat("Health", vital(c.health, "hearts")));
    if (typeof c.food === "number") stats.appendChild(stat("Food", vital(c.food, "food")));
    if (typeof c.ping === "number") stats.appendChild(stat("Ping", c.ping + " ms"));
    if (typeof c.uptimeSeconds === "number") stats.appendChild(stat("Uptime", durationText(c.uptimeSeconds * 1000)));
    if (typeof c.spectators === "number") stats.appendChild(stat("Spectators", String(c.spectators)));
    stats.appendChild(stat("Controlled by", str(c.controllingPlayer) || "nobody"));
    el.actStats.replaceChildren(stats);

    // --- now doing ---
    var cur = Array.isArray(a.current) ? a.current : [];
    var nf = document.createDocumentFragment();
    if (!cur.length) nf.appendChild(line("note", "Nothing running right now."));
    cur.forEach(function (it) {
      if (!it || typeof it !== "object") return;
      var card = document.createElement("div");
      card.className = "now-card " + (LEVEL_COLORS[it.level] || "lvl-info");
      var top = document.createElement("div");
      top.className = "now-top";
      top.appendChild(line("now-source", str(it.source) || "?"));
      if (typeof it.since === "number") top.appendChild(line("note", "since " + durationText(serverNow() - it.since)));
      card.appendChild(top);
      if (str(it.title)) card.appendChild(line("now-title", str(it.title)));
      if (str(it.detail)) card.appendChild(hide(line("now-detail", str(it.detail))));
      if (it.progress && typeof it.progress === "object" && num(it.progress.total, 0) > 0) {
        var bar = document.createElement("div");
        bar.className = "progress";
        var fill = document.createElement("div");
        fill.className = "progress-fill";
        fill.style.transform = "scaleX(" + Math.max(0, Math.min(1, num(it.progress.done, 0) / num(it.progress.total, 1))) + ")";
        bar.appendChild(fill);
        card.appendChild(bar);
        card.appendChild(line("note", num(it.progress.done, 0) + " / " + num(it.progress.total, 0)));
      }
      nf.appendChild(card);
    });
    el.actNow.replaceChildren(nf);

    renderModules(a);

    // --- plugins ---
    var pf = document.createDocumentFragment();
    (Array.isArray(a.plugins) ? a.plugins : []).forEach(function (p) {
      if (!p || typeof p !== "object") return;
      var row = document.createElement("div");
      row.className = "plugin-row";
      row.appendChild(line("plugin-name", str(p.name) || str(p.id)));
      row.appendChild(line("note", (str(p.id) && str(p.name) ? str(p.id) + " " : "") + (str(p.version) ? "v" + str(p.version) : "")));
      pf.appendChild(row);
    });
    el.actPlugins.replaceChildren(pf);

    renderEvents();
  }

  function renderModules(a) {
    var mods = Array.isArray(a.modules) ? a.modules : [];
    var q = act.moduleQuery;
    var groups = new Map();
    var on = 0;
    mods.forEach(function (m) {
      if (!m || typeof m !== "object") return;
      if (m.enabled) on++;
      if (act.enabledOnly && !m.enabled) return;
      if (q && (str(m.name) + " " + str(m.plugin) + " " + str(m.status)).toLowerCase().indexOf(q) < 0) return;
      var p = str(m.plugin) || "aquarius";
      if (!groups.has(p)) groups.set(p, []);
      groups.get(p).push(m);
    });
    el.actModCount.textContent = on + " of " + mods.length + " on";
    var frag = document.createDocumentFragment();
    groups.forEach(function (list, plugin) {
      frag.appendChild(line("settings-group-title", plugin));
      var grid = document.createElement("div");
      grid.className = "module-grid";
      list.forEach(function (m) {
        var b = document.createElement("div");
        b.className = "module-chip" + (m.enabled ? " on" : "");
        var dot = document.createElement("span");
        dot.className = "dot only-desktop " + (m.enabled ? "dot-on" : "dot-off");
        b.appendChild(dot);
        var mName = document.createElement("span");
        mName.className = "module-name";
        mName.textContent = " " + str(m.name);
        b.appendChild(mName);
        if (str(m.status)) b.appendChild(hide(line("module-status", str(m.status))));
        // Mobile: a switch showing the state. Read-only: the dashboard has no way to toggle a module from here.
        var sw = document.createElement("span");
        sw.className = "switch only-mobile";
        sw.setAttribute("aria-checked", m.enabled ? "true" : "false");
          sw.setAttribute("role", "switch");
          sw.setAttribute("aria-readonly", "true");
          sw.setAttribute("aria-disabled", "true");
          sw.setAttribute("aria-label", str(m.name));
        b.appendChild(sw);
        b.title = (m.enabled ? "On" : "Off");

        grid.appendChild(b);
      });
      frag.appendChild(grid);
    });
    if (!groups.size) frag.appendChild(line("note", "No modules match."));
    el.actModules.replaceChildren(frag);
  }



  function renderEvents() {
    var d = act.data;
    var events = d && Array.isArray(d.events) ? d.events.slice() : [];
    events.sort(function (x, y) { return num(y && y.at, 0) - num(x && x.at, 0); }); // newest first, by time
    // the source filter's options
    var sources = [];
    events.forEach(function (e) { if (e && str(e.source) && sources.indexOf(str(e.source)) < 0) sources.push(str(e.source)); });
    sources.sort();
    var want = [""].concat(sources).join("\u0000");
    if (el.actSource.dataset.opts !== want) {
      el.actSource.dataset.opts = want;
      var of = document.createDocumentFragment();
      [""].concat(sources).forEach(function (s) {
        var o = document.createElement("option");
        o.value = s;
        o.textContent = s || "All sources";
        of.appendChild(o);
      });
      el.actSource.replaceChildren(of);
      el.actSource.value = act.sourceFilter;
    }
    var scroll = el.actEvents.scrollTop;
    var frag = document.createDocumentFragment();
    var shown = 0;
    events.forEach(function (e) {
      if (!e || typeof e !== "object") return;
      if (act.sourceFilter && str(e.source) !== act.sourceFilter) return;
      if (act.levelFilter && e.level !== act.levelFilter) return;
      shown++;
      var row = document.createElement("div");
      row.className = "event " + (LEVEL_COLORS[e.level] || "lvl-info");
      var t = line("event-time", durationText(serverNow() - num(e.at, 0)) + " ago");
      t.title = new Date(num(e.at, 0)).toLocaleString();
      row.appendChild(t);
      row.appendChild(line("event-source", str(e.source)));
      row.appendChild(hide(line("event-text", str(e.text))));
      frag.appendChild(row);
    });
    if (!shown) frag.appendChild(line("note", events.length ? "No events match." : "No events yet."));
    el.actEvents.replaceChildren(frag);
    el.actEvents.scrollTop = scroll;
  }

  el.actModFilter.addEventListener("input", function () {
    act.moduleQuery = el.actModFilter.value.trim().toLowerCase();
    if (act.data && act.data.activity) renderModules(act.data.activity);
  });
  el.actEnabledOnly.addEventListener("change", function () {
    act.enabledOnly = el.actEnabledOnly.checked;
    if (act.data && act.data.activity) renderModules(act.data.activity);
  });
  el.actSource.addEventListener("change", function () {
    act.sourceFilter = el.actSource.value;
    renderEvents();
  });
  el.actLevel.addEventListener("change", function () {
    act.levelFilter = el.actLevel.value;
    renderEvents();
  });

  // ---------- sudo mode ----------
  // 403 {"error":"confirm password"}; the page asks for the password, confirms it, and runs the action again.

  /** text: optional explanation for the password prompt (askPassword). */
  function withSudo(action, text) {
    return action().catch(function (err) {
      if (!(err && err.status === 403 && err.body && err.body.error === "confirm password")) throw err;
      return askPassword(text).then(function () { return action(); });
    });
  }

  function askPassword(text) {
    return new Promise(function (resolve, reject) {
      var form = document.createElement("form");
      form.className = "password-form";
      form.noValidate = true;
      form.appendChild(line("window-info", text || (me.owner
        ? "Type your password again to administer this installation."
        : "Account administration needs your password again.")));
      var label = document.createElement("label");
      label.className = "auth-field";
      var span = document.createElement("span");
      span.className = "field-label";
      span.textContent = "Password";
      var input = document.createElement("input");
      input.type = "password";
      input.className = "mc-input";
      input.autocomplete = "current-password";
      input.maxLength = 1024;
      label.appendChild(span);
      label.appendChild(input);
      form.appendChild(label);
      var ok = document.createElement("button");
      ok.type = "submit";
      ok.className = "mc-button";
      ok.textContent = "Confirm";
      form.appendChild(ok);
      var msg = line("note warn", "");
      form.appendChild(msg);
      var done = false;
      form.addEventListener("submit", function (e) {
        e.preventDefault();
        ok.disabled = true;
        msg.textContent = "Checking...";
        api("/api/v1/auth/confirm", { method: "POST", body: { password: input.value } }).then(function (r) {
          sudoState(r && r.elevatedUntil ? num(r.elevatedUntil, 0) - state.clockSkew : Date.now() + 15 * 60 * 1000);
          done = true;
          closeWindow();
          resolve();
        }, function (err) {
          ok.disabled = false;
          var b = err && err.body;
          msg.textContent = err && err.status === 401 ? "Wrong password." :
            err && err.status === 429 ? "Too many attempts. Try again in " + (b && b.retryAfter ? b.retryAfter : 60) + " s." : (b && b.error) || "Could not confirm.";
          input.select();
        });
      });
      var win = openWindow("Confirm password", form, "password-window");
      input.focus();
      // Closing the window without confirming cancels the action.
      var observer = new MutationObserver(function () {
        if (!win.isConnected) {
          observer.disconnect();
          if (!done) reject({ status: 0, body: { error: "cancelled" } });
        }
      });
      observer.observe(document.body, { childList: true });
    });
  }

  function hasCap(b, cap) {
    return !!b && Array.isArray(b.caps) && b.caps.indexOf(cap) >= 0;
  }

  function capHint(cap) {
    return line("note", "This warehouse agent does not offer " + cap + ".");
  }

  function unlockBox(text, done) {
    var box = document.createElement("div");
    box.className = "cap-hint";
    box.appendChild(line("note", text));
    var b = document.createElement("button");
    b.type = "button";
    b.className = "mc-button";
    b.textContent = "Confirm password";
    b.addEventListener("click", function () {
      askPassword().then(done, function () {});
    });
    box.appendChild(b);
    return box;
  }

  function updateActivityNav(firstLoad) {
    var show = hasCap(currentBot(), "activity") && can("activity");
    el.navActivity.hidden = !show;
    if (!show && dash.page === "activity") showPage("bots");
    if (firstLoad) {
      var saved = null;
      try {
        saved = window.localStorage.getItem(PAGE_KEY);
      } catch (e) {
        saved = null;
      }
      if (dash.page !== "stash") return;
      if (saved && saved !== "stash" && pageAllowed(saved) && (saved !== "activity" || show)) showPage(saved);
      else if (!saved && show) showPage("activity");
    }
  }

  // ---------- controls ----------

  el.search.addEventListener("input", function () { queueRender(SEARCH_DEBOUNCE_MS); });
  el.sort.addEventListener("change", function () {
    if (isCreativeTab(state.tab)) state.sortTab = el.sort.value;
    else state.sortAll = el.sort.value;
    queueRender(0);
  });
  el.search.addEventListener("keydown", function (e) {
    if (e.key === "Escape") {
      el.search.value = "";
      queueRender(0);
    }
  });
  el.select.addEventListener("change", function () {
    selectStash(el.select.value || null);
  });

  // Keep "last seen 2m ago" fresh between polls.
  window.setInterval(function () {
    updateHeader();
    if (link.open) renderLink();
  }, 5000);
  window.setInterval(poll, POLL_MS);
  // The owner may change this user's permissions (or disable the account) at any time.
  window.setInterval(function () { if (!auth.blocked) refreshSudo(); }, 30000);
  el.sort.value = state.sortAll;
  checkAuth(); // renders the tabs once logged in (their icons need the session)
})();
