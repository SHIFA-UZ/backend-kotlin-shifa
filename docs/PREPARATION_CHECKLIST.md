# Preparation checklist

What you need to do, and what I need from you, before and during the first two phases of `ARCHITECTURE_ROADMAP.md`. Steps are in the order I need them. Each step says which roadmap item it unblocks, so you can skip ahead or stop at any point without breaking anything.

**How we work:** you connect me to Railway once (section 0); from then on I run the Railway steps myself under the rules below. GitHub settings (rulesets, environments, Dependabot) you do yourself from the steps in 3.1; I only push branches and open PRs with the repo's git identity as before. You also do what needs your own accounts (Daily, Stripe, Firebase, the app stores) and the product decisions. I write code on branches; nothing is committed, pushed or merged without your approval per PR. Every PR goes to development first once that environment exists.

**Rules I follow once connected:**

- I read anything (project status, variables, logs, database) without asking. When I report variables back I mask secret values.
- I change the **development** environment freely: it has no users.
- I touch **production** only after showing you the exact command and getting a "go" for that command: setting a variable, adding a volume, switching the source branch, redeploying.
- On the production database I run `SELECT` only. Any `UPDATE`/`DELETE` is shown to you first and run only on your "go".
- Every Railway command I run names the environment explicitly (`-e development` / `-e production`); I never rely on the CLI's "current" environment.
- Nothing from Railway or GitHub is copied into the repo or into chat except masked values and counts.

Paste results into the chat using the headings in section 7, so I can match them to the steps.

---

## 0. Connect me to Railway (one-time, 2 minutes)

The Railway CLI and the Postgres client are installed on this machine (`brew install railway libpq`). The login is interactive, so you run it in this session with the `!` prefix:

```
! railway login
```

A browser window opens; approve it. Then link this repo to the Railway project (pick the project and the backend service when asked):

```
! cd backend-kotlin-shifa && railway link
```

When done, tell me and I verify with `railway status`.

What this lets me do myself: steps 1.2, 1.3, 1.5, 3.4, 3.5 and section 4 below. What stays with you: 1.1, 1.4, 1.6, 1.7, 3.1 (GitHub), 3.2, 3.3.

Optional: Railway also has an MCP server (`railway mcp install --agent claude-code`). I do not need it; the CLI already covers everything, and fewer moving parts is better. Say so if you want it anyway.

---

## 1. Today, before any code (unblocks Phase 0)

### 1.1 Decide on the roadmap branch

The branch `docs/architecture-roadmap` holds `docs/ARCHITECTURE_ROADMAP.md`, this file, and the `.gitignore` fix (markdown files are no longer ignored). Tell me "commit and open the PR" or what to change first. Also: `CLAUDE.md` in this repo has an uncommitted change from before this work; tell me whether it is yours to keep (I will not touch it).

### 1.2 Check the Railway volume (roadmap Q2) — **done 2026-10-27**

A 2 GB volume is mounted at `/app/storage` (268 MB used), with a daily backup. Nothing to do.

### 1.3 Export the production variables (roadmap 3.6, unblocks 0.4 and 1.10) — **done 2026-10-27**

48 variables read through the CLI (secrets masked in my notes). Facts that change the plan: `JWT_SECRET` is 40 characters (the startup check in 0.4 warns, never refuses); `GEMINI_API_KEY` set, `XAI_API_KEY` not set (the Grok provider is removed from config in 1.10); stray unused variables `PUBLIC_BASE_URL`, `SPRING_VARIABLES_ACTIVE`, `DB_USERNAME`, `DB_PASSWORD` (deleted after 1.10). Non-secret values are hardcoded verbatim in 1.10.

### 1.4 Rotate the Daily.co API key (roadmap S5, unblocks 0.4)

The current key is committed in git, so it must be treated as leaked.

