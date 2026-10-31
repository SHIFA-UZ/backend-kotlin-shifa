// Admin health page: two-step admin sign-in, then GET /api/admin/metrics every 30 s while the tab is visible.
// Served as its own file because the production CSP allows scripts from 'self' only.
(() => {
  const $ = (id) => document.getElementById(id);
  const api = location.origin;
  const fmt = new Intl.NumberFormat('en');
  const bytes = (n) => n >= 1e9 ? (n / 1e9).toFixed(2) + ' GB' : n >= 1e6 ? (n / 1e6).toFixed(1) + ' MB' : n >= 1e3 ? Math.round(n / 1e3) + ' kB' : n + ' B';
  const dur = (s) => { const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60); return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`; };
  const POLL_MS = 30000;
  let token = null;
  try { token = sessionStorage.getItem('shifa.admin.token'); } catch (e) { token = null; }
  let timer = null;
  let inFlight = false;
  let lastOk = null;
  $('where').textContent = api;

  const bars = (el, rows, name, value, text, cls) => {
    el.replaceChildren();
    const max = Math.max(1, ...rows.map(value));
    rows.forEach((r) => {
      const row = document.createElement('div'); row.className = 'bar';
      const n = document.createElement('div'); n.className = 'name'; n.textContent = name(r); n.title = name(r);
      const t = document.createElement('div'); t.className = 'track';
      const f = document.createElement('div'); f.className = 'fill' + (cls ? ' ' + cls(r) : ''); f.style.width = (100 * value(r) / max).toFixed(1) + '%';
      t.append(f);
      const v = document.createElement('div'); v.className = 'val'; v.textContent = text(r);
      row.append(n, t, v); el.append(row);
    });
    if (!rows.length) { const p = document.createElement('div'); p.className = 'muted small'; p.textContent = 'No rows'; el.append(p); }
  };

  const render = (m) => {
    $('uptime').textContent = dur(m.app.uptimeSeconds);
    $('commit').textContent = (m.app.commit || '').slice(0, 8);
    $('boot').textContent = m.app.springBoot;
    $('java').textContent = m.app.javaVersion;
    $('heap').textContent = `${fmt.format(m.jvm.heapUsedMb)} / ${fmt.format(m.jvm.heapMaxMb)} MB`;
    $('nonheap').textContent = `${fmt.format(m.jvm.nonHeapUsedMb)} MB`;
    $('threads').textContent = fmt.format(m.jvm.threads);
    const db = m.database;
    $('dbsize').textContent = bytes(db.sizeBytes);
    $('schema').textContent = db.schemaVersion != null ? 'V' + db.schemaVersion : '—';
    $('pool').textContent = `${db.pool.active} + ${db.pool.idle} / ${db.pool.max}`;
    $('pending').textContent = fmt.format(db.pool.pending);
    $('pending').closest('.card').style.color = db.pool.pending > 0 ? 'var(--warn)' : '';
    $('server-conns').textContent = `${db.serverConnections.active} + ${db.serverConnections.idle}`;
    $('pg').textContent = db.postgresVersion;
    bars($('tables'), db.tables, (r) => r.table, (r) => r.bytes, (r) => `${bytes(r.bytes)} · ~${fmt.format(r.estimatedRows)} rows`);
    const dom = m.domain;
    bars($('appointments'), dom.appointmentsByStatus, (r) => `${r.status} · ${r.paymentStatus}`, (r) => r.count, (r) => fmt.format(r.count));
    bars($('payments'), dom.paymentsByStatus, (r) => `${r.status} · ${r.gateway}`, (r) => r.count, (r) => fmt.format(r.count), () => 'alt');
    bars($('roles'), dom.usersByRole, (r) => r.key, (r) => r.count, (r) => fmt.format(r.count), () => 'alt');
    bars($('daily'), dom.appointmentsPerDay, (r) => r.day, (r) => r.count, (r) => fmt.format(r.count));
    $('notifications').textContent = `${fmt.format(dom.notificationsUnread)} / ${fmt.format(dom.notificationsTotal)}`;
    $('sessions').textContent = fmt.format(dom.sessionsActive);
    $('migration').textContent = db.lastMigration ? `${db.lastMigration} (${(db.migratedAt || '').slice(0, 10)})` : '—';
    const http = m.http;
    $('req').textContent = fmt.format(http.requests);
    $('req5xx').textContent = fmt.format(http.serverErrors);
    $('req5xx').closest('.card').style.color = http.serverErrors > 0 ? 'var(--bad)' : '';
    $('req-since').textContent = http.since.slice(0, 16).replace('T', ' ');
    bars($('routes'), http.routes.slice(0, 25), (r) => `${r.method} ${r.route}`, (r) => r.requests,
      (r) => `${fmt.format(r.requests)} · avg ${fmt.format(r.averageMs)} ms · recent max ${fmt.format(r.recentMaxMs)} ms`, (r) => r.serverErrors > 0 ? 'bad' : '');
    lastOk = new Date(m.generatedAt);
    $('refreshed').textContent = `Updated ${lastOk.toLocaleTimeString()} · every ${POLL_MS / 1000} s while this tab is visible`;
    $('refreshed').className = 'small muted';
  };

  const stale = (why) => {
    const when = lastOk ? `last good read ${lastOk.toLocaleTimeString()}` : 'no data yet';
    $('refreshed').textContent = `${why} · ${when}`;
    $('refreshed').className = 'small error';
  };

  const setHealth = async () => {
    try {
      const r = await fetch(api + '/actuator/health/readiness'); const j = await r.json();
      $('health').textContent = j.status; $('health').className = 'pill ' + (j.status === 'UP' ? 'ok' : 'bad');
    } catch (e) { $('health').textContent = 'unreachable'; $('health').className = 'pill bad'; }
  };

  const resetLogin = () => {
    const codeField = $('code');
    codeField.hidden = true; codeField.required = false; codeField.value = '';
    $('password').value = '';
    $('submit').textContent = 'Send code';
  };

  const signOut = (message) => {
    token = null; try { sessionStorage.removeItem('shifa.admin.token'); } catch (e) { /* storage blocked */ }
    clearInterval(timer); timer = null;
    clearInterval(storageTimer); storageTimer = null;
    $('board').hidden = true; $('login').hidden = false; $('refresh').hidden = true; $('logout').hidden = true;
    resetLogin();
    $('login-error').className = 'small error';
    $('login-error').textContent = message || '';
    $('login-error').hidden = !message;
  };

  const load = async () => {
    if (!token || inFlight) return;
    inFlight = true;
    try {
      const r = await fetch(api + '/api/admin/metrics', { headers: { Authorization: 'Bearer ' + token } });
      if (r.status === 401 || r.status === 403) return signOut('Session ended. Sign in again.');
      if (!r.ok) return stale(`Metrics failed: HTTP ${r.status}`);
      render(await r.json());
    } catch (e) {
      stale('Metrics unreachable');
    } finally {
      inFlight = false;
    }
  };

  const tick = () => { if (document.hidden) return; load(); setHealth(); };

  // Volume-to-bucket copy (POST /api/admin/storage/migrate starts it, GET reports progress). Polls every 5 s while running.
  let storageTimer = null;
  const renderStorage = (st) => {
    const when = (t) => (t ? new Date(t).toLocaleTimeString() : '');
    const run = st.state === 'running';
    const text = `${st.state}${st.dryRun ? ' (dry run)' : ''} · scanned ${fmt.format(st.scanned)} · copied ${fmt.format(st.copied)} · skipped ${fmt.format(st.skipped)} · conflicts ${fmt.format(st.conflicts)} · failed ${fmt.format(st.failed)} · ${bytes(st.bytes)}` +
      (st.startedAt ? ` · started ${when(st.startedAt)}` : '') + (st.finishedAt ? ` · finished ${when(st.finishedAt)}` : '');
    $('storage-status').textContent = text;
    $('storage-status').className = 'small ' + (st.state === 'failed' || st.failed > 0 ? 'error' : st.state === 'done' ? 'ok' : '');
    $('storage-start').disabled = run; $('storage-dry').disabled = run;
    $('storage-error').hidden = !st.lastError; $('storage-error').textContent = st.lastError || '';
    clearInterval(storageTimer); storageTimer = run ? setInterval(() => loadStorage(), 5000) : null;
  };
  let storageBusy = false;
  const storageCall = async (method, query) => {
    if (!token || storageBusy) return;
    storageBusy = true;
    if (method === 'POST') { $('storage-start').disabled = true; $('storage-dry').disabled = true; }
    try {
      const r = await fetch(api + '/api/admin/storage/migrate' + (query || ''), { method, headers: { Authorization: 'Bearer ' + token } });
      if (!token) return; // signed out while the request was in flight
      if (r.status === 401 || r.status === 403) return signOut('Session ended. Sign in again.');
      const j = await r.json().catch(() => ({}));
      if (!r.ok) {
        $('storage-error').textContent = j.message || `HTTP ${r.status}`; $('storage-error').hidden = false;
        $('storage-start').disabled = false; $('storage-dry').disabled = false;
        return;
      }
      renderStorage(j);
    } catch (e) {
      if (!token) return;
      $('storage-error').textContent = 'Storage status unreachable'; $('storage-error').hidden = false;
      $('storage-start').disabled = false; $('storage-dry').disabled = false;
    } finally {
      storageBusy = false;
    }
  };
  const loadStorage = () => storageCall('GET');
  $('storage-start').addEventListener('click', () => storageCall('POST'));
  $('storage-dry').addEventListener('click', () => storageCall('POST', '?dryRun=true'));
  $('storage-refresh').addEventListener('click', loadStorage);

  const start = () => {
    $('login').hidden = true; $('board').hidden = false; $('refresh').hidden = false; $('logout').hidden = false;
    tick();
    loadStorage();
    clearInterval(timer); timer = setInterval(tick, POLL_MS);
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
        $('login-error').className = 'small muted'; $('login-error').textContent = `Code sent to ${j.emailHint || 'your email'}. Wrong or expired code? Sign out and start again.`; $('login-error').hidden = false;
      } else {
        const r = await fetch(api + '/api/auth/admin/verify-login-otp', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password, code: codeField.value.trim() }) });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.message || j.error || `HTTP ${r.status}`);
        token = j.token; try { sessionStorage.setItem('shifa.admin.token', token); } catch (err) { /* storage blocked */ }
        resetLogin();
        start();
      }
    } catch (err) {
      $('login-error').className = 'small error'; $('login-error').textContent = err.message; $('login-error').hidden = false;
    }
  });
  $('start-over').addEventListener('click', () => { resetLogin(); $('login-error').hidden = true; });
  $('refresh').addEventListener('click', tick);
  $('logout').addEventListener('click', () => signOut());
  document.addEventListener('visibilitychange', () => { if (!document.hidden && token) tick(); });

  setHealth();
  if (token) start();
})();
