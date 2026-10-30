-- The admin metrics endpoint counts appointments created per day over the last 30 days on every refresh.
CREATE INDEX IF NOT EXISTS idx_appointments_created_at ON appointments (created_at);
