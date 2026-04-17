-- Complete cleanup script for duplicate patient profiles
-- This script:
-- 1. Identifies duplicates by phone and email
-- 2. Updates all foreign key references to point to the most recent patient
-- 3. Deletes older duplicate patient profiles
-- 4. Verifies the cleanup

BEGIN;

-- ============================================
-- Step 1: Show duplicates before cleanup
-- ============================================
SELECT '=== BEFORE CLEANUP ===' as status;

SELECT 'Duplicates by phone:' as info;
SELECT phone, COUNT(*) as count, ARRAY_AGG(id ORDER BY id) as patient_ids
FROM patient_profiles
WHERE phone IS NOT NULL AND phone != ''
GROUP BY phone
HAVING COUNT(*) > 1;

SELECT 'Duplicates by email:' as info;
SELECT email, COUNT(*) as count, ARRAY_AGG(id ORDER BY id) as patient_ids
FROM patient_profiles
WHERE email IS NOT NULL AND email != ''
GROUP BY email
HAVING COUNT(*) > 1;

-- ============================================
-- Step 2: Update foreign key references
-- ============================================

-- Update appointments to use the most recent patient (highest ID)
UPDATE appointments a
SET patient_id = (
    SELECT MAX(p.id)
    FROM patient_profiles p
    WHERE p.id IN (
        SELECT id FROM patient_profiles 
        WHERE (phone = (SELECT phone FROM patient_profiles WHERE id = a.patient_id) 
               AND phone IS NOT NULL AND phone != '')
           OR (email = (SELECT email FROM patient_profiles WHERE id = a.patient_id) 
               AND email IS NOT NULL AND email != '')
    )
)
WHERE EXISTS (
    SELECT 1 FROM patient_profiles p1, patient_profiles p2
    WHERE p1.id = a.patient_id
    AND (
        (p1.phone IS NOT NULL AND p1.phone != '' AND p2.phone = p1.phone AND p2.id > p1.id)
        OR (p1.email IS NOT NULL AND p1.email != '' AND p2.email = p1.email AND p2.id > p1.id)
    )
);

-- Update doctor_reviews to use the most recent patient
UPDATE doctor_reviews dr
SET patient_id = (
    SELECT MAX(p.id)
    FROM patient_profiles p
    WHERE p.id IN (
        SELECT id FROM patient_profiles 
        WHERE (phone = (SELECT phone FROM patient_profiles WHERE id = dr.patient_id) 
               AND phone IS NOT NULL AND phone != '')
           OR (email = (SELECT email FROM patient_profiles WHERE id = dr.patient_id) 
               AND email IS NOT NULL AND email != '')
    )
)
WHERE EXISTS (
    SELECT 1 FROM patient_profiles p1, patient_profiles p2
    WHERE p1.id = dr.patient_id
    AND (
        (p1.phone IS NOT NULL AND p1.phone != '' AND p2.phone = p1.phone AND p2.id > p1.id)
        OR (p1.email IS NOT NULL AND p1.email != '' AND p2.email = p1.email AND p2.id > p1.id)
    )
);

-- Update patient_forms to use the most recent patient
UPDATE patient_forms pf
SET patient_id = (
    SELECT MAX(p.id)
    FROM patient_profiles p
    WHERE p.id IN (
        SELECT id FROM patient_profiles 
        WHERE (phone = (SELECT phone FROM patient_profiles WHERE id = pf.patient_id) 
               AND phone IS NOT NULL AND phone != '')
           OR (email = (SELECT email FROM patient_profiles WHERE id = pf.patient_id) 
               AND email IS NOT NULL AND email != '')
    )
)
WHERE EXISTS (
    SELECT 1 FROM patient_profiles p1, patient_profiles p2
    WHERE p1.id = pf.patient_id
    AND (
        (p1.phone IS NOT NULL AND p1.phone != '' AND p2.phone = p1.phone AND p2.id > p1.id)
        OR (p1.email IS NOT NULL AND p1.email != '' AND p2.email = p1.email AND p2.id > p1.id)
    )
);

-- Update patient_documents to use the most recent patient
UPDATE patient_documents pd
SET patient_id = (
    SELECT MAX(p.id)
    FROM patient_profiles p
    WHERE p.id IN (
        SELECT id FROM patient_profiles 
        WHERE (phone = (SELECT phone FROM patient_profiles WHERE id = pd.patient_id) 
               AND phone IS NOT NULL AND phone != '')
           OR (email = (SELECT email FROM patient_profiles WHERE id = pd.patient_id) 
               AND email IS NOT NULL AND email != '')
    )
)
WHERE EXISTS (
    SELECT 1 FROM patient_profiles p1, patient_profiles p2
    WHERE p1.id = pd.patient_id
    AND (
        (p1.phone IS NOT NULL AND p1.phone != '' AND p2.phone = p1.phone AND p2.id > p1.id)
        OR (p1.email IS NOT NULL AND p1.email != '' AND p2.email = p1.email AND p2.id > p1.id)
    )
);

-- Update messages (recipient_patient_id) to use the most recent patient
UPDATE messages m
SET recipient_patient_id = (
    SELECT MAX(p.id)
    FROM patient_profiles p
    WHERE p.id IN (
        SELECT id FROM patient_profiles 
        WHERE (phone = (SELECT phone FROM patient_profiles WHERE id = m.recipient_patient_id) 
               AND phone IS NOT NULL AND phone != '')
           OR (email = (SELECT email FROM patient_profiles WHERE id = m.recipient_patient_id) 
               AND email IS NOT NULL AND email != '')
    )
)
WHERE m.recipient_patient_id IS NOT NULL
AND EXISTS (
    SELECT 1 FROM patient_profiles p1, patient_profiles p2
    WHERE p1.id = m.recipient_patient_id
    AND (
        (p1.phone IS NOT NULL AND p1.phone != '' AND p2.phone = p1.phone AND p2.id > p1.id)
        OR (p1.email IS NOT NULL AND p1.email != '' AND p2.email = p1.email AND p2.id > p1.id)
    )
);

-- ============================================
-- Step 3: Delete duplicate patient profiles
-- ============================================

-- Delete duplicates by phone (keep the one with highest ID)
DELETE FROM patient_profiles
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY phone ORDER BY id DESC) as rn
        FROM patient_profiles
        WHERE phone IS NOT NULL AND phone != ''
    ) t
    WHERE t.rn > 1
);

-- Delete duplicates by email (keep the one with highest ID)
-- Only delete if not already deleted in phone cleanup
DELETE FROM patient_profiles
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY email ORDER BY id DESC) as rn
        FROM patient_profiles
        WHERE email IS NOT NULL AND email != ''
    ) t
    WHERE t.rn > 1
);

-- ============================================
-- Step 4: Verify cleanup
-- ============================================
SELECT '=== AFTER CLEANUP ===' as status;

SELECT 'Remaining duplicates by phone:' as info;
SELECT phone, COUNT(*) as count
FROM patient_profiles
WHERE phone IS NOT NULL AND phone != ''
GROUP BY phone
HAVING COUNT(*) > 1;

SELECT 'Remaining duplicates by email:' as info;
SELECT email, COUNT(*) as count
FROM patient_profiles
WHERE email IS NOT NULL AND email != ''
GROUP BY email
HAVING COUNT(*) > 1;

SELECT 'Total patient profiles:' as info, COUNT(*) as count
FROM patient_profiles;

COMMIT;

-- If the duplicate queries return no rows, cleanup was successful!
