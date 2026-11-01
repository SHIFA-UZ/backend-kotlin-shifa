-- The admin metrics endpoint reads the last 24 hours of payments and AI calls on every refresh.
CREATE INDEX IF NOT EXISTS idx_payments_created_at ON payments (created_at);
CREATE INDEX IF NOT EXISTS idx_ai_llm_request_log_created_at ON ai_llm_request_log (created_at);
