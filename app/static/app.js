const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

const state = {
  demo: false,
  loggedIn: false,
  accounts: [],
  settings: {},
  reels: [],
  total: 0,
  limit: 60,
  offset: 0,
  playerIndex: -1,
  jobPollTimer: null,
  filters: {
    account: "all",
    status: "all",
    q: "",
    sort: "newest",
    min_views: "",
    min_likes: "",
    min_duration: "",
    max_duration: "",
    since: "",
    until: "",
    include_hidden: false,
  },
};

/* -- helpers ------------------------------------------------------------ */
const compact = new Intl.NumberFormat("he-IL", { notation: "compact", maximumFractionDigits: 1 });
const full = new Intl.NumberFormat("he-IL");

function fmtCount(value) {
  const n = Number(value || 0);
  return n >= 10000 ? compact.format(n) : full.format(n);
}

function fmtDuration(seconds) {
  const total = Math.round(Number(seconds) || 0);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

function fmtDate(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleDateString("he-IL", { day: "numeric", month: "short", year: "numeric" });
}

function engagementRate(reel) {
  const views = Math.max(Number(reel.play_count) || 0, 1);
  return ((Number(reel.like_count) || 0) + (Number(reel.comment_count) || 0)) / views;
}

function toast(message, isError = false) {
  const node = document.createElement("div");
  node.className = `toast${isError ? " is-error" : ""}`;
  node.textContent = message;
  $("#toasts").append(node);
  setTimeout(() => node.remove(), isError ? 7000 : 3500);
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: options.body ? { "Content-Type": "application/json" } : undefined,
    ...options,
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(data?.detail || `שגיאה ${response.status}`);
  return data;
}

function activeFilterQuery(extra = {}) {
  const params = new URLSearchParams();
  const filters = { ...state.filters, ...extra };
  Object.entries(filters).forEach(([key, value]) => {
    if (value === "" || value === null || value === undefined || value === false) return;
    params.set(key, String(value));
  });
  return params;
}

/* -- session ------------------------------------------------------------ */
async function refreshStatus() {
  const status = await api("/api/status");
  state.demo = status.demo;
  state.loggedIn = status.logged_in;
  state.settings = status.settings || {};
  $("#demo-badge").classList.toggle("hidden", !status.demo);
  $("#logout").classList.toggle("hidden", !status.logged_in || status.demo);
  $("#session-state").textContent = status.demo
    ? "נתוני הדגמה מקומיים"
    : status.logged_in
      ? `מחובר כ-@${status.username}`
      : "לא מחובר";
  $("#login-modal").classList.toggle("hidden", status.logged_in || status.demo);
  if (state.filters.sort === "newest" && state.settings.default_sort) {
    state.filters.sort = state.settings.default_sort;
    $("#sort").value = state.settings.default_sort;
  }
}

async function login(event) {
  event.preventDefault();
  const error = $("#login-error");
  error.classList.add("hidden");
  try {
    await api("/api/login", {
      method: "POST",
      body: {
        username: $("#login-username").value,
        password: $("#login-password").value,
        verification_code: $("#login-2fa").value,
        challenge_code: $("#login-challenge").value,
      },
    });
    $("#login-password").value = "";
    await refreshStatus();
    await loadAccounts();
    toast("ההתחברות הצליחה");
  } catch (err) {
    error.textContent = err.message;
    error.classList.remove("hidden");
  }
}

/* -- accounts ----------------------------------------------------------- */
async function loadAccounts() {
  const data = await api("/api/accounts");
  state.accounts = data.items;
  renderAccounts();
}

function renderAccounts() {
  const list = $("#account-list");
  list.replaceChildren();

  const totals = state.accounts.reduce(
    (acc, account) => ({
      reels: acc.reels + (account.reel_count || 0),
      unseen: acc.unseen + (account.unseen_count || 0),
    }),
    { reels: 0, unseen: 0 },
  );

  list.append(accountRow({
    pk: "all",
    username: "כל החשבונות",
    reel_count: totals.reels,
    unseen_count: totals.unseen,
  }, true));

  if (!state.accounts.length) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "עוד לא הוספת חשבונות. הקלד שם משתמש למעלה.";
    list.append(empty);
    return;
  }

  state.accounts.forEach((account) => list.append(accountRow(account, false)));
}

