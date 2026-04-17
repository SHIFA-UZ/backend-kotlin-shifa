# Cleanup Problematic Schedule Data

## Problem

If a doctor has existing overlapping schedule rules or rules that generate too many slots, the calendar may still have issues even after the fixes. We need to clean up the existing problematic data.

## Solution Options

### Option 1: SQL Cleanup Script (Recommended)

Run this SQL script to identify and fix problematic schedule rules:

```sql
-- ============================================
-- STEP 1: Find overlapping weekly schedule rules
-- ============================================
-- This finds doctors with overlapping rules for the same weekday
SELECT 
    w1.doctor_id,
    w1.weekday,
    w1.start_time as rule1_start,
    w1.end_time as rule1_end,
    w1.slot_minutes as rule1_slots,
    w2.start_time as rule2_start,
    w2.end_time as rule2_end,
    w2.slot_minutes as rule2_slots,
    w1.id as rule1_id,
    w2.id as rule2_id
FROM weekly_schedule_rules w1
JOIN weekly_schedule_rules w2 
    ON w1.doctor_id = w2.doctor_id 
    AND w1.weekday = w2.weekday
    AND w1.id < w2.id
WHERE w1.start_time < w2.end_time 
    AND w2.start_time < w1.end_time
ORDER BY w1.doctor_id, w1.weekday;

-- ============================================
-- STEP 2: Find rules that generate too many slots (>200)
-- ============================================
SELECT 
    id,
    doctor_id,
    weekday,
    start_time,
    end_time,
    slot_minutes,
    EXTRACT(EPOCH FROM (end_time - start_time)) / 60 as duration_minutes,
    ROUND(EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / slot_minutes) as estimated_slots
FROM weekly_schedule_rules
WHERE EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / slot_minutes > 200
ORDER BY estimated_slots DESC;

-- ============================================
-- STEP 3: Find rules with very small slot_minutes (<5)
-- ============================================
SELECT 
    id,
    doctor_id,
    weekday,
    start_time,
    end_time,
    slot_minutes
FROM weekly_schedule_rules
WHERE slot_minutes < 5
ORDER BY slot_minutes;

-- ============================================
-- STEP 4: Manual Cleanup (Choose one approach)
-- ============================================

-- APPROACH A: Delete overlapping rules (keeps the first one)
-- WARNING: Review the results from STEP 1 first!
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

-- APPROACH B: Fix slot_minutes for rules that generate too many slots
-- This increases slot_minutes to ensure max 200 slots
UPDATE weekly_schedule_rules
SET slot_minutes = GREATEST(
    5,  -- minimum 5 minutes
    CEIL(EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / 200)::int
)
WHERE EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / slot_minutes > 200;

-- APPROACH C: Fix slot_minutes for rules with <5 minutes
UPDATE weekly_schedule_rules
SET slot_minutes = 5
WHERE slot_minutes < 5;

-- ============================================
-- STEP 5: Check date-specific rules for issues
-- ============================================
-- Find date-specific rules that generate too many slots
SELECT 
    id,
    doctor_id,
    start_date,
    end_date,
    start_time,
    end_time,
    slot_minutes,
    EXTRACT(EPOCH FROM (end_time - start_time)) / 60 as duration_minutes,
    ROUND(EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / slot_minutes) as estimated_slots
FROM date_specific_schedule_rules
WHERE EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / slot_minutes > 200
ORDER BY estimated_slots DESC;

-- Fix date-specific rules with too many slots
UPDATE date_specific_schedule_rules
SET slot_minutes = GREATEST(
    5,
    CEIL(EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / 200)::int
)
WHERE EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / slot_minutes > 200;

-- Fix date-specific rules with <5 minutes
UPDATE date_specific_schedule_rules
SET slot_minutes = 5
WHERE slot_minutes < 5;
```

### Option 2: Admin Endpoint (For Future)

We could add an admin endpoint to validate and clean schedules, but for now, SQL is faster.

### Option 3: Manual Fix via UI

1. Login as the problematic doctor
2. Go to Schedule setup
3. Review and manually fix overlapping rules
4. Save

## Recommended Steps

1. **Identify the problematic doctor**:
   ```sql
   -- Find doctors with overlapping rules
   SELECT DISTINCT doctor_id 
   FROM weekly_schedule_rules w1
   JOIN weekly_schedule_rules w2 
       ON w1.doctor_id = w2.doctor_id 
       AND w1.weekday = w2.weekday
       AND w1.id < w2.id
   WHERE w1.start_time < w2.end_time 
       AND w2.start_time < w1.end_time;
   ```

2. **Review their schedule**:
   ```sql
   SELECT * FROM weekly_schedule_rules 
   WHERE doctor_id = <PROBLEMATIC_DOCTOR_ID>
   ORDER BY weekday, start_time;
   ```

3. **Clean up**:
   - **Option A**: Delete overlapping rules (keeps first one)
   - **Option B**: Merge overlapping rules manually
   - **Option C**: Fix slot_minutes to prevent too many slots

4. **Verify**:
   ```sql
   -- Check if doctor still has issues
   SELECT 
       weekday,
       COUNT(*) as rule_count,
       SUM(CASE WHEN slot_minutes < 5 THEN 1 ELSE 0 END) as small_slots,
       SUM(CASE WHEN EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / slot_minutes > 200 THEN 1 ELSE 0 END) as too_many_slots
   FROM weekly_schedule_rules
   WHERE doctor_id = <PROBLEMATIC_DOCTOR_ID>
   GROUP BY weekday;
   ```

## Quick Fix for Specific Doctor

If you know the doctor's ID, here's a quick cleanup:

```sql
-- 1. Fix slot_minutes issues
UPDATE weekly_schedule_rules
SET slot_minutes = GREATEST(5, CEIL(EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / 200)::int)
WHERE doctor_id = <DOCTOR_ID>
    AND EXTRACT(EPOCH FROM (end_time - start_time)) / 60 / slot_minutes > 200;

UPDATE weekly_schedule_rules
SET slot_minutes = 5
WHERE doctor_id = <DOCTOR_ID> AND slot_minutes < 5;

-- 2. Remove overlapping rules (keeps the one with earlier start_time)
DELETE FROM weekly_schedule_rules
WHERE id IN (
    SELECT w2.id
    FROM weekly_schedule_rules w1
    JOIN weekly_schedule_rules w2 
        ON w1.doctor_id = w2.doctor_id 
        AND w1.weekday = w2.weekday
        AND w1.id < w2.id
    WHERE w1.doctor_id = <DOCTOR_ID>
        AND w1.start_time < w2.end_time 
        AND w2.start_time < w1.end_time
);
```

## After Cleanup

1. The calendar endpoint will work with the new safeguards
2. The doctor can still expand schedules using the new date-specific feature
3. Future schedule edits will be validated to prevent the problem

## Prevention

The new code prevents:
- ✅ Overlapping rules (validation in `ScheduleController`)
- ✅ Too many slots (max 200 per day)
- ✅ Very small slots (minimum 5 minutes)
- ✅ Overriding existing schedules (expansion-only policy)
