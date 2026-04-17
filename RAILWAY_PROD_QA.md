# Railway: prod vs qa (one repo, two projects)

- **Prod Railway:** uses **`application-prod.yml`** (profile `prod`).
- **QA Railway:** uses **`application-qa.yml`** (profile `qa`).

Same repo; each Railway project has its own Postgres and Variables.

---

## Profile selection (Option 2)

The start command in `railway.toml`, `railway.json`, and `nixpacks.toml` does **not** include `--spring.profiles.active`.  
Profile is set per project via the **Variable**:

- **Prod project:** `SPRING_PROFILES_ACTIVE` = **`prod`**
- **QA project:** `SPRING_PROFILES_ACTIVE` = **`qa`**

No custom start command in Railway Settings is needed.

---

## Prod Railway

1. **Variables:** set `SPRING_PROFILES_ACTIVE=prod`.
2. Keep Postgres linked (`DATABASE_URL` set) and other Variables (`JWT_SECRET`, `APP_PUBLICBASEURL`, `APP_FRONTENDURL`, API keys, etc.).

---

## QA Railway

1. **New project** (e.g. "Shifa Backend – QA"), deploy from same repo.
2. **Add Postgres** and **link** it to the backend service so `DATABASE_URL` is set automatically.
3. **Variables** (all required for the app to start):
   - `SPRING_PROFILES_ACTIVE` = `qa`
   - `JWT_SECRET` (e.g. a long random string; use a different value than prod)
   - `APP_PUBLICBASEURL` (e.g. `https://your-qa-backend.up.railway.app`)
   - `APP_FRONTENDURL` (optional; QA frontend URL for CORS)
   - `OPENAI_API_KEY`, `OPENAI_PROJECT_ID` (if you use OpenAI features)
   - `DAILY_API_KEY` (if you use video; get from https://dashboard.daily.co/)
   - `GOOGLE_MAPS_API_KEY` (optional)

If the app **exits immediately** or **never becomes healthy**, check: Postgres is linked (DATABASE_URL present) and the variables above are set.

---

## Healthcheck

- **Path:** `/actuator/health/liveness` (set in `railway.toml`). This returns 200 when the JVM is up; it does not check the database. Using liveness avoids failed deploys when the DB is temporarily slow or when the main `/actuator/health` would return 503.
- Missing required variables (e.g. `DATABASE_URL`, `JWT_SECRET`) prevent the app from starting, so the container never responds and the healthcheck fails.
