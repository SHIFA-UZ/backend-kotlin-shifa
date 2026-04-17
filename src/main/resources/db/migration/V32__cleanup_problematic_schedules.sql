-- V32: Cleanup problematic schedule rules
-- This migration fixes existing schedule rules that could cause memory issues

-- ============================================
-- Fix 1: Update slot_minutes for rules that generate too many slots (>200)
-- ============================================
UPDATE weekly_schedule_rules
SET slot_minutes = GREATEST(
    5,  -- minimum 5 minutes
    CEIL(EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / 200)::int
)
WHERE EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / slot_minutes > 200;

-- ============================================
-- Fix 2: Update slot_minutes for rules with <5 minutes
-- ============================================
UPDATE weekly_schedule_rules
SET slot_minutes = 5
WHERE slot_minutes < 5;

-- ============================================
-- Fix 3: Remove overlapping rules (keeps the one with earlier start_time)
-- This prevents duplicate slot generation
-- ============================================
DELETE FROM weekly_schedule_rules
WHERE id IN (
    SELECT w2.id
    FROM weekly_schedule_rules w1
    JOIN weekly_schedule_rules w2 
        ON w1.doctor_id = w2.doctor_id 
        AND w1.weekday = w2.weekday
        AND w1.id < w2.id
    WHERE w1.start_time < w2.end_time 
        AND w2.start_time < w1.end_time
);

-- ============================================
-- Fix 4: Fix date-specific rules with too many slots
-- ============================================
UPDATE date_specific_schedule_rules
SET slot_minutes = GREATEST(
    5,
    CEIL(EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / 200)::int
)
WHERE EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / slot_minutes > 200;

-- ============================================
-- Fix 5: Fix date-specific rules with <5 minutes
-- ============================================
UPDATE date_specific_schedule_rules
SET slot_minutes = 5
WHERE slot_minutes < 5;