function accountRow(account, isAll) {
  const row = document.createElement("div");
  row.className = "account-row";
  row.classList.toggle("is-active", state.filters.account === account.pk);
  row.tabIndex = 0;

  const name = document.createElement("span");
  name.className = "name";
  name.textContent = isAll ? account.username : `@${account.username}`;
  row.append(name);

  if (account.unseen_count > 0) {
    const unseen = document.createElement("span");
    unseen.className = "unseen";
    unseen.textContent = fmtCount(account.unseen_count);
    unseen.title = "סרטונים שעוד לא צפית בהם";
    row.append(unseen);
  }

  const count = document.createElement("span");
  count.className = "count";
  count.textContent = fmtCount(account.reel_count || 0);
  count.title = "סה״כ סרטונים שנאספו";
  row.append(count);

  if (!isAll) {
    const syncBtn = document.createElement("button");
    syncBtn.className = "row-btn";
    syncBtn.type = "button";
    syncBtn.textContent = "⟳";
    syncBtn.title = "סנכרן חשבון";
    syncBtn.addEventListener("click", async (event) => {
      event.stopPropagation();
      await api(`/api/accounts/${account.pk}/sync`, { method: "POST", body: {} });
      toast(`סנכרון @${account.username} נוסף לתור`);
      pollJobs();
    });
    row.append(syncBtn);

    const delBtn = document.createElement("button");
    delBtn.className = "row-btn";
    delBtn.type = "button";
    delBtn.textContent = "✕";
    delBtn.title = "הסר חשבון וכל הנתונים שלו";
    delBtn.addEventListener("click", async (event) => {
      event.stopPropagation();
      if (!confirm(`להסיר את @${account.username} ואת כל הסרטונים ששמורים עליו?`)) return;
      await api(`/api/accounts/${account.pk}`, { method: "DELETE" });
      if (state.filters.account === account.pk) state.filters.account = "all";
      await loadAccounts();
      await loadReels(true);
    });
    row.append(delBtn);
  }

  const select = () => {
    state.filters.account = account.pk;
    renderAccounts();
    loadReels(true);
    if (!$("#tab-stats").classList.contains("hidden")) loadStats();
  };
  row.addEventListener("click", select);
  row.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      select();
    }
  });
  return row;
}

async function addAccount(event) {
  event.preventDefault();
  const input = $("#add-account-input");
  const username = input.value.trim();
  if (!username) return;
  try {
    await api("/api/accounts", { method: "POST", body: { username } });
    input.value = "";
    toast(`@${username} נוסף לתור המשיכה`);
    pollJobs();
  } catch (err) {
    toast(err.message, true);
  }
}

/* -- jobs --------------------------------------------------------------- */
async function pollJobs() {
  clearTimeout(state.jobPollTimer);
  let data;
  try {
    data = await api("/api/jobs");
  } catch {
    return;
  }
  const strip = $("#job-strip");
  const running = data.items.filter((job) => job.status === "queued" || job.status === "running");
  const failed = data.items.filter((job) => job.status === "error").slice(0, 2);

  strip.replaceChildren();
  if (running.length) {
    const label = document.createElement("span");
    label.textContent = `⟳ ${running.map((job) => job.label).join(" · ")}`;
    strip.append(label);
  }
  failed.forEach((job) => {
    const node = document.createElement("span");
    node.style.color = "var(--critical)";
    node.textContent = `${job.label}: ${job.error}`;
    strip.append(node);
  });
  strip.classList.toggle("hidden", !strip.childElementCount);

  if (running.length) {
    state.jobPollTimer = setTimeout(pollJobs, 1500);
  } else if (state.lastActive) {
    await loadAccounts();
    await loadReels(true);
  }
  state.lastActive = running.length > 0;
}

/* -- reels grid --------------------------------------------------------- */
async function loadReels(reset = false) {
  if (reset) state.offset = 0;
  const params = activeFilterQuery({ limit: state.limit, offset: state.offset });
  const data = await api(`/api/reels?${params}`);
  state.total = data.total;
  state.reels = reset ? data.items : [...state.reels, ...data.items];
  renderGrid();
  updateExportLink();
}

function updateExportLink() {
  const params = activeFilterQuery();
  params.delete("limit");
  params.delete("offset");
  params.set("fmt", "csv");
  $("#export-csv").href = `/api/export?${params}`;
}

