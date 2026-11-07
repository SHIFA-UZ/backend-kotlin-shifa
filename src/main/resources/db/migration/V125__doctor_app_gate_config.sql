-- Force-update and maintenance switches for the doctor app, served in GET /api/public/config as "doctorApp".
-- Admins edit them with PUT /api/admin/config/{key}; an empty value means "not set" (no gate).
INSERT INTO system_config (config_key, config_value, description) VALUES
  ('doctor_app.min_version.android', '', 'Doctor app: oldest Android version allowed to run (e.g. 1.0.1); empty = no gate'),
  ('doctor_app.min_version.ios', '', 'Doctor app: oldest iOS version allowed to run (e.g. 1.0.1); empty = no gate'),
  ('doctor_app.latest_version.android', '', 'Doctor app: newest Android version (soft "update available"); empty = none'),
  ('doctor_app.latest_version.ios', '', 'Doctor app: newest iOS version (soft "update available"); empty = none'),
  ('doctor_app.store_url.android', 'https://play.google.com/store/apps/details?id=com.shifa.doc', 'Doctor app: Google Play page'),
  ('doctor_app.store_url.ios', '', 'Doctor app: App Store page (https://apps.apple.com/app/id...)'),
  ('doctor_app.maintenance.active', 'false', 'Doctor app: true shows the maintenance screen instead of the app'),
  ('doctor_app.maintenance.message.en', '', 'Doctor app: maintenance message in English; empty = the app''s own text'),
  ('doctor_app.maintenance.message.uz', '', 'Doctor app: maintenance message in Uzbek; empty = the app''s own text'),
  ('doctor_app.maintenance.message.ru', '', 'Doctor app: maintenance message in Russian; empty = the app''s own text'),
  ('doctor_app.maintenance.until', '', 'Doctor app: expected end of maintenance, ISO-8601 instant (e.g. 2026-11-08T05:00:00Z); empty = unknown')
ON CONFLICT (config_key) DO NOTHING;
