# Deploying Two Separate Environments (Internal Test vs Production/QA)

You can run **this** backend in two completely separate Railway setups:

- **Environment A (internal test):** This repo → Railway Project A (e.g. “Shifa Internal”)
- **Environment B (production/QA):** Another repo → Railway Project B (e.g. “Shifa Production” or “Shifa QA”)

Each environment has its own database, domain, and env vars. They do not affect each other.

---

## Option 1: Two repos, two Railway projects (recommended)

Best when you want a clear split (e.g. internal code vs release code, or different teams).

### Environment A – Internal test (this workspace)

1. **Create a new Railway project** (e.g. “Shifa Backend – Internal”).
2. In that project, **New → GitHub Repo** and connect **this** repository (the one you’re in now).
3. Add a **Postgres** service in the same project and **link** it to the backend (so `DATABASE_URL` is set).
4. Configure **Variables** for this project only (e.g. `JWT_SECRET`, `APP_PUBLICBASEURL`, `APP_FRONTENDURL`, etc.). Use internal URLs (e.g. `https://shifa-internal.railway.app`).
5. Deploy from the branch you use for internal work (e.g. `main` or `develop`).

Result: this repo deploys only to “Internal” Railway. Its DB and URLs are for internal test only.

### Environment B – Production or QA (other repo)

1. **Create a second Railway project** (e.g. “Shifa Backend – Production” or “Shifa QA”).
2. **New → GitHub Repo** and connect your **other** repository (production/QA codebase).
3. Add **Postgres** in this project and link it to the backend.
4. Configure **Variables** for this project (production/QA URLs, secrets, etc.).
5. Deploy from the branch you use for production/QA (e.g. `production` or `release`).

Result: the other repo deploys only to “Production/QA” Railway. Completely separate DB and config.

**Keeping code in sync (optional):**  
- Merge from internal repo → production repo when you promote (e.g. PR from internal repo into prod repo), or  
- Use the same repo and Option 2 below.

---

## Option 2: One repo, two Railway projects (prod vs qa profile)

This repo uses two Spring profiles: **`prod`** → `application-prod.yml` (current Railway / internal test); **`qa`** → `application-qa.yml` (new Railway / QA). Same codebase; each project has its own Postgres and Variables.

### Environment A – Internal test

1. Create Railway project **“Shifa Backend – Internal”**.
2. Connect it to **this** GitHub repo.
3. In **Settings**, set **Root Directory** (if needed) and **Branch** (e.g. `main` or `develop`).
4. Add Postgres, link it, set Variables (internal URLs and secrets).
5. Deploy. All deploys from the chosen branch go to this project.

### Environment B – Production/QA

1. Create a **second** Railway project **“Shifa Backend – Production”** (or “QA”).
2. Connect it to the **same** GitHub repo.
3. In **Settings**, set **Branch** to a different branch (e.g. `production` or `release`). Optionally use a different **Root Directory** if you ever split apps.
4. Add **Postgres** in this project (separate DB), link it, set Variables (production/QA URLs and secrets).
5. Deploy. Pushes to the production branch deploy only to this project.

Result: one repo, two Railway projects, two branches, two databases and two sets of env vars. No overlap.

---

## Checklist per environment (either option)

For **each** Railway project you must:

| Item | Internal (A) | Production/QA (B) |
|------|----------------|-------------------|
| Railway project | e.g. Shifa Internal | e.g. Shifa Production |
| GitHub repo | This repo (or same repo) | Other repo (or same repo + different branch) |
| Postgres | One per project (linked) | One per project (linked) |
| `DATABASE_URL` | Set by linking Postgres | Set by linking Postgres |
| `APP_PUBLICBASEURL` | Internal backend URL | Production/QA backend URL |
| `APP_FRONTENDURL` | Internal frontend URL | Production/QA frontend URL |
| `JWT_SECRET` | Unique per environment | Unique per environment |
| Other API keys | Internal/test keys OK | Production keys |

Use **different** `JWT_SECRET` and URLs in each project so tokens and CORS are correct per environment.

---

## Quick start for “this” backend (internal test only)

1. Railway dashboard → **New Project**.
2. **Add service** → **GitHub Repo** → select this repository.
3. **Add service** → **Database** → **PostgreSQL** → create, then **link** it to the backend service (Variables → `DATABASE_URL` reference or automatic link).
4. In backend service **Variables**, set at least:
   - `APP_PUBLICBASEURL` = `https://<your-internal-backend>.railway.app`
   - `APP_FRONTENDURL` = your internal frontend URL (if any)
   - `JWT_SECRET` = generate a strong secret (e.g. `openssl rand -base64 64`)
   - Plus any other vars from `ENVIRONMENT_VARIABLES_SETUP.md`.
5. Deploy. Your internal test backend and DB are isolated from production/QA.

When you’re ready for the second environment, repeat the same steps in a **new** Railway project and point it at the other repo (or the same repo with a different branch), with its own Postgres and Variables.