function renderGrid() {
  const grid = $("#grid");
  grid.replaceChildren();
  state.reels.forEach((reel, index) => grid.append(reelCard(reel, index)));

  const empty = $("#grid-empty");
  empty.classList.toggle("hidden", state.reels.length > 0);
  empty.textContent = state.accounts.length
    ? "אין סרטונים שתואמים את הסינון הנוכחי."
    : "הוסף חשבון מהתפריט הצדדי כדי להתחיל.";

  $("#result-count").textContent = state.total
    ? `${full.format(state.total)} סרטונים · מוצגים ${full.format(state.reels.length)}`
    : "";
  $("#load-more").classList.toggle("hidden", state.reels.length >= state.total);
}

function reelCard(reel, index) {
  const card = document.createElement("article");
  card.className = "card";
  card.classList.toggle("is-seen", Boolean(reel.watched_at));

  const thumb = document.createElement("div");
  thumb.className = "card-thumb";
  const img = document.createElement("img");
  img.loading = "lazy";
  img.alt = "";
  img.src = `/api/reels/${reel.pk}/thumb`;
  img.addEventListener("error", () => {
    const placeholder = document.createElement("div");
    placeholder.className = "no-thumb";
    placeholder.textContent = "אין תצוגה מקדימה";
    img.replaceWith(placeholder);
  });
  thumb.append(img);

  if (reel.duration) {
    const duration = document.createElement("span");
    duration.className = "card-tag tag-duration";
    duration.textContent = fmtDuration(reel.duration);
    thumb.append(duration);
  }
  if (!reel.watched_at) {
    const unseen = document.createElement("span");
    unseen.className = "card-tag tag-unseen";
    unseen.textContent = "חדש";
    thumb.append(unseen);
  }
  if (reel.is_favorite) {
    const fav = document.createElement("span");
    fav.className = "card-tag tag-fav";
    fav.textContent = "★";
    thumb.append(fav);
  }
  card.append(thumb);

  const body = document.createElement("div");
  body.className = "card-body";

  const account = document.createElement("div");
  account.className = "card-account";
  account.textContent = `@${reel.account_username} · ${fmtDate(reel.taken_at)}`;
  body.append(account);

  const caption = document.createElement("p");
  caption.className = "card-caption";
  caption.textContent = reel.caption || "—";
  body.append(caption);

  const metrics = document.createElement("div");
  metrics.className = "card-metrics";
  metrics.append(
    metric("▶", fmtCount(reel.play_count), "צפיות"),
    metric("♥", fmtCount(reel.like_count), "לייקים"),
    metric("💬", fmtCount(reel.comment_count), "תגובות"),
    metric("%", (engagementRate(reel) * 100).toFixed(1), "אחוז מעורבות"),
  );
  body.append(metrics);

  const actions = document.createElement("div");
  actions.className = "card-actions";
  actions.append(
    toggleButton(reel.watched_at ? "נצפה" : "לא נצפה", Boolean(reel.watched_at), async () => {
      const next = !reel.watched_at;
      await api(`/api/reels/${reel.pk}/watch`, { method: "POST", body: { watched: next } });
      reel.watched_at = next ? new Date().toISOString() : null;
      renderGrid();
      loadAccounts();
    }),
    toggleButton("★", Boolean(reel.is_favorite), async () => {
      const next = !reel.is_favorite;
      await api(`/api/reels/${reel.pk}/favorite`, { method: "POST", body: { value: next } });
      reel.is_favorite = next ? 1 : 0;
      renderGrid();
    }),
    toggleButton("הסתר", Boolean(reel.is_hidden), async () => {
      const next = !reel.is_hidden;
      await api(`/api/reels/${reel.pk}/hide`, { method: "POST", body: { value: next } });
      reel.is_hidden = next ? 1 : 0;
      loadReels(true);
    }),
  );
  body.append(actions);
  card.append(body);

  card.addEventListener("click", (event) => {
    if (event.target.closest(".icon-btn")) return;
    openPlayer(index);
  });
  return card;
}

function metric(symbol, value, title) {
  const span = document.createElement("span");
  span.title = title;
  span.textContent = `${symbol} ${value}`;
  return span;
}

function toggleButton(label, isOn, handler) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `icon-btn${isOn ? " is-on" : ""}`;
  button.textContent = label;
  button.addEventListener("click", async (event) => {
    event.stopPropagation();
    try {
      await handler();
    } catch (err) {
      toast(err.message, true);
    }
  });
  return button;
}