1. https://dashboard.daily.co → **Developers → API keys** → create a new key.
2. Railway → backend service → Variables → set `DAILY_API_KEY` to the new key. Railway redeploys automatically; video keeps working because the app only reads the variable.
3. After the deploy is healthy, delete the old key in the Daily dashboard.
4. Tell me it is done. Do **not** rotate `JWT_SECRET`; that logs out every user.

### 1.5 Run the read-only production queries (unblocks 0.2, 0.3, 0.8, 0.10) — **done 2026-10-27**

Results: 5 appointments with duplicate open checkouts (13 surplus rows; fixed by a migration in PR 0.10); zero legacy `/photos/` URLs; extensions in use `pdf png jpg jpeg m4a`; no `patient@test.com` account; database 58 MB, Flyway at V117 = repo, pgvector 0.8.2, `max_connections` 500 with 11 used. To re-run later:

This session's permission system blocks me from reading the production database directly, which is the right default. The queries are saved as `docs/sql/prod-baseline-readonly.sql` (every statement is a `SELECT` or `SHOW`). Run them yourself from this session; the Railway CLI injects the connection details, nothing is typed or stored:

```
! cd backend-kotlin-shifa && railway run -s Postgres -e staging sh -c '/opt/homebrew/opt/libpq/bin/psql "host=$RAILWAY_TCP_PROXY_DOMAIN port=$RAILWAY_TCP_PROXY_PORT user=$PGUSER dbname=$PGDATABASE sslmode=require" -X -q -f docs/sql/prod-baseline-readonly.sql'
```

The output lands in the chat and I read it from there. The queries, for reference:

```sql
-- Q-A  Duplicate open checkouts (roadmap 0.10). Expect 0 rows; any rows need a data fix first.
SELECT appointment_id, count(*) AS open_payments, array_agg(id ORDER BY created_at) AS payment_ids
FROM payments
WHERE status IN ('PENDING', 'AUTHORIZED') AND appointment_id IS NOT NULL
GROUP BY appointment_id
HAVING count(*) > 1;

-- Q-B  Stored URLs that use the legacy /photos/ path (roadmap 0.2). Tells me which legacy folders to keep serving.
SELECT 'patient_profiles.photo_url' AS src, count(*) FROM patient_profiles WHERE photo_url LIKE '%/photos/%'
UNION ALL
SELECT 'doctor_profiles.avatar_url', count(*) FROM doctor_profiles WHERE avatar_url LIKE '%/photos/%'
UNION ALL
SELECT 'doctor_profiles.certificates', count(*) FROM doctor_profiles WHERE certificates LIKE '%/photos/%'
UNION ALL
SELECT 'patient_documents.file_path', count(*) FROM patient_documents WHERE file_path LIKE '%/photos/%'
UNION ALL
SELECT 'messages.attachment_url', count(*) FROM messages WHERE attachment_url LIKE '%/photos/%';

-- Q-C  Which folders the /photos/ URLs point at (only if Q-B returned non-zero counts).
SELECT DISTINCT substring(u FROM '/photos/([^/]+)/') AS folder
FROM (
  SELECT photo_url AS u FROM patient_profiles WHERE photo_url LIKE '%/photos/%'
  UNION ALL SELECT avatar_url FROM doctor_profiles WHERE avatar_url LIKE '%/photos/%'
  UNION ALL SELECT file_path FROM patient_documents WHERE file_path LIKE '%/photos/%'
  UNION ALL SELECT attachment_url FROM messages WHERE attachment_url LIKE '%/photos/%'
) t;

-- Q-D  File extensions actually in use (roadmap 0.8). Defines the upload allow-list.
SELECT 'patient_documents' AS src, lower(substring(file_path FROM '\.([A-Za-z0-9]+)$')) AS ext, count(*)
FROM patient_documents WHERE file_path IS NOT NULL GROUP BY 2
UNION ALL
SELECT 'messages', lower(substring(attachment_url FROM '\.([A-Za-z0-9]+)$')), count(*)
FROM messages WHERE attachment_url IS NOT NULL GROUP BY 2
UNION ALL
SELECT 'patient_photos', lower(substring(photo_url FROM '\.([A-Za-z0-9]+)$')), count(*)
FROM patient_profiles WHERE photo_url IS NOT NULL GROUP BY 2
UNION ALL
SELECT 'doctor_avatars', lower(substring(avatar_url FROM '\.([A-Za-z0-9]+)$')), count(*)
FROM doctor_profiles WHERE avatar_url IS NOT NULL GROUP BY 2
ORDER BY 1, 3 DESC;

-- Q-E  The test account created by /api/test (roadmap 0.3). I need to know whether it exists and is enabled.
SELECT id, email, phone, enabled, created_at FROM users WHERE email = 'patient@test.com' OR phone = '+998901234567';

-- Q-F  Table sizes, for the scorecard baseline (roadmap 13.3). Top 15 by rows.
SELECT relname AS table_name, n_live_tup AS approx_rows
FROM pg_stat_user_tables ORDER BY n_live_tup DESC LIMIT 15;

-- Q-G  Postgres connection ceiling, for the pool budget (roadmap 2.8).
SHOW max_connections;
```

