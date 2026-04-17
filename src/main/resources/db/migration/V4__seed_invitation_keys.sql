-- Optional: seed several VerifyKey codes for testing
INSERT INTO invitation_keys (key_code, consumed)
VALUES ('BEKZOD', FALSE),
       ('SHIFA-2025', FALSE),
       ('TEST-KEY', FALSE)
ON CONFLICT (key_code) DO NOTHING;