/* -- bulk actions ------------------------------------------------------- */
async function bulkMark(watched) {
  const filters = { ...state.filters };
  delete filters.limit;
  delete filters.offset;
  const data = await api("/api/reels/bulk", {
    method: "POST",
    body: { action: watched ? "mark_watched" : "mark_unwatched", filters },
  });
  toast(`עודכנו ${full.format(data.affected)} סרטונים`);
  await loadAccounts();
  await loadReels(true);
}

/* -- player ------------------------------------------------------------- */
const video = $("#player-video");
let autoMarked = false;

function openPlayer(index) {
  if (index < 0 || index >= state.reels.length) return;
  state.playerIndex = index;
  const reel = state.reels[index];
  autoMarked = false;

  $("#player").classList.remove("hidden");
  $("#player-account").textContent = `@${reel.account_username}`;
  $("#player-date").textContent = fmtDate(reel.taken_at);
  $("#player-caption").textContent = reel.caption || "";
  $("#player-link").href = reel.code ? `https://www.instagram.com/reel/${reel.code}/` : "#";

  const stats = $("#player-stats");
  stats.replaceChildren();
  [
    ["צפיות", fmtCount(reel.play_count)],
    ["לייקים", fmtCount(reel.like_count)],
    ["תגובות", fmtCount(reel.comment_count)],
    ["אחוז מעורבות", `${(engagementRate(reel) * 100).toFixed(2)}%`],
    ["אורך", fmtDuration(reel.duration)],
    ["מוזיקה", reel.music_title || "—"],
  ].forEach(([label, value]) => {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = value;
    stats.append(dt, dd);
  });

  renderPlayerControls(reel);
  renderRating(reel);
  $("#player-note").value = reel.note || "";

  const fallback = $("#player-fallback");
  if (state.demo) {
    video.classList.add("hidden");
    video.removeAttribute("src");
    fallback.classList.remove("hidden");
    fallback.textContent = "מצב הדגמה — אין וידאו אמיתי לניגון. כל שאר הממשק פעיל.";
  } else {
    fallback.classList.add("hidden");
    video.classList.remove("hidden");
    video.src = `/api/reels/${reel.pk}/video`;
    video.play().catch(() => {});
  }
}

function renderPlayerControls(reel) {
  const watchedBtn = $("#player-watched");
  watchedBtn.textContent = reel.watched_at ? "בטל סימון צפייה" : "סמן כנצפה";
  $("#player-fav").classList.toggle("btn-primary", Boolean(reel.is_favorite));
  $("#player-hide").classList.toggle("btn-primary", Boolean(reel.is_hidden));
}

function renderRating(reel) {
  const container = $("#player-rating");
  container.replaceChildren();
  for (let value = 1; value <= 5; value += 1) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "★";
    button.setAttribute("aria-label", `${value} כוכבים`);
    button.classList.toggle("is-on", (reel.rating || 0) >= value);
    button.addEventListener("click", async () => {
      const next = reel.rating === value ? 0 : value;
      await api(`/api/reels/${reel.pk}/annotate`, { method: "POST", body: { rating: next } });
      reel.rating = next;
      renderRating(reel);
    });
    container.append(button);
  }
}

function closePlayer() {
  $("#player").classList.add("hidden");
  video.pause();
  video.removeAttribute("src");
  video.load();
  state.playerIndex = -1;
  renderGrid();
  loadAccounts();
}

function step(delta) {
  const next = state.playerIndex + delta;
  if (next < 0 || next >= state.reels.length) {
    if (next >= state.reels.length && state.reels.length < state.total) {
      loadMore().then(() => openPlayer(next));
    }
    return;
  }
  openPlayer(next);
}

async function markCurrentWatched(watched) {
  const reel = state.reels[state.playerIndex];
  if (!reel) return;
  await api(`/api/reels/${reel.pk}/watch`, {
    method: "POST",
    body: { watched, seconds: video.currentTime || 0 },
  });
  reel.watched_at = watched ? new Date().toISOString() : null;
  renderPlayerControls(reel);
}

video.addEventListener("timeupdate", () => {
  if (autoMarked || state.playerIndex < 0) return;
  const reel = state.reels[state.playerIndex];
  if (!reel || reel.watched_at) return;
  const ratio = Number(state.settings.auto_watch_ratio ?? 0.6);
  const minSeconds = Number(state.settings.auto_watch_min_seconds ?? 3);
  const target = Math.max(minSeconds, (video.duration || reel.duration || 0) * ratio);
  if (video.currentTime >= target) {
    autoMarked = true;
    markCurrentWatched(true).catch(() => {});
  }
});