### 1.6 Tell me which app builds are live (roadmap Q9) — **done 2026-10-27**: patient 1.2.3, doctor web 1.0.0+3 (= `main`)

- Google Play Console → patient app → **Statistics / Release overview**: which versions have active installs, and roughly what share is below 1.2.0 (the Flutter build).
- App Store Connect → patient app → same.
- Doctor app: which platforms are actually distributed (web only, or also Android, iOS, Windows), and the current version of each.

A sentence per store is enough. This sets when the "two releases later" cleanups may run.

### 1.7 Three product decisions that change Phase 0 — **done 2026-10-27**: Q10 keep, Q12 30 days, Q3 Railway only

- **Q10** Should a doctor or clinic staff member be able to search (chat) and assign (calendar) patients who have no appointment, conversation or clinic relationship with them? Today they see the whole patient base. "Yes, keep it" means S3 is accepted risk; "No" means a doctor-app change in Phase 4.
- **Q12** Intended token lifetimes: doctors 12 hours (as `CLAUDE.md` says) or 30 days (as production is configured)? Patients 365 days?
- **Q3** Drop the GCP Cloud Run path (files, dependency, docs) and keep Railway only? **Yes, done 2026-10-29.**

With 1.1 to 1.7 done I can open the Phase 0 PRs in the order in roadmap section 11, days 1 to 5. Each PR comes with: what changed, test output, and the exact smoke checks to do in the apps after the Railway deploy.

---

## 2. During Phase 0 (you act per PR)

For each Phase 0 PR I will ask for one of these; nothing to prepare in advance:

| PR | What you do |
|---|---|
| 0.3 | Nothing: the test account does not exist in production. |
| 0.4 | Nothing if 1.4 is done. |
| 0.2, 0.8 | After the log-only week: paste the one-line counts I ask for from Railway logs (search terms given in the PR). |
| 0.10 | Approve the data-fix migration in the PR (cancels 13 duplicate open payments on 5 appointments, keeps the newest per appointment). |
| all | Merge, watch the Railway deploy turn green, run the smoke checks listed in the PR on the current store builds. |

---

## 3. Before Phase 1 (unblocks CI and the development environment)

### 3.1 GitHub repository settings (`SHIFA-UZ/backend-kotlin-shifa`) — **you do this**

Do these **after** the first CI workflow has run on `main` (I will tell you), because the status check must exist before it can be required.

