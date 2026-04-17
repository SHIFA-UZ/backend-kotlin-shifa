# Daily.co Webhook Setup (AI Scribe)

The AI scribe needs Daily to send **recording.ready-to-download** events to your backend so it can download the recording, transcribe it, and create a draft note.

Per [Daily's API](https://docs.daily.co/reference/rest-api/webhooks/create), webhooks are created via **POST /webhooks**. The dashboard may not show a "Webhooks" UI for all plans; using the API is reliable.

## Option 1: Run the script (Bash / Git Bash / WSL)

From the backend repo root, with your Daily API key set:

```bash
export DAILY_API_KEY="your-daily-api-key"
./scripts/create-daily-webhook.sh
```

On **Windows PowerShell** use Option 3 below (PowerShell doesn't support `export`).

To use a different webhook URL (e.g. staging):

```bash
export DAILY_API_KEY="your-key"
export DAILY_WEBHOOK_URL="https://your-staging-backend.example.com/api/webhooks/daily"
./scripts/create-daily-webhook.sh
```

## Option 2: curl one-liner

Replace `YOUR_DAILY_API_KEY` with your actual key:

```bash
curl -X POST "https://api.daily.co/v1/webhooks" \
  -H "Authorization: Bearer YOUR_DAILY_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://shifa-doc-backend-mvp-production.up.railway.app/api/webhooks/daily","eventTypes":["recording.ready-to-download"]}'
```

You should get a **200** response with a JSON body containing `uuid`, `url`, `eventTypes`, etc. If you get **400**, Daily could not reach your URL (e.g. backend not returning 200 for the test request).

## Option 3: PowerShell (Windows)

```powershell
$apiKey = "YOUR_DAILY_API_KEY"
$body = @{
  url = "https://shifa-doc-backend-mvp-production.up.railway.app/api/webhooks/daily"
  eventTypes = @("recording.ready-to-download")
} | ConvertTo-Json

Invoke-RestMethod -Uri "https://api.daily.co/v1/webhooks" `
  -Method Post `
  -Headers @{ "Authorization" = "Bearer $apiKey"; "Content-Type" = "application/json" } `
  -Body $body
```

## Verify

- After creating the webhook, start a **new** video call, record, and end the call.
- Within 1–2 minutes, check Railway logs for: `Daily webhook received: type=recording.ready-to-download` and `Scribe pipeline started`.
- In the doctor app, reopen the appointment; the notes carousel should show an **"AI Scribe - Consultation Summary"** draft.

## List or delete webhooks

- **GET** `https://api.daily.co/v1/webhooks` (with `Authorization: Bearer YOUR_DAILY_API_KEY`) to list webhooks.
- **DELETE** `https://api.daily.co/v1/webhooks` or **DELETE** `https://api.daily.co/v1/webhooks/:uuid` to remove (see [Daily webhook docs](https://docs.daily.co/reference/rest-api/webhooks/create)).