video.addEventListener("ended", () => {
  if (state.settings.autoplay_next !== false) step(1);
});

/* -- stats -------------------------------------------------------------- */
async function loadStats() {
  const account = state.filters.account;
  const data = await api(`/api/stats?account=${encodeURIComponent(account)}`);
  renderStats(data);
}

function renderStats(data) {
  const panel = $("#tab-stats");
  panel.replaceChildren();

  const totals = data.totals || {};
  const tiles = [
    ["סרטונים שנאספו", fmtCount(totals.reels), null],
    ["לא נצפו", fmtCount(totals.unseen), `${totals.reels ? Math.round((totals.unseen / totals.reels) * 100) : 0}% מהמאגר`],
    ["מועדפים", fmtCount(totals.favorites), null],
    ["ממוצע צפיות", fmtCount(Math.round(totals.avg_views || 0)), `שיא: ${fmtCount(totals.max_views)}`],
    ["ממוצע מעורבות", `${((totals.avg_engagement || 0) * 100).toFixed(2)}%`, null],
    ["אורך ממוצע", fmtDuration(totals.avg_duration), null],
  ];

  const row = document.createElement("div");
  row.className = "stat-row";
  tiles.forEach(([label, value, sub]) => {
    const tile = document.createElement("div");
    tile.className = "stat-tile";
    const labelNode = document.createElement("div");
    labelNode.className = "label";
    labelNode.textContent = label;
    const valueNode = document.createElement("div");
    valueNode.className = "value";
    valueNode.textContent = value;
    tile.append(labelNode, valueNode);
    if (sub) {
      const subNode = document.createElement("div");
      subNode.className = "sub";
      subNode.textContent = sub;
      tile.append(subNode);
    }
    row.append(tile);
  });
  panel.append(row);

  if (data.monthly?.length) {
    panel.append(monthlyChart(data.monthly));
  }

  if (data.top?.length) {
    const card = document.createElement("div");
    card.className = "chart-card";
    const title = document.createElement("h3");
    title.className = "chart-title";
    title.textContent = "הסרטונים עם הכי הרבה צפיות";
    card.append(title);
    card.append(reelTable(data.top));
    panel.append(card);
  }
}