1. **Settings → Code security**: enable *Dependabot alerts* and *Dependabot security updates*. (Secret scanning and dependency graph: enable if your plan offers them.)
2. **Settings → Rules → Rulesets → New branch ruleset** named `main`: target `main`; require a pull request before merging; require status checks to pass and add `CI / build`; block force pushes; restrict deletions.
3. **Settings → Environments → New environment** named `production`: add yourself under *Required reviewers*. Under *Environment variables* add `DEV_BASE_URL` once the development URL exists (step 3.3).
4. **Settings → Rules → New branch ruleset** named `production`: target `production`; restrict updates and deletions for everyone; in the **Bypass list** add *GitHub Actions* (the release workflow pushes with the repository token). Do this only after I create the `production` branch (roadmap 1.9).
5. **Settings → Actions → General**: *Workflow permissions* → "Read and write permissions" (needed for the fast-forward push), and leave "Allow GitHub Actions to create and approve pull requests" off.

### 3.2 Decisions that shape the development environment

- **Q1** answered; the two empty projects were deleted on 2026-10-27. Environment `staging` renamed to `production` on 2026-10-27.
- **Q4** answered 2026-10-27: share the production Firebase project at first.
- **Q5** answered 2026-10-27: `api.shifa.uz` added in Railway and in DNS the same day; `api-dev.shifa.uz` follows when the development service exists. Railway hostnames stay. Apps' base URLs change on branches once the certificate is verified (roadmap Q5 has the sequence).
- **Q7** answered: only you.
- **Q8** answered: Railway's own object storage.

### 3.3 Gather development credentials (do not paste them to me; you enter them in Railway)

| Secret | Where to get a development value |
|---|---|
| `JWT_SECRET` | generate: `openssl rand -base64 64` |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | **Now (shared project):** Firebase console → the existing project → Project settings → Service accounts → *Generate new private key*. Paste the JSON into the development Railway variable. This key is separate from production's and can be revoked on its own. **Later (separate project, Phase 2):** see "Separating Firebase" below. |
| `OPENAI_API_KEY` | platform.openai.com → a new project named `shifa-dev` → API keys. Keeps the bill readable. |
| `DAILY_API_KEY`, `DAILY_WEBHOOK_SECRET` | Daily dashboard → a second domain (free) → API key; webhook created with `scripts/create-daily-webhook.sh` against the dev URL |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | Stripe dashboard → **Test mode** → Developers → API keys; webhook endpoint `https://<dev-url>/api/webhooks/stripe` in test mode |
| `CLICK_SECRET_KEY` | Click test merchant if you have one; otherwise Click stays disabled in `application-dev.yml` |
| `DEVSMS_API_TOKEN` | same account, or leave unset so dev logs SMS instead of sending (I will make that the dev default) |
| `MAIL_PASSWORD` | same Brevo account |

**Separating Firebase (Phase 2, when development is stable; about two hours):**

1. Firebase console → *Add project* → `shifa-dev`. Enable **Authentication → Sign-in method → Phone**, and under *Phone numbers for testing* add a few test numbers with fixed codes (free, no SMS).
2. Register the three apps with the **same ids** as production: Android `com.shifa.patientapp`, iOS `uz.shifa.patient`, and a Web app for the doctor app. Download `google-services.json` and `GoogleService-Info.plist`.
3. iOS pushes: Apple Developer → Keys → create an APNs key (or reuse the existing one), upload it under *Project settings → Cloud Messaging → Apple app configuration*. Without it, dev pushes never reach iPhones.
4. Patient app repo: put the two files in the **debug** source sets only (`composeApp/src/androidMain/debug/google-services.json`, the plist selected by the Debug configuration in Xcode), and add `FIREBASE_ANDROID_CONFIG_DEV_BASE64` / `FIREBASE_IOS_CONFIG_DEV_BASE64` secrets for the App Distribution workflow. Release builds keep the production files.
5. Doctor app repo: the dev Firebase web config goes behind the same `--dart-define` mechanism as `API_BASE_URL` (e.g. `FIREBASE_ENV=dev`), used only by the dev build.
6. Backend: in the `shifa-dev` project generate a service-account key and set it as `FIREBASE_SERVICE_ACCOUNT_JSON` in the development environment, replacing the shared one.
7. Verify on development: doctor phone login with a test number, and a push to a debug build on Android and iOS.

