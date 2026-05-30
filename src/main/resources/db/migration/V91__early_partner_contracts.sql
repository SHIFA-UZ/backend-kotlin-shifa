-- Early-user partnership contracts (admin-issued, per doctor, sequential number)
CREATE TABLE IF NOT EXISTS early_partner_contract (
  id                  BIGSERIAL PRIMARY KEY,
  doctor_profile_id   BIGINT NOT NULL UNIQUE REFERENCES doctor_profiles(id) ON DELETE CASCADE,
  contract_seq        INT NOT NULL UNIQUE,
  contract_number     VARCHAR(32) NOT NULL UNIQUE,
  effective_date      DATE NOT NULL,
  term_months         INT NOT NULL DEFAULT 6,
  partner_full_name   TEXT NOT NULL,
  partner_clinic      TEXT,
  partner_phone       TEXT,
  partner_email       TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_early_partner_contract_seq ON early_partner_contract (contract_seq);

INSERT INTO system_config (config_key, config_value, description) VALUES
  ('early_partner_contract_next_seq', '461', 'Next SHIFA early-partner contract sequence (SHIFA-0461, then 0462, …)'),
  ('early_partner_contract_effective_date', '2026-06-01', 'Default contract effective date (ISO yyyy-MM-dd)'),
  ('early_partner_contract_term_months', '6', 'Default contract term in months')
ON CONFLICT (config_key) DO NOTHING;