function monthlyChart(monthly) {
  const card = document.createElement("div");
  card.className = "chart-card";

  const title = document.createElement("h3");
  title.className = "chart-title";
  title.textContent = "סרטונים שפורסמו לפי חודש";
  const sub = document.createElement("p");
  sub.className = "chart-sub";
  sub.textContent = "מבוסס רק על הסרטונים שנאספו למאגר המקומי";
  card.append(title, sub);

  const width = 720;
  const height = 240;
  const padding = { top: 16, right: 12, bottom: 28, left: 40 };
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const max = Math.max(...monthly.map((row) => row.reels), 1);
  const ticks = niceTicks(max);
  const scaleMax = ticks[ticks.length - 1];
  const bandWidth = plotWidth / monthly.length;
  const barWidth = Math.max(4, Math.min(bandWidth - 6, 42));

  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", `0 0 ${width} ${height}`);
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", "מספר סרטונים שפורסמו בכל חודש");

  const gridGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
  gridGroup.setAttribute("class", "chart-grid");
  ticks.forEach((tick) => {
    const y = padding.top + plotHeight - (tick / scaleMax) * plotHeight;
    const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
    line.setAttribute("x1", padding.left);
    line.setAttribute("x2", width - padding.right);
    line.setAttribute("y1", y);
    line.setAttribute("y2", y);
    if (tick === 0) line.setAttribute("class", "chart-baseline");
    gridGroup.append(line);

    const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
    label.setAttribute("class", "chart-value");
    label.setAttribute("x", padding.left - 8);
    label.setAttribute("y", y + 3);
    label.setAttribute("text-anchor", "end");
    label.textContent = String(tick);
    gridGroup.append(label);
  });
  svg.append(gridGroup);

  const tip = document.createElement("div");
  tip.className = "chart-tip hidden";

  monthly.forEach((row, index) => {
    const barHeight = (row.reels / scaleMax) * plotHeight;
    const x = padding.left + index * bandWidth + (bandWidth - barWidth) / 2;
    const y = padding.top + plotHeight - barHeight;

    const bar = document.createElementNS("http://www.w3.org/2000/svg", "path");
    bar.setAttribute("class", "chart-bar");
    bar.setAttribute("d", roundedTopBar(x, y, barWidth, barHeight, 4));
    svg.append(bar);

    const hit = document.createElementNS("http://www.w3.org/2000/svg", "rect");
    hit.setAttribute("class", "chart-hit");
    hit.setAttribute("x", padding.left + index * bandWidth);
    hit.setAttribute("y", padding.top);
    hit.setAttribute("width", bandWidth);
    hit.setAttribute("height", plotHeight);
    hit.addEventListener("pointerenter", () => {
      bar.classList.add("is-hover");
      tip.classList.remove("hidden");
      tip.textContent = `${row.month} · ${row.reels} סרטונים · ממוצע ${fmtCount(Math.round(row.avg_views || 0))} צפיות`;
      tip.style.left = `${((x + barWidth / 2) / width) * 100}%`;
      tip.style.top = `${(y / height) * 100}%`;
    });
    hit.addEventListener("pointerleave", () => {
      bar.classList.remove("is-hover");
      tip.classList.add("hidden");
    });
    svg.append(hit);

    const showLabel = monthly.length <= 12 || index % Math.ceil(monthly.length / 12) === 0;
    if (showLabel) {
      const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
      label.setAttribute("class", "chart-label");
      label.setAttribute("x", x + barWidth / 2);
      label.setAttribute("y", height - 10);
      label.setAttribute("text-anchor", "middle");
      label.textContent = row.month.slice(2);
      svg.append(label);
    }
  });

  const wrap = document.createElement("div");
  wrap.className = "chart-wrap";
  wrap.append(svg, tip);
  card.append(wrap);

  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "btn btn-ghost table-toggle";
  toggle.textContent = "הצג כטבלה";
  const table = document.createElement("table");
  table.className = "data-table hidden";
  table.innerHTML = "<thead><tr><th>חודש</th><th>סרטונים</th><th>ממוצע צפיות</th></tr></thead>";
  const tbody = document.createElement("tbody");
  monthly.forEach((row) => {
    const tr = document.createElement("tr");
    [row.month, full.format(row.reels), fmtCount(Math.round(row.avg_views || 0))].forEach((cell) => {
      const td = document.createElement("td");
      td.textContent = cell;
      tr.append(td);
    });
    tbody.append(tr);
  });
  table.append(tbody);
  toggle.addEventListener("click", () => {
    const hidden = table.classList.toggle("hidden");
    toggle.textContent = hidden ? "הצג כטבלה" : "הסתר טבלה";
  });
  card.append(toggle, table);
  return card;
}

function roundedTopBar(x, y, width, height, radius) {
  if (height <= 0) return "";
  const r = Math.min(radius, width / 2, height);
  return [
    `M ${x} ${y + height}`,
    `L ${x} ${y + r}`,
    `Q ${x} ${y} ${x + r} ${y}`,
    `L ${x + width - r} ${y}`,
    `Q ${x + width} ${y} ${x + width} ${y + r}`,
    `L ${x + width} ${y + height}`,
    "Z",
  ].join(" ");
}

function niceTicks(max) {
  const step = Math.max(1, Math.ceil(max / 4));
  const ticks = [];
  for (let value = 0; value <= max + step; value += step) ticks.push(value);
  return ticks;
}

function reelTable(rows) {
  const table = document.createElement("table");
  table.className = "data-table";
  table.innerHTML =
    "<thead><tr><th>חשבון</th><th>תאריך</th><th>צפיות</th><th>לייקים</th><th>מעורבות</th></tr></thead>";
  const tbody = document.createElement("tbody");
  rows.forEach((reel) => {
    const tr = document.createElement("tr");
    [
      `@${reel.account_username}`,
      fmtDate(reel.taken_at),
      fmtCount(reel.play_count),
      fmtCount(reel.like_count),
      `${(engagementRate(reel) * 100).toFixed(2)}%`,
    ].forEach((cell) => {
      const td = document.createElement("td");
      td.textContent = cell;
      tr.append(td);
    });
    tbody.append(tr);
  });
  table.append(tbody);
  return table;
}

