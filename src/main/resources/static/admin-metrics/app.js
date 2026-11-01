// Admin health page: two-step admin sign-in, then GET /api/admin/metrics every 30 s while the tab is visible.
// Served as its own file because the production CSP allows scripts from 'self' only. Server text only ever
// reaches the page as text nodes. A card whose field the API does not send stays hidden; nothing shows as zero.
(() => {
  // Limits behind the verdict, the vitals and the colours.
  const POLL_MS = 30000;
  const FETCH_TIMEOUT_MS = 15000; // a hung request must end in the stale state, not freeze the refresh
  const LIMIT_5XX = 0.001; // share of requests
  const BUDGET_MS = 1000;
  const SLOW_MAX_MS = 5000;
  const WARN_MS = 400;
  const FAST_MS = 200;
  const PAY_BAD = 0.02;
  const PAY_WARN = 0.01;
  const POOL_WATCH = 0.8;
  const HEAP_WATCH = 0.85;
  const BAR_WARN = 0.6; // bar turns amber at this share of its limit
  const JOB_GRACE_MS = 120000;
  const ROUTE_ROWS = 25;
  const WATCH_ROWS = 5;
  const SEVERITY = ['DOWN', '5xx', 'DEP', 'JOB', 'POOL', 'HEAP', 'SLOW', 'PAY'];
  const CHIP = { DOWN: 'c-bad', '5xx': 'c-bad', DEP: 'c-bad', JOB: 'c-warn', POOL: 'c-warn', HEAP: 'c-warn', SLOW: 'c-warn', PAY: 'c-warn' };

  const $ = (id) => document.getElementById(id);
  const api = location.origin;
  const el = (tag, cls, ...kids) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    n.append(...kids.filter((k) => k !== null && k !== undefined));
    return n;
  };
  const fill = (id, ...kids) => $(id).replaceChildren(...kids.filter((k) => k !== null && k !== undefined));
  const show = (id, on) => { $(id).hidden = !on; return on; };
  const noRows = () => el('div', 'empty', 'No rows');
  const titled = (node, title) => { node.title = title; return node; };
  const sized = (node, prop, value) => { node.style[prop] = value; return node; };

  const num = new Intl.NumberFormat('en');
  const fmt = (n) => num.format(n);
  const compactNum = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 2 });
  const compact = (n) => compactNum.format(n);
  const bytes = (n) => n >= 1e9 ? (n / 1e9).toFixed(2) + ' GB' : n >= 1e6 ? (n / 1e6).toFixed(1) + ' MB' : n >= 1e3 ? Math.round(n / 1e3) + ' kB' : n + ' B';
  const ms = (n) => n >= 1000 ? (n / 1000).toFixed(1) + ' s' : n + ' ms';
  const dur = (s) => { const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60); return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`; };
  const pct = (ratio, digits = 1) => (ratio * 100).toFixed(digits) + '%';
  const widthPct = (ratio) => (Math.min(1, Math.max(0, ratio)) * 100).toFixed(1) + '%';
  const clock = (t) => new Date(t).toLocaleTimeString('en-GB');
  const hourMin = (t) => new Date(t).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  const utcMinute = (iso) => iso.slice(0, 16).replace('T', ' ');
  const ago = (iso, now) => { const min = Math.round((now - Date.parse(iso)) / 60000); return min < 1 ? 'just now' : min < 90 ? `${min} min ago` : `${Math.round(min / 60)} h ago`; };
  const words = (s) => { const t = String(s).replace(/[-_]+/g, ' ').toLowerCase(); return t.charAt(0).toUpperCase() + t.slice(1); };
  const plural = (n, one) => `${fmt(n)} ${one}${n === 1 ? '' : 's'}`;
  const share = (n, total) => { const r = total ? n / total : 0; return r > 0 && r < 0.0001 ? '<0.01%' : pct(r, 2); };
  const barTone = (ratio) => ratio >= 1 ? 'c-bad' : ratio >= BAR_WARN ? 'c-warn' : 'c-accent';
  const latText = (v) => v >= BUDGET_MS ? 't-bad' : v >= WARN_MS ? 't-warn' : '';
  const logPct = (v) => widthPct((Math.log10(Math.max(v, 1)) - 1) / 3); // 10 ms to 10 s

  const store = {
    get: (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch (e) { /* storage blocked */ } },
  };

  let token = null;
  try { token = sessionStorage.getItem('shifa.admin.token'); } catch (e) { token = null; }
  let timer = null;
  let countdown = null;
  let nextAt = 0;
  let inFlight = false;
  let lastOk = null;
  let lastMetrics = null;
  let readiness = null;
  let isStale = false;
  let session = 0; // bumped on sign-out: a response that was already on its way is dropped
  let routeSort = store.get('shifa.admin.routeSort') === 'busy' ? 'busy' : 'worst';
  let routesAll = false;
  let activityRange = ['24h', '7d'].includes(store.get('shifa.admin.activityRange')) ? store.get('shifa.admin.activityRange') : '30d';
  let trafficRange = ['7d', '30d'].includes(store.get('shifa.admin.trafficRange')) ? store.get('shifa.admin.trafficRange') : '24h';

  // Environment: the Railway hosts of both environments are told apart by name, so dev is never labelled production.
  const host = location.hostname;
  const env = host === 'api.shifa.uz' ? ['PRODUCTION', '']
    : host === 'shifa-doc-backend-mvp-production.up.railway.app' ? ['PRODUCTION · LEGACY HOST', '']
    : host === 'api-dev.shifa.uz' || host.endsWith('-development.up.railway.app') ? ['DEV', 'dev'] : ['LOCAL', 'local'];
  $('env').textContent = env[0];
  $('env').className = 'env ' + env[1];
  $('where').textContent = host;

  const bar = (ratio, tone, tall) => el('div', 'track' + (tall ? ' tall' : ''), sized(el('div', tone), 'width', widthPct(ratio)));
  const stackBar = (id, parts, minPct) => {
    const total = parts.reduce((s, p) => s + p.count, 0);
    fill(id, ...parts.filter((p) => p.count > 0).map((p) =>
      titled(sized(el('div', p.tone), 'width', Math.max(100 * p.count / total, minPct) + '%'), `${p.label}: ${fmt(p.count)}`)));
  };
  const legend = (id, parts) => {
    const total = parts.reduce((s, p) => s + p.count, 0);
    fill(id, ...parts.flatMap((p) => [el('i', 'sw ' + p.tone), el('span', 'min0', p.label), el('span', 'right', fmt(p.count)), el('span', 'right muted', share(p.count, total))]));
  };
  const kv = (id, rows) => fill(id, ...rows.flatMap(([k, v, cls]) => [el('dt', '', k), el('dd', cls || '', v)]));
  const mini = (label, value, sub, tone, extra) => el('div', 'mini ' + (extra || ''), el('div', 'label', label), el('div', 'value ' + (tone || ''), value), sub ? el('div', 'sub', sub) : null);

  // ---- Is anything wrong: the rules from the design, computed here so the server only reports numbers.
  const isOverdue = (job, now) => job.nextRunAt && Date.parse(job.nextRunAt) + JOB_GRACE_MS < now;
  const depStale = (d, now) => {
    const seen = d.lastSuccessAt || d.lastWebhookAt;
    return d.staleAfterSeconds && seen && now - Date.parse(seen) > d.staleAfterSeconds * 1000;
  };
  const gateways = (rows) => {
    const by = new Map();
    rows.forEach((r) => {
      const g = by.get(r.gateway) || { name: r.gateway, ok: 0, pending: 0, failed: 0, other: 0 };
      if (r.status === 'PAID') g.ok += r.count;
      else if (r.status === 'PENDING' || r.status === 'AUTHORIZED') g.pending += r.count;
      else if (r.status === 'FAILED') g.failed += r.count;
      else g.other += r.count; // cancelled, refunded
      by.set(r.gateway, g);
    });
    return [...by.values()].map((g) => ({ ...g, total: g.ok + g.pending + g.failed + g.other, rate: g.failed / (g.ok + g.pending + g.failed + g.other || 1) }));
  };

  const watchItems = (m) => {
    const now = Date.parse(m.generatedAt);
    const out = [];
    const add = (level, title, detail, link, href) => out.push({ level, title, detail, link, href });
    if (readiness !== 'UP') add('DOWN', `Readiness ${readiness === null ? 'unreachable' : readiness}`, 'The readiness probe does not report UP.', 'Vitals', '#vitals');

    const routes = m.http.routes;
    const erroring = routes.filter((r) => r.serverErrors > 0).sort((a, b) => b.serverErrors - a.serverErrors);
    if (erroring.length) {
      const w = erroring[0];
      add('5xx', `${plural(m.http.serverErrors, 'server error')} since deploy, ${fmt(w.serverErrors)} on ${w.method} ${w.route}`,
        erroring.length > 1 ? `${erroring.length} routes answered 5xx.` : 'Only this route answered 5xx.', 'Routes', '#routes');
    }
    (m.dependencies || []).filter((d) => d.state !== 'UP' || depStale(d, now)).forEach((d) =>
      add('DEP', `${depName(d)}: ${d.state === 'UP' ? 'no recent success' : d.state}`, d.state === 'UP' ? `Older than ${dur(d.staleAfterSeconds)}.` : 'Reported by the backend.', 'Dependencies', '#dependencies'));
    (m.jobs || []).filter((j) => j.lastResult === 'FAILED' || isOverdue(j, now)).forEach((j) =>
      add('JOB', `${jobLabel(j)[0]}: ${j.lastResult === 'FAILED' ? 'last run failed' : 'overdue'}`, `Last started ${ago(j.lastRunAt, now)}.`, 'Jobs', '#jobs'));
    const pool = m.database.pool;
    if (pool.pending > 0 || pool.active / (pool.max || 1) >= POOL_WATCH) add('POOL', `DB pool ${pool.active} active of ${pool.max}`, `${plural(pool.pending, 'thread')} waiting for a connection.`, 'Vitals', '#vitals');
    const heap = m.jvm.heapUsedMb / (m.jvm.heapMaxMb || 1);
    if (heap >= HEAP_WATCH) add('HEAP', `Heap at ${pct(heap, 0)}`, `${fmt(m.jvm.heapUsedMb)} of ${fmt(m.jvm.heapMaxMb)} MB.`, 'Vitals', '#vitals');
    const slow = routes.filter((r) => r.averageMs >= BUDGET_MS || r.recentMaxMs >= SLOW_MAX_MS).sort((a, b) => b.averageMs - a.averageMs);
    if (slow.length) {
      const w = slow[0];
      add('SLOW', `${w.method} ${w.route}: avg ${ms(w.averageMs)}, recent max ${ms(w.recentMaxMs)}`, slow.length > 1 ? `${slow.length} routes over budget.` : 'Over the 1 s average or 5 s maximum budget.', 'Routes', '#routes');
    }
    const windowed = m.paymentsLast24h != null;
    gateways(windowed ? m.paymentsLast24h : m.domain.paymentsByStatus).filter((g) => g.rate >= PAY_BAD).sort((a, b) => b.rate - a.rate).forEach((g) =>
      add('PAY', `${words(g.name)} failure rate ${pct(g.rate)} (${fmt(g.failed)} of ${fmt(g.total)})`, windowed ? 'Last 24 hours.' : 'All-time numbers.', 'Payments', '#payments'));
    return out.sort((a, b) => SEVERITY.indexOf(a.level) - SEVERITY.indexOf(b.level));
  };

  const renderVerdict = (m) => {
    const items = watchItems(m);
    const up = readiness === 'UP';
    $('verdict-main').className = 'verdict-main' + (up ? '' : ' bad');
    $('verdict-dot').className = 'verdict-dot ' + (up ? 'ok' : 'bad');
    $('verdict-state').className = 'verdict-state ' + (up ? 't-ok' : 't-bad');
    $('verdict-label').textContent = up ? 'UP · READY' : readiness === null ? 'UNREACHABLE' : `${readiness} · NOT READY`;
    $('verdict-head').textContent = !up ? `Degraded: readiness ${readiness === null ? 'probe unreachable' : readiness}`
      : items.length === 0 ? 'Serving normally. Nothing to watch.'
      : `Serving normally. ${items.length === 1 ? '1 thing' : items.length + ' things'} to watch.`;
    const levels = new Set(items.map((i) => i.level));
    const rate = m.http.requests ? m.http.serverErrors / m.http.requests : 0;
    const clean = [up && 'readiness', rate < LIMIT_5XX && 'error rate', !levels.has('POOL') && 'pool', !levels.has('HEAP') && 'heap'].filter(Boolean);
    $('verdict-sub').textContent = clean.length ? `${words(clean.join(', ').replace(/, ([^,]*)$/, ' and $1'))} ${clean.length === 1 ? 'is' : 'are'} within limits.` : '';
    const rows = items.slice(0, WATCH_ROWS).map((w) => {
      const link = el('a', 'watch-link', w.link); link.href = w.href;
      return el('div', 'watch-row', el('span', 'chip ' + CHIP[w.level], w.level), el('div', 'min0', el('div', 'strong', w.title), el('div', 'small muted', w.detail)), link);
    });
    fill('watch', ...(rows.length ? rows : [el('div', 'small muted', 'No rule is tripped: routes, pool, heap and payments are all clear.')]),
      items.length > WATCH_ROWS ? el('div', 'small muted', `+${items.length - WATCH_ROWS} more`) : null);
  };

  const renderVitals = (m) => {
    const rate = m.http.requests ? m.http.serverErrors / m.http.requests : 0;
    const rateText = rate === 0 ? '0%' : rate < 0.0001 ? pct(rate, 4) : pct(rate, 2);
    const heap = m.jvm.heapUsedMb / (m.jvm.heapMaxMb || 1);
    const pool = m.database.pool;
    const used = pool.active / (pool.max || 1); // idle connections are spare capacity, a warmed-up pool is not a full one
    const slowest = m.http.routes.reduce((a, b) => (!a || b.averageMs > a.averageMs ? b : a), null);
    const up = readiness === 'UP';
    const tile = (label, limit, value, ratio, tone, sub, subCls) => el('div', 'tile',
      el('div', 'vital-head', el('span', '', label), el('span', '', limit)),
      el('div', 'vital-value', value), bar(ratio, tone), el('div', 'small ' + (subCls || 'muted'), sub));
    fill('vitals-list',
      tile('5xx rate', 'limit 0.1%', rateText, rate / LIMIT_5XX, barTone(rate / LIMIT_5XX), `${fmt(m.http.serverErrors)} of ${fmt(m.http.requests)} since deploy`),
      tile('Heap', `${fmt(m.jvm.heapMaxMb)} MB`, pct(heap, 0), heap, barTone(heap), `${fmt(m.jvm.heapUsedMb)} MB used`),
      tile('DB pool', `${fmt(pool.max)} max`, `${pool.active} / ${pool.max} active`, used, barTone(used), `${fmt(pool.idle)} idle · ${plural(pool.pending, 'thread')} waiting`, pool.pending > 0 ? 't-warn' : ''),
      tile('Slowest route avg', 'budget 1 s', slowest ? ms(slowest.averageMs) : '—', slowest ? slowest.averageMs / BUDGET_MS : 0, barTone(slowest ? slowest.averageMs / BUDGET_MS : 0), slowest ? `${slowest.method} ${slowest.route}` : 'No requests yet'),
      tile('Readiness', '/actuator', up ? 'UP' : 'DOWN', 1, up ? 'c-accent' : 'c-bad', up ? `probed every ${POLL_MS / 1000} s` : `probe says ${readiness === null ? 'unreachable' : readiness}`, up ? '' : 't-bad'));
  };

  // ---- Where is it wrong.
  const DEPS = { POSTGRES: ['PostgreSQL', 'primary + pgvector'], CLICK: ['Click', 'payments'], STRIPE: ['Stripe', 'payments'], DAILY: ['Daily.co', 'video rooms'], FIREBASE: ['Firebase', 'OTP + FCM push'], LLM: ['LLM providers', 'OpenAI / Gemini / Grok'] };
  const depName = (d) => (DEPS[d.name] || [words(d.name)])[0];
  const renderDeps = (m) => {
    if (!show('dependencies', Array.isArray(m.dependencies) && m.dependencies.length > 0)) return;
    const now = Date.parse(m.generatedAt);
    fill('deps-list', ...m.dependencies.map((d) => {
      const stale = depStale(d, now);
      const tone = d.state === 'DOWN' ? 'c-bad' : d.state !== 'UP' || stale ? 'c-warn' : 'c-accent';
      const stat = d.lastWebhookAt ? `webhook ${ago(d.lastWebhookAt, now)}` : d.latencyMs != null ? `ping ${ms(d.latencyMs)}`
        : d.fcmAcceptRatio24h != null ? `FCM ${pct(d.fcmAcceptRatio24h)} ok` : d.p95Ms24h != null ? `p95 ${ms(d.p95Ms24h)}` : d.lastSuccessAt ? `ok ${ago(d.lastSuccessAt, now)}` : d.state;
      return el('div', 'tile dep', el('div', 'dep-name', el('span', 'dot ' + tone), depName(d)), el('div', 'small muted', (DEPS[d.name] || [null, ''])[1]), el('div', 'mono', stat));
    }));
  };

  const renderRoutes = (m) => {
    const http = m.http;
    const sorted = [...http.routes].sort(routeSort === 'busy' ? (a, b) => b.requests - a.requests
      : (a, b) => b.serverErrors - a.serverErrors || b.recentMaxMs - a.recentMaxMs);
    const shown = routesAll ? sorted : sorted.slice(0, ROUTE_ROWS);
    const hasP95 = http.routes.some((r) => r.p95Ms != null);
    $('routes-table').className = 'rt' + (hasP95 ? ' has-p95' : '');
    $('tick-label').textContent = hasP95 ? 'p95' : 'recent max';
    $('sort-worst').setAttribute('aria-pressed', String(routeSort === 'worst'));
    $('sort-busy').setAttribute('aria-pressed', String(routeSort === 'busy'));
    const th = (text, right) => { const c = el('div', 'th' + (right ? ' right' : ''), text); c.setAttribute('role', 'columnheader'); return c; };
    const td = (cls, ...kids) => { const c = el('div', 'td ' + cls, ...kids); c.setAttribute('role', 'cell'); return c; };
    const row = (...cells) => { const r = el('div', '', ...cells); r.setAttribute('role', 'row'); return r; };
    const rows = shown.map((r) => {
      const tickAt = r.p95Ms != null ? r.p95Ms : r.recentMaxMs;
      const avg = sized(el('div', 'avg ' + (r.averageMs >= BUDGET_MS ? 'c-bad' : r.averageMs >= FAST_MS ? 'c-warn' : 'c-accent')), 'width', logPct(r.averageMs));
      const tick = sized(el('div', 'tick'), 'left', logPct(tickAt));
      return row(
        td('mono ellipsis', el('span', 'muted', r.method), ` ${r.route}`),
        td('right', fmt(r.requests)),
        td('', titled(el('div', 'lat', avg, tick, el('div', 'budget')), `avg ${ms(r.averageMs)} · ${hasP95 ? 'p95 ' + (r.p95Ms != null ? ms(r.p95Ms) : '—') + ' · ' : ''}recent max ${ms(r.recentMaxMs)}`)),
        td('right ' + latText(r.averageMs), ms(r.averageMs)),
        hasP95 ? td('right ' + (r.p95Ms != null ? latText(r.p95Ms) : 'muted'), r.p95Ms != null ? ms(r.p95Ms) : '—') : null,
        td('right ' + latText(r.recentMaxMs), ms(r.recentMaxMs)),
        td('right ' + (r.serverErrors ? 't-bad strong' : 'muted'), r.serverErrors ? fmt(r.serverErrors) : '—'));
    });
    fill('routes-table', row(th('Route'), th('Requests', true), th('Latency (log scale, 10 ms – 10 s)'), th('Avg', true), hasP95 ? th('p95', true) : null, th('Max', true), th('5xx', true)), ...rows);
    if (!shown.length) $('routes-table').append(noRows());
    const more = http.routes.length > ROUTE_ROWS;
    if (show('routes-all', more)) $('routes-all').textContent = routesAll ? `Show first ${ROUTE_ROWS}` : `Show all ${fmt(http.routes.length)}`;
    $('routes-note').textContent = `Totals since ${utcMinute(http.since)} UTC (last deploy): ${fmt(http.requests)} requests · ${fmt(http.serverErrors)} answered 5xx. Counters reset on every deploy.`;
  };

  const STATUS = [['2xx', '2xx success', 'c-accent'], ['304', '304 not modified', 'c-soft'], ['3xxOther', 'Other 3xx', 'c-dim'], ['401', '401 unauthorized', 'c-pending'], ['403', '403 forbidden', 'c-warn'], ['4xxOther', '404 / 422 / other 4xx', 'c-grey'], ['5xx', '5xx', 'c-bad']];
  const RANGES = {
    '24h': { title: '24 h', per: 'hour', compare: 'same hours yesterday', span: '24 hours' },
    '7d': { title: '7 days', per: '6 hours', compare: 'previous 7 days', span: '7 days' },
    '30d': { title: '30 days', per: 'day', compare: 'previous 30 days', span: '30 days' },
  };
  const percentChange = (now, before) => (before ? Math.round(100 * (now - before) / before) : null);
  const signed = (n) => `${n >= 0 ? '+' : ''}${n}%`;
  const bucketLabel = (bucketSeconds, iso) => (bucketSeconds === 3600 ? `${String(new Date(iso).getHours()).padStart(2, '0')}:00`
    : bucketSeconds < 86400 ? new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
    : new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' }));
  // Exact counts sit in a row above the columns: flat when every one fits its column, else turned on end.
  const fitNumbers = (id) => {
    const chart = $(id);
    chart.classList.add('flat');
    if ([...chart.querySelectorAll('.num')].some((n) => n.offsetWidth > n.nextElementSibling.clientWidth)) chart.classList.remove('flat');
  };
  const renderRangeButtons = () => {
    document.querySelectorAll('[data-range]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.range === trafficRange)));
    document.querySelectorAll('[data-activity-range]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.activityRange === activityRange)));
  };
  const renderTraffic = (t) => {
    const r = RANGES[t.range];
    $('traffic-title').textContent = `Traffic, last ${r.title}`; // set here, so a heading never sits on another range's numbers
    $('traffic-unit').textContent = `requests / ${r.per}`;
    const change = percentChange;
    const reqChange = change(t.comparableRequests, t.previousRequests);
    const p95Change = t.comparableP95Ms != null ? change(t.comparableP95Ms, t.previousP95Ms) : null;
    const none = 'no earlier period yet';
    const avail = lastMetrics && lastMetrics.http.availability30d;
    const when = (iso) => bucketLabel(t.bucketSeconds === 3600 ? 21600 : t.bucketSeconds, iso); // always shows the date
    const label = (iso) => bucketLabel(t.bucketSeconds, iso);
    const kpi = (name, value, sub, tone) => el('div', 'mini kpi', el('div', 'label', name), el('div', 'value', value), el('div', 'small ' + (tone || 'muted'), sub));
    fill('traffic-kpis',
      kpi('Requests', fmt(t.requests), reqChange !== null ? `${signed(reqChange)} vs ${r.compare}` : none),
      kpi('Peak rate', `${fmt(t.peakRps)} rps`, t.peakAt ? new Date(t.peakAt).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—'),
      kpi('p50 / p95', t.p95Ms != null ? `${fmt(t.p50Ms)} / ${ms(t.p95Ms)}` : '—', p95Change !== null ? `p95 ${signed(p95Change)} vs ${r.compare}` : none, p95Change !== null && p95Change > 10 ? 't-warn' : ''),
      kpi('p99', t.p99Ms != null ? ms(t.p99Ms) : '—', ''),
      avail ? kpi('Error budget', pct(avail.ratio, 2), `SLO ${pct(avail.slo)} · 30 d`, avail.ratio >= avail.slo ? 't-ok' : 't-bad') : null);

    const peak = Math.max(1, ...t.buckets.map((b) => b.requests));
    fill('hours', ...t.buckets.flatMap((b) => {
      const tip = `${label(b.start)} · ${fmt(b.requests)} req${b.p95Ms != null ? ` · p95 ${ms(b.p95Ms)}` : ''}${b.serverErrors ? ` · ${fmt(b.serverErrors)} 5xx` : ''}`;
      return [titled(el('span', 'num', fmt(b.requests)), tip), titled(el('div', 'hour',
        sized(el('div', 'h ' + (b.serverErrors > 0 ? 'c-bad' : 'c-accent')), 'height', widthPct(b.requests / peak)),
        b.p95Ms != null ? sized(el('div', 'p'), 'bottom', widthPct(b.p95Ms / 800)) : null), tip)];
    }));
    fitNumbers('hours');
    const top = t.buckets.reduce((a, b) => (b.requests > a.requests ? b : a), t.buckets[0]);
    $('hours').setAttribute('aria-label', `${fmt(t.requests)} requests in ${r.span}, busiest ${label(top.start)} with ${fmt(top.requests)}, ${fmt(t.byStatus['5xx'] || 0)} answered 5xx`);
    $('hours-first').textContent = label(t.buckets[0].start);
    $('hours-peak').textContent = top.requests ? `peak ${label(top.start)}` : '';

    const parts = STATUS.map(([key, name, tone]) => ({ key, label: name, tone, count: t.byStatus[key] || 0 })).filter((p) => p.key !== '3xxOther' || p.count > 0);
    stackBar('status-bar', parts, 0.4);
    legend('status-legend', parts);
    fill('top4xx', ...(t.top4xx.length ? t.top4xx.slice(0, 4).map((e) => el('div', 'row-4xx',
      el('span', 'mono t-warn', String(e.status)),
      el('div', 'min0', el('div', 'mono ellipsis', `${e.method} ${e.route}`), e.reason ? el('div', 'small muted', words(e.reason)) : null),
      el('span', 'right', fmt(e.count)))) : [noRows()]));
    const partial = !t.collectingSince || Date.parse(t.collectingSince) > Date.parse(t.from);
    $('traffic-note').textContent = `/api requests only, saved every minute. ${!t.collectingSince ? 'Nothing saved yet.' : partial ? `Collecting since ${when(t.collectingSince)}, so this covers less than ${r.span}.` : 'Survives deploys.'}${t.bucketSeconds === 86400 ? ' Days are UTC.' : ''}`;
  };

  // One view at a time, fetched on every poll. Only the latest request may touch the page: a slow older answer is dropped.
  const rangeLoader = (path, rangeOf, onData, onError) => {
    let latest = 0;
    return async () => {
      if (!token) return;
      const mine = session, range = rangeOf(), request = ++latest;
      try {
        const r = await fetch(`${api}/api/admin/metrics/${path}?range=${range}`, { headers: { Authorization: 'Bearer ' + token }, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
        if (mine !== session || request !== latest) return;
        if (r.status === 401 || r.status === 403) return signOut('Session ended. Sign in again.');
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const body = await r.json();
        if (mine !== session || request !== latest) return;
        onData(body);
      } catch (e) {
        if (mine !== session || request !== latest) return;
        onError(range);
      }
    };
  };
  const loadTraffic = rangeLoader('traffic', () => trafficRange, (t) => {
    show('traffic-error', false);
    show('traffic', true);
    renderTraffic(t);
  }, (range) => {
    $('traffic-error').textContent = `Traffic for ${RANGES[range].span} could not be read. Trying again on the next refresh.`;
    show('traffic-error', true);
    show('traffic', $('hours').childElementCount > 0); // keeps the last good view
  });

  const renderClients = (m) => {
    const list = m.clients24h;
    if (!show('clients', Array.isArray(list) && list.length > 0)) return;
    const total = list.reduce((s, c) => s + c.requests, 0) || 1;
    const max = Math.max(1, ...list.map((c) => c.requests));
    fill('clients-list', ...list.map((c) => el('div', '',
      el('div', 'bar-cell', el('span', '', `${words(c.app)} ${c.platform ? words(c.platform) : ''} `, el('span', 'mono muted', c.version || '')), bar(c.requests / max, c.legacy ? 'c-warn' : c.app === 'PATIENT' ? 'c-accent' : c.app === 'DOCTOR' ? 'c-accent2' : 'c-dim')),
      el('div', 'right', pct(c.requests / total)),
      el('div', 'right small ' + (c.legacy ? 't-warn' : 'muted'), c.legacy ? 'legacy' : ''))));
    const hosts = m.hosts24h || [];
    const hostTotal = hosts.reduce((s, h) => s + h.requests, 0);
    const legacy = hosts.filter((h) => h.host !== 'api.shifa.uz').reduce((s, h) => s + h.requests, 0);
    if (show('clients-foot', hostTotal > 0)) $('clients-foot').textContent = `Legacy host *.up.railway.app: ${pct(legacy / hostTotal)} of traffic`;
  };

  const funnel = (name, steps, rateText, good) => el('div', 'stack',
    el('div', 'head', el('span', 'strong', name), el('span', 'strong ' + (good ? 't-ok' : 't-warn'), rateText)),
    ...steps.map(([label, n]) => el('div', 'funnel-row', el('span', 'muted', label), bar(n / (steps[0][1] || 1), 'c-accent2', true), el('span', 'right', fmt(n)))));
  const renderAuth = (m) => {
    const a = m.auth24h, c = m.checkout24h;
    if (!show('auth', a != null || c != null)) return;
    const blocks = [];
    if (a) {
      const r = a.otpVerified / (a.otpSent || 1);
      blocks.push(funnel('Patient phone OTP', [['OTP sent', a.otpSent], ['Code verified', a.otpVerified], ['First request', a.firstRequestAfterLogin]], `${pct(r, 0)} verified`, r >= 0.8));
    }
    if (c) {
      const r = c.paid / (c.opened || 1);
      blocks.push(funnel('Checkout → paid', [['Checkout opened', c.opened], ['Gateway redirect', c.redirected], ['Webhook paid', c.paid]], `${pct(r, 0)} paid${c.medianSecondsToWebhook != null ? ` · median ${c.medianSecondsToWebhook} s` : ''}`, r >= 0.85));
    }
    fill('funnels', ...blocks);
    if (!show('auth-kv', a != null)) return;
    const sessions = a.sessionsByApp || {};
    kv('auth-kv', [
      ['Sessions by app', Object.entries(sessions).map(([k, v]) => `${fmt(v)} ${k.toLowerCase()}`).join(' · ') || '—'],
      ['401 → forced logout', `${fmt(a.unauthorized401)}${a.unauthorizedExpiredDoctorToken != null ? ` (expired doctor token: ${fmt(a.unauthorizedExpiredDoctorToken)})` : ''}`],
      ['Sessions revoked', fmt(a.sessionsRevoked)],
      ['Rate-limited (429)', `${fmt(a.rateLimited429)}${a.rateLimitedTopSourcesCount != null ? ` · ${fmt(a.rateLimitedTopSourcesCount)} from top 3 sources` : ''}`, a.rateLimited429 > 0 ? 't-warn' : ''],
    ]);
  };

  const AI_NAMES = { openai: 'OpenAI', gemini: 'Gemini', grok: 'Grok' };
  const renderAi = (m) => {
    const list = m.ai24h;
    if (!show('ai', Array.isArray(list))) return;
    const hasCost = list.some((a) => a.costUsd != null);
    const money = (n) => '$' + n.toFixed(2);
    $('ai-table').className = 'ai' + (hasCost ? ' has-cost' : '');
    const th = (t, right) => el('div', 'th' + (right ? ' right' : ''), t);
    fill('ai-table', el('div', '', th('Provider'), th('Calls', true), th('p95', true), th('Err', true), hasCost ? th('Cost', true) : null),
      ...list.map((a) => el('div', '',
        el('div', 'td min0', `${AI_NAMES[a.provider.toLowerCase()] || a.provider} `, a.use ? el('span', 'small muted', words(a.use)) : null),
        el('div', 'td right', fmt(a.calls)),
        el('div', 'td right ' + (a.p95Ms == null ? 'muted' : a.p95Ms >= 6000 ? 't-bad' : a.p95Ms >= 3000 ? 't-warn' : ''), a.p95Ms != null ? ms(a.p95Ms) : '—'),
        el('div', 'td right ' + (a.errorRatio >= 0.05 ? 't-bad' : a.errorRatio >= 0.01 ? 't-warn' : ''), pct(a.errorRatio)),
        hasCost ? el('div', 'td right', a.costUsd != null ? money(a.costUsd) : '—') : null)));
    if (!list.length) $('ai-table').append(noRows());
    const sum = (k) => list.reduce((s, a) => s + (a[k] || 0), 0);
    $('ai-foot').textContent = [`${compact(sum('tokensIn'))} tokens in`, `${compact(sum('tokensOut'))} out`,
      hasCost ? `${money(sum('costUsd'))} today` : null, m.aiCost30dUsd != null ? `30-d ${money(m.aiCost30dUsd)}` : null,
      m.aiFallbacks24h != null ? `fallbacks: ${fmt(m.aiFallbacks24h)}` : null].filter(Boolean).join(' · ');
  };

  const renderMedia = (m) => {
    const x = m.media24h;
    if (!show('media', x != null)) return;
    const joinRate = x.joinFailures / (x.videoRooms || 1);
    fill('media-list',
      mini('Video rooms', fmt(x.videoRooms), `avg ${fmt(x.avgRoomMinutes)} min`),
      mini('Join failures', pct(joinRate), `${fmt(x.joinFailures)} of ${fmt(x.videoRooms)}`, joinRate >= 0.01 ? 't-warn' : ''),
      mini('Recordings', fmt(x.recordings), `${fmt(x.transcribing)} transcribing`),
      mini('FCM sent', fmt(x.fcmSent), `${pct(x.fcmAccepted / (x.fcmSent || 1))} accepted`),
      mini('Invalid tokens', fmt(x.fcmInvalidTokensPruned), 'pruned'),
      mini('SMS reminders', fmt(x.smsReminders), `${fmt(x.smsFailed)} failed`, x.smsFailed > 0 ? 't-warn' : ''));
  };

  const renderPayments = (m) => {
    const windowed = m.paymentsLast24h != null;
    const recent = new Map(gateways(m.paymentsLast24h || []).map((g) => [g.name, g]));
    const list = gateways(m.domain.paymentsByStatus);
    const tone = (r) => r >= PAY_BAD ? 't-bad' : r >= PAY_WARN ? 't-warn' : 't-ok';
    fill('payments-list', ...(list.length ? list.map((g) => {
      const seg = (n, cls, label) => (n > 0 ? titled(sized(el('div', cls), 'width', Math.max(100 * n / g.total, 0.6) + '%'), `${label}: ${fmt(n)}`) : null);
      const r = recent.get(g.name);
      return el('div', 'stack',
        el('div', 'head', el('span', 'strong', words(g.name)), el('span', 'strong ' + tone(g.rate), `${pct(g.rate)} failed`)),
        el('div', 'segs thin', seg(g.ok, 'c-accent', 'Succeeded'), seg(g.pending, 'c-pending', 'Pending'), seg(g.failed, 'c-bad', 'Failed'), seg(g.other, 'c-grey', 'Cancelled or refunded')),
        el('div', 'small muted', `${fmt(g.ok)} succeeded · ${fmt(g.pending)} pending · ${fmt(g.failed)} failed${g.other ? ` · ${fmt(g.other)} cancelled or refunded` : ''}`),
        windowed ? el('div', 'small ' + (r ? tone(r.rate) : 'muted'), r ? `Last 24 h: ${pct(r.rate)} failed (${fmt(r.failed)} of ${fmt(r.total)})` : 'Last 24 h: no payments') : null);
    }) : [noRows()]));
    $('payments-foot').textContent = windowed ? 'Bars show all-time totals.' : 'All-time totals.';
  };

  const JOBS = {
    'reminder-notifications': ['Appointment and care reminders', 'tasks, appointments, payments and prophylaxis, by SMS and FCM'],
    'payment-hold-expiry': ['Payment hold expiry', 'releases slots held by unpaid checkouts'],
    'user-session-cleanup': ['Expired session cleanup', 'revoked and expired JWT sessions'],
    'sms-otp-cleanup': ['SMS code cleanup', 'expired phone codes'],
    'email-otp-cleanup': ['E-mail code cleanup', 'expired e-mail codes'],
    'ai-draft-note-cleanup': ['AI draft cleanup', 'old unsaved visit drafts'],
    'traffic-rollup': ['Traffic rollup', 'saves request counts for the traffic card'],
    'traffic-rollup-cleanup': ['Traffic rollup cleanup', 'deletes saved counts older than 62 days'],
  };
  const jobLabel = (j) => JOBS[j.name] || [words(j.name), ''];
  const renderJobs = (m) => {
    if (!show('jobs', Array.isArray(m.jobs) && m.jobs.length > 0)) return;
    const now = Date.parse(m.generatedAt);
    fill('jobs-list', ...m.jobs.map((j) => {
      const failed = j.lastResult === 'FAILED';
      const overdue = isOverdue(j, now);
      const tone = failed ? 'c-bad' : overdue || j.note ? 'c-warn' : 'c-accent';
      const last = [j.lastRunAt ? hourMin(j.lastRunAt) : null, j.lastResult ? j.lastResult.toLowerCase() : null, overdue ? 'overdue' : null, j.processed != null ? `${fmt(j.processed)} done` : null, j.queued != null ? `${fmt(j.queued)} queued` : null].filter(Boolean).join(' · ');
      const [name, desc] = jobLabel(j);
      return el('div', 'job', el('span', 'dot ' + tone), el('div', 'min0', el('div', '', name), desc ? el('div', 'small muted', desc) : null, j.note ? el('div', 'small t-warn', `Open audit item: ${j.note}`) : null),
        titled(el('div', 'mono right ' + (failed ? 't-bad' : overdue ? 't-warn' : ''), last), j.lastRunAt ? `Last started ${j.lastRunAt}` : ''));
    }));
  };

  const renderDatabase = (m) => {
    const db = m.database, dom = m.domain;
    $('tables-head').textContent = `${bytes(db.sizeBytes)} total · rows estimated`;
    const maxBytes = Math.max(1, ...db.tables.map((t) => t.bytes));
    fill('tables', ...(db.tables.length ? db.tables.map((t) => el('div', '',
      el('div', 'bar-cell', el('span', 'mono ellipsis', t.table), bar(t.bytes / maxBytes, 'c-accent')),
      el('div', 'right', bytes(t.bytes)),
      el('div', 'right muted', `${compact(t.estimatedRows)} rows`))) : [noRows()]));
    kv('db-kv', [
      ['Schema', db.schemaVersion != null ? [`V${db.schemaVersion}`, db.lastMigration, (db.migratedAt || '').slice(0, 10)].filter(Boolean).join(' · ') : '—'],
      ['App pool', `${db.pool.active} active · ${db.pool.idle} idle · ${db.pool.max} max · ${db.pool.pending} waiting`, db.pool.pending > 0 ? 't-warn' : ''],
      ['Server connections', `${db.serverConnections.active} active · ${db.serverConnections.idle} idle`],
      ['Active sessions', fmt(dom.sessionsActive)],
      ['Notifications', `${fmt(dom.notificationsUnread)} unread of ${fmt(dom.notificationsTotal)}`],
    ]);
    const tiles = [];
    if (db.cacheHitRatio != null) tiles.push(mini('Cache hit', pct(db.cacheHitRatio), 'target > 99%', db.cacheHitRatio >= 0.99 ? 't-ok' : 't-warn'));
    if (db.growthBytesPerWeek != null) {
      const months = db.volumeLimitBytes ? Math.round((db.volumeLimitBytes - db.sizeBytes) / db.growthBytesPerWeek / 4.345) : null;
      tiles.push(mini('Growth', `+${bytes(db.growthBytesPerWeek)}/wk`, months !== null ? `full in ~${months} months` : ''));
    }
    if (db.deadTupleRatio) tiles.push(mini('Dead tuples', pct(db.deadTupleRatio.ratio), db.deadTupleRatio.table, db.deadTupleRatio.ratio >= 0.05 ? 't-warn' : ''));
    if (db.unusedIndexes) tiles.push(mini('Unused indexes', fmt(db.unusedIndexes.count), bytes(db.unusedIndexes.bytes)));
    const queries = db.slowQueries || [];
    if (!show('pg', tiles.length > 0 || queries.length > 0)) return;
    fill('pg-list', ...tiles);
    if (show('slow-block', queries.length > 0)) {
      fill('slow-list', ...queries.map((q) => el('div', 'query', titled(el('span', 'mono ellipsis', q.query), q.query), el('span', 'muted', compact(q.calls)), el('span', 'right strong ' + (q.meanMs >= 100 ? 't-warn' : ''), ms(q.meanMs)))));
    }
  };

  // ---- Is real usage flowing.
  const outcomeTone = (r) => {
    if (r.status === 'COMPLETED') return 'c-accent';
    if (r.status === 'CONFIRMED' || r.status === 'IN_PROGRESS') return 'c-accent2';
    if (r.status === 'REQUESTED' || r.status === 'PENDING_PAYMENT') return 'c-pending';
    return r.paymentStatus === 'REFUNDED' ? 'c-grey' : 'c-dim';
  };
  const renderDaily = (a) => {
    const r = RANGES[a.range];
    const last = a.buckets.length - 1;
    const max = Math.max(1, ...a.buckets.map((b) => b.count));
    const label = (b) => bucketLabel(a.bucketSeconds, b.start);
    $('daily-title').textContent = `Appointments created / ${r.per}`; // set here, so a heading never sits on another range's numbers
    fill('daily', ...a.buckets.flatMap((b, i) => {
      const tip = `${label(b)}: ${fmt(b.count)}${i === last ? ' (still running)' : ''}`;
      return [titled(el('span', 'num', fmt(b.count)), tip), titled(el('div', 'day', sized(el('div', i === last ? 'c-warn' : 'c-accent'), 'height', widthPct(b.count / max))), tip)];
    }));
    fitNumbers('daily');
    const change = percentChange(a.comparableTotal, a.previousTotal);
    const head = `${fmt(a.total)} in ${r.span}${change !== null ? ` · ${signed(change)} vs ${r.compare}` : ''}`;
    $('daily-head').textContent = head;
    $('daily').setAttribute('aria-label', `Appointments created per ${r.per}: ${head}`);
    $('daily-first').textContent = label(a.buckets[0]);
    show('daily-error', false);
  };
  const loadActivity = rangeLoader('appointments', () => activityRange, renderDaily, (range) => {
    $('daily-error').textContent = `Appointments for ${RANGES[range].span} could not be read. Trying again on the next refresh.`;
    show('daily-error', true);
  });

  const renderActivity = (m) => {
    const dom = m.domain;
    const parts = dom.appointmentsByStatus.map((r) => ({ label: `${words(r.status)} · ${words(r.paymentStatus).toLowerCase()}`, count: r.count, tone: outcomeTone(r) }));
    stackBar('outcome-bar', parts, 0.4);
    legend('outcome-legend', parts);
    if (!parts.length) $('outcome-legend').append(noRows());
    const ROLE = { PATIENT: 'patient', DOCTOR: 'doctor', CLINIC_STAFF: 'clinic staff', ADMIN: 'admin' };
    $('users-line').textContent = 'Users: ' + (dom.usersByRole.map((r) => (r.key === 'CLINIC_STAFF' ? `${fmt(r.count)} clinic staff` : plural(r.count, ROLE[r.key] || r.key.toLowerCase()))).join(' · ') || 'none');
  };

  const renderFooter = (m) => {
    fill('footer', ...[`commit ${(m.app.commit || '').slice(0, 8)}`, `Spring Boot ${m.app.springBoot}`, `Java ${m.app.javaVersion}`, `PostgreSQL ${m.database.postgresVersion}`,
      `${fmt(m.jvm.threads)} threads · non-heap ${fmt(m.jvm.nonHeapUsedMb)} MB`, `deployed ${utcMinute(m.http.since)} UTC`].map((t) => el('span', '', t)));
  };

  const render = (m) => {
    lastMetrics = m;
    $('where').textContent = `${host} · build ${(m.app.commit || '').slice(0, 8)} · up ${dur(m.app.uptimeSeconds)}`;
    [renderVerdict, renderVitals, renderDeps, renderRoutes, renderClients, renderAuth, renderAi, renderMedia, renderPayments, renderJobs, renderDatabase, renderActivity, renderFooter].forEach((f) => f(m));
    lastOk = new Date(m.generatedAt);
    isStale = false;
    $('board').classList.remove('stale');
    $('status').classList.remove('failed');
    updateClock();
  };

  const updateClock = () => {
    if (isStale || !lastOk) return;
    $('refreshed').textContent = `Updated ${clock(lastOk)} · next in ${Math.max(0, Math.ceil((nextAt - Date.now()) / 1000))} s`;
  };

  const stale = (why) => {
    isStale = true;
    $('refreshed').textContent = `${why} · ${lastOk ? `last good read ${clock(lastOk)}` : 'no data yet'}`;
    $('status').classList.add('failed');
    $('board').classList.add('stale');
  };

  const probeReadiness = async () => {
    try {
      const r = await fetch(api + '/actuator/health/readiness', { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
      readiness = (await r.json()).status || null;
    } catch (e) { readiness = null; }
  };

  const resetLogin = () => {
    const codeField = $('code');
    codeField.hidden = true; codeField.required = false; codeField.value = '';
    $('password').value = '';
    $('submit').textContent = 'Send code';
  };

  const signOut = (message) => {
    session++;
    inFlight = false;
    token = null; try { sessionStorage.removeItem('shifa.admin.token'); } catch (e) { /* storage blocked */ }
    clearInterval(timer); timer = null;
    clearInterval(countdown); countdown = null;
    lastMetrics = null; lastOk = null;
    $('board').hidden = true; $('login').hidden = false; $('status').hidden = true;
    $('where').textContent = host;
    resetLogin();
    $('login-error').className = 'small t-bad';
    $('login-error').textContent = message || '';
    $('login-error').hidden = !message;
  };

  const load = async () => {
    if (!token || inFlight) return;
    inFlight = true;
    const mine = session;
    try {
      const [r] = await Promise.all([fetch(api + '/api/admin/metrics', { headers: { Authorization: 'Bearer ' + token }, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) }), probeReadiness()]);
      if (mine !== session) return; // signed out while the request was in flight
      if (r.status === 401 || r.status === 403) return signOut('Session ended. Sign in again.');
      if (!r.ok) {
        if (lastMetrics) { renderVerdict(lastMetrics); renderVitals(lastMetrics); }
        return stale(`Metrics failed: HTTP ${r.status}`);
      }
      const body = await r.json();
      if (mine !== session) return;
      render(body);
    } catch (e) {
      if (mine !== session) return;
      if (lastMetrics) { renderVerdict(lastMetrics); renderVitals(lastMetrics); }
      stale('Metrics unreachable');
    } finally {
      inFlight = false;
    }
  };

  const tick = () => {
    nextAt = Date.now() + POLL_MS;
    if (document.hidden) return;
    load();
    loadTraffic();
    loadActivity();
  };

  const setSort = (mode) => {
    routeSort = mode;
    store.set('shifa.admin.routeSort', mode);
    if (lastMetrics) renderRoutes(lastMetrics);
  };
  $('sort-worst').addEventListener('click', () => setSort('worst'));
  $('sort-busy').addEventListener('click', () => setSort('busy'));
  $('routes-all').addEventListener('click', () => { routesAll = !routesAll; if (lastMetrics) renderRoutes(lastMetrics); });

  document.querySelectorAll('[data-range]').forEach((b) => b.addEventListener('click', () => {
    trafficRange = b.dataset.range;
    store.set('shifa.admin.trafficRange', trafficRange);
    renderRangeButtons();
    loadTraffic();
  }));

  document.querySelectorAll('[data-activity-range]').forEach((b) => b.addEventListener('click', () => {
    activityRange = b.dataset.activityRange;
    store.set('shifa.admin.activityRange', activityRange);
    renderRangeButtons();
    loadActivity();
  }));

  const restartTimer = () => {
    clearInterval(timer); timer = setInterval(tick, POLL_MS);
    tick();
  };

  const start = () => {
    $('login').hidden = true; $('board').hidden = false; $('status').hidden = false;
    fitNumbers('hours'); fitNumbers('daily'); // charts kept from before a sign-out were last measured while hidden
    renderRangeButtons();
    clearInterval(countdown); countdown = setInterval(updateClock, 1000);
    restartTimer();
  };

  $('login-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('login-error').hidden = true;
    const username = $('username').value.trim(); const password = $('password').value;
    const codeField = $('code');
    try {
      if (codeField.hidden) {
        const r = await fetch(api + '/api/auth/admin/request-login-otp', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.message || j.error || `HTTP ${r.status}`);
        codeField.hidden = false; codeField.required = true; codeField.focus();
        $('submit').textContent = 'Sign in';
        $('login-error').className = 'small muted'; $('login-error').textContent = `Code sent to ${j.emailHint || 'your email'}. Wrong or expired code? Press Start over and try again.`; $('login-error').hidden = false;
      } else {
        const r = await fetch(api + '/api/auth/admin/verify-login-otp', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password, code: codeField.value.trim() }) });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.message || j.error || `HTTP ${r.status}`);
        token = j.token; try { sessionStorage.setItem('shifa.admin.token', token); } catch (err) { /* storage blocked */ }
        resetLogin();
        $('login-error').hidden = true;
        start();
      }
    } catch (err) {
      $('login-error').className = 'small t-bad'; $('login-error').textContent = err.message; $('login-error').hidden = false;
    }
  });
  $('start-over').addEventListener('click', () => { resetLogin(); $('login-error').hidden = true; });
  $('refresh').addEventListener('click', restartTimer);
  $('logout').addEventListener('click', () => signOut());
  window.addEventListener('resize', () => { fitNumbers('hours'); fitNumbers('daily'); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && token) restartTimer(); });

  if (token) start();
})();