### 3.4 Create the development environment — **done 2026-10-28** (and the production gate: `production` branch + trigger switched the same day)

Environment `development` (id `c59cc5d3-393b-4946-a792-ed611d91be79`) created from production with deploys skipped; backend `DATABASE_URL` set to `${{Postgres.DATABASE_URL}}` **before** the first boot; new `JWT_SECRET`; dev Firebase service-account key; `APP_PUBLICBASEURL=https://api-dev.shifa.uz`; `CLICK_ENABLED=false`; `PAYMENT_ENABLED_PROVIDERS=NONE`; live Stripe keys, the SMS token and two stray variables removed; volumes added for Postgres (`/var/lib/postgresql/data`) and uploads (`/app/storage`). First deploy succeeded: Flyway applied all 117 migrations to the empty dev database; health 200 at `https://shifa-doc-backend-mvp-development.up.railway.app`. Custom domain `api-dev.shifa.uz` added and **verified 2026-10-28** (CNAME `api-dev` → `gl2b1tqq.up.railway.app` + TXT `_railway-verify.api-dev`; certificate valid to 2027-01-26; liveness 200, CORS preflight from `doctor.shifa.uz` 200). Profile is still `prod` until PR 1.5 adds `dev`. Original plan for reference:

`railway environment new development`, `railway add --database postgres -e development`, the variables from 3.3 (you paste the development secrets into Railway yourself, or hand them to me in the session and I set them with `railway variable set -e development`; they never go into the repo), `railway domain -e development`. The source branch and Wait for CI go through `railway api`. I verify the one check that matters most before the first deploy: the development backend's database variables reference the **new** development Postgres, never the production one. I give you the development URL for the apps' debug builds.

### 3.5 Backups (roadmap 13.5) — **done 2026-10-27**

Daily backups exist for both the Postgres volume and the uploads volume, 6-day retention. I propose adding a weekly schedule with 90-day retention (roadmap 3.2 step 9); say "go" and I add it through the API.

---

## 4. Before Phase 2 (platform hygiene) — **I do this once connected**

- Memory limit: I read it and either size the JVM to it or ask your "go" to raise production to 2 GB.
- The three Postgres reference variables (roadmap 2.2): I add them in development, then in production on your "go".
- Object storage per Q8: if Railway's own, I create it in the project; if Cloudflare R2, you create the bucket and API token in Cloudflare and paste the two keys into Railway (or hand them to me in the session).

---

## 5. Phase 3 and later

Nothing to prepare. Dependency upgrades, the patient app items and the modular-monolith moves need only PR approvals and the dev soak.

---

## 6. What you will get from me, per PR

1. Branch name and a one-paragraph summary of the change.
2. Which roadmap item and which scorecard criteria it moves, with before/after numbers where measurable.
3. Test output (`./gradlew build`), and once CI exists, the green check.
4. The exact smoke checks to run in the apps after deploy.
5. Any SQL you must run, read-only unless stated, and the rollback (always: Railway "Redeploy" of the previous deployment).

Approve by saying "merge" (or "commit", "push", "open PR" at the earlier stages). Each PR needs its own approval.

---

## 7. Paste-back template

```
### 0 connected
railway login + link: done

### 1.1 roadmap branch
commit and open the PR: yes/no   |   CLAUDE.md change: mine, keep / discard

### 1.4 Daily key rotated: yes / date

### 1.6 live builds
Play: ...   App Store: ...   Doctor app platforms/versions: ...

### 1.7 decisions
Q10: ...   Q12: ...   Q3: ...

### 3.2 decisions (when ready)
Q1: ...   Q4: ...   Q5: ...   Q7: ...   Q8: ...

### 3.4 development URL (when ready)
https://...

### 3.5 backups
on/off, schedule, retention
```