/* -- activity ----------------------------------------------------------- */
async function loadActivity() {
  const data = await api("/api/sync-log");
  const panel = $("#tab-activity");
  panel.replaceChildren();

  const card = document.createElement("div");
  card.className = "chart-card";
  const title = document.createElement("h3");
  title.className = "chart-title";
  title.textContent = "היסטוריית סנכרון";
  card.append(title);

  if (!data.items.length) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "עוד לא בוצע סנכרון.";
    card.append(empty);
  } else {
    const table = document.createElement("table");
    table.className = "data-table";
    table.innerHTML =
      "<thead><tr><th>חשבון</th><th>התחיל</th><th>סטטוס</th><th>נמשכו</th><th>חדשים</th></tr></thead>";
    const tbody = document.createElement("tbody");
    data.items.forEach((entry) => {
      const tr = document.createElement("tr");
      [
        `@${entry.username || "?"}`,
        new Date(entry.started_at).toLocaleString("he-IL"),
        entry.error ? `שגיאה: ${entry.error}` : entry.status,
        full.format(entry.fetched || 0),
        full.format(entry.new_count || 0),
      ].forEach((cell) => {
        const td = document.createElement("td");
        td.textContent = cell;
        tr.append(td);
      });
      tbody.append(tr);
    });
    table.append(tbody);
    card.append(table);
  }
  panel.append(card);
}

/* -- settings ----------------------------------------------------------- */
function openSettings() {
  $("#s-blocked").value = (state.settings.blocked_keywords || []).join(", ");
  $("#s-ratio").value = state.settings.auto_watch_ratio ?? 0.6;
  $("#s-ratio-value").textContent = `${Math.round((state.settings.auto_watch_ratio ?? 0.6) * 100)}%`;
  $("#s-min-seconds").value = state.settings.auto_watch_min_seconds ?? 3;
  $("#s-autoplay").checked = state.settings.autoplay_next !== false;
  $("#settings-modal").classList.remove("hidden");
}

async function saveSettings() {
  const payload = {
    blocked_keywords: $("#s-blocked").value.split(",").map((word) => word.trim()).filter(Boolean),
    auto_watch_ratio: Number($("#s-ratio").value),
    auto_watch_min_seconds: Number($("#s-min-seconds").value),
    autoplay_next: $("#s-autoplay").checked,
    default_sort: $("#sort").value,
  };
  state.settings = await api("/api/settings", { method: "PUT", body: payload });
  $("#settings-modal").classList.add("hidden");
  toast("ההגדרות נשמרו");
  loadReels(true);
}

/* -- misc --------------------------------------------------------------- */
async function loadMore() {
  state.offset += state.limit;
  await loadReels(false);
}

function readFilterInputs() {
  state.filters.min_views = $("#f-min-views").value;
  state.filters.min_likes = $("#f-min-likes").value;
  state.filters.min_duration = $("#f-min-duration").value;
  state.filters.max_duration = $("#f-max-duration").value;
  state.filters.since = $("#f-since").value;
  state.filters.until = $("#f-until").value;
  state.filters.include_hidden = $("#f-include-hidden").checked;
  loadReels(true);
}

function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  localStorage.setItem("reels-lab-theme", theme);
}

function debounce(fn, delay) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

