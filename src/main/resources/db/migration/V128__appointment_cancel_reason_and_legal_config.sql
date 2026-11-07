-- Why the doctor's side cancelled a visit (POST /api/appointments/{id}/cancel); null for every other cancellation.
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS cancel_reason_code VARCHAR(32);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS cancel_note TEXT;

-- The same reason on the patient's APPOINTMENT_CANCELLED notification (inbox and push data).
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS cancel_reason_code VARCHAR(32);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS cancel_note TEXT;

-- Legal documents served by GET /api/public/legal. Admins edit them with PUT /api/admin/config/{key};
-- an empty value means "not set" (a per-language URL falls back to the default one).
INSERT INTO system_config (config_key, config_value, description) VALUES
  ('legal.terms_url', 'https://shifa.uz/#terms', 'Terms of use URL (default for every language)'),
  ('legal.terms_url.uz', '', 'Terms of use URL in Uzbek; empty = legal.terms_url'),
  ('legal.terms_url.ru', '', 'Terms of use URL in Russian; empty = legal.terms_url'),
  ('legal.privacy_url', 'https://shifa.uz/#privacy', 'Privacy policy URL (default for every language)'),
  ('legal.privacy_url.uz', '', 'Privacy policy URL in Uzbek; empty = legal.privacy_url'),
  ('legal.privacy_url.ru', '', 'Privacy policy URL in Russian; empty = legal.privacy_url'),
  ('legal.licences_url', '', 'Open-source licences URL; empty = none'),
  ('legal.version', '1', 'Version of the legal documents; raise it when the texts change'),
  ('legal.updated_at', '2026-11-07', 'Date the legal documents last changed (yyyy-MM-dd)')
ON CONFLICT (config_key) DO NOTHING;