/* -- wiring ------------------------------------------------------------- */
$("#login-form").addEventListener("submit", login);
$("#add-account-form").addEventListener("submit", addAccount);
$("#logout").addEventListener("click", async () => {
  await api("/api/logout", { method: "POST" });
  await refreshStatus();
});
$("#sync-all").addEventListener("click", async () => {
  await api("/api/accounts/sync-all", { method: "POST", body: {} });
  toast("כל החשבונות נוספו לתור");
  pollJobs();
});
$("#open-settings").addEventListener("click", openSettings);
$("#settings-save").addEventListener("click", () => saveSettings().catch((err) => toast(err.message, true)));
$("#s-ratio").addEventListener("input", (event) => {
  $("#s-ratio-value").textContent = `${Math.round(Number(event.target.value) * 100)}%`;
});
$$("[data-close-modal]").forEach((button) =>
  button.addEventListener("click", () => button.closest(".modal").classList.add("hidden")),
);
$("#toggle-theme").addEventListener("click", () => {
  const current = document.documentElement.dataset.theme
    || (matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark");
  applyTheme(current === "dark" ? "light" : "dark");
});

$("#status-chips").addEventListener("click", (event) => {
  const chip = event.target.closest(".chip");
  if (!chip) return;
  $$("#status-chips .chip").forEach((node) => node.classList.toggle("is-active", node === chip));
  state.filters.status = chip.dataset.status;
  loadReels(true);
});

$("#sort").addEventListener("change", (event) => {
  state.filters.sort = event.target.value;
  loadReels(true);
});
$("#search").addEventListener("input", debounce((event) => {
  state.filters.q = event.target.value.trim();
  loadReels(true);
}, 350));

["#f-min-views", "#f-min-likes", "#f-min-duration", "#f-max-duration", "#f-since", "#f-until"]
  .forEach((selector) => $(selector).addEventListener("change", readFilterInputs));
$("#f-include-hidden").addEventListener("change", readFilterInputs);
$("#reset-filters").addEventListener("click", () => {
  ["#f-min-views", "#f-min-likes", "#f-min-duration", "#f-max-duration", "#f-since", "#f-until"]
    .forEach((selector) => { $(selector).value = ""; });
  $("#f-include-hidden").checked = false;
  $("#search").value = "";
  state.filters.q = "";
  readFilterInputs();
});

$("#load-more").addEventListener("click", () => loadMore());
$("#mark-all-seen").addEventListener("click", () => bulkMark(true).catch((err) => toast(err.message, true)));
$("#mark-all-unseen").addEventListener("click", () => bulkMark(false).catch((err) => toast(err.message, true)));
$("#play-unseen").addEventListener("click", async () => {
  state.filters.status = "unseen";
  $$("#status-chips .chip").forEach((node) =>
    node.classList.toggle("is-active", node.dataset.status === "unseen"));
  await loadReels(true);
  if (state.reels.length) openPlayer(0);
  else toast("אין סרטונים שלא נצפו בסינון הנוכחי");
});

$$(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    $$(".tab").forEach((node) => node.classList.toggle("is-active", node === tab));
    $$(".tab-panel").forEach((panel) => panel.classList.add("hidden"));
    $(`#tab-${tab.dataset.tab}`).classList.remove("hidden");
    if (tab.dataset.tab === "stats") loadStats().catch((err) => toast(err.message, true));
    if (tab.dataset.tab === "activity") loadActivity().catch((err) => toast(err.message, true));
  });
});

$("#player-close").addEventListener("click", closePlayer);
$("#player-prev").addEventListener("click", () => step(-1));
$("#player-next").addEventListener("click", () => step(1));
$("#player-watched").addEventListener("click", async () => {
  const reel = state.reels[state.playerIndex];
  if (reel) await markCurrentWatched(!reel.watched_at);
});
$("#player-fav").addEventListener("click", async () => {
  const reel = state.reels[state.playerIndex];
  if (!reel) return;
  const next = !reel.is_favorite;
  await api(`/api/reels/${reel.pk}/favorite`, { method: "POST", body: { value: next } });
  reel.is_favorite = next ? 1 : 0;
  renderPlayerControls(reel);
});
$("#player-hide").addEventListener("click", async () => {
  const reel = state.reels[state.playerIndex];
  if (!reel) return;
  const next = !reel.is_hidden;
  await api(`/api/reels/${reel.pk}/hide`, { method: "POST", body: { value: next } });
  reel.is_hidden = next ? 1 : 0;
  renderPlayerControls(reel);
});
$("#player-note").addEventListener("change", async (event) => {
  const reel = state.reels[state.playerIndex];
  if (!reel) return;
  await api(`/api/reels/${reel.pk}/annotate`, { method: "POST", body: { note: event.target.value } });
  reel.note = event.target.value;
  toast("ההערה נשמרה");
});

document.addEventListener("keydown", (event) => {
  if ($("#player").classList.contains("hidden")) return;
  if (["INPUT", "TEXTAREA"].includes(event.target.tagName)) return;
  const keys = {
    Escape: closePlayer,
    ArrowLeft: () => step(1),
    ArrowRight: () => step(-1),
    " ": () => (video.paused ? video.play() : video.pause()),
    w: () => $("#player-watched").click(),
    f: () => $("#player-fav").click(),
    h: () => $("#player-hide").click(),
  };
  const handler = keys[event.key] || keys[event.key.toLowerCase()];
  if (handler) {
    event.preventDefault();
    handler();
  }
});

/* -- boot --------------------------------------------------------------- */
const savedTheme = localStorage.getItem("reels-lab-theme");
if (savedTheme) applyTheme(savedTheme);

(async function boot() {
  try {
    await refreshStatus();
    await loadAccounts();
    await loadReels(true);
    pollJobs();
  } catch (err) {
    toast(err.message, true);
  }
})();
