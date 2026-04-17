-- Simple cleanup script for duplicate patient profiles
-- Keeps the most recent patient (highest ID) and deletes older duplicates

-- Step 1: Show duplicates before cleanup
SELECT 'BEFORE CLEANUP - Duplicates by phone:' as info;
SELECT phone, COUNT(*) as count, ARRAY_AGG(id ORDER BY id) as patient_ids
FROM patient_profiles
WHERE phone IS NOT NULL AND phone != ''
GROUP BY phone
HAVING COUNT(*) > 1;

SELECT 'BEFORE CLEANUP - Duplicates by email:' as info;
SELECT email, COUNT(*) as count, ARRAY_AGG(id ORDER BY id) as patient_ids
FROM patient_profiles
WHERE email IS NOT NULL AND email != ''
GROUP BY email
HAVING COUNT(*) > 1;

-- Step 2: Update foreign keys to point to the most recent patient
-- Update appointments to use the most recent patient profile
UPDATE appointments a
SET patient_id = (
    SELECT MAX(id) 
    FROM patient_profiles p 
    WHERE (p.phone = (SELECT phone FROM patient_profiles WHERE id = a.patient_id) 
           OR p.email = (SELECT email FROM patient_profiles WHERE id = a.patient_id))
)
WHERE EXISTS (
    SELECT 1 
    FROM patient_profiles p1, patient_profiles p2
    WHERE p1.id = a.patient_id
    AND ((p1.phone IS NOT NULL AND p1.phone != '' AND p2.phone = p1.phone)
         OR (p1.email IS NOT NULL AND p1.email != '' AND p2.email = p1.email))
    AND p2.id > p1.id
);

-- Update doctor reviews to use the most recent patient profile
UPDATE doctor_reviews dr
SET patient_id = (
    SELECT MAX(id) 
    FROM patient_profiles p 
    WHERE (p.phone = (SELECT phone FROM patient_profiles WHERE id = dr.patient_id) 
           OR p.email = (SELECT email FROM patient_profiles WHERE id = dr.patient_id))
)
WHERE EXISTS (
    SELECT 1 
    FROM patient_profiles p1, patient_profiles p2
    WHERE p1.id = dr.patient_id
    AND ((p1.phone IS NOT NULL AND p1.phone != '' AND p2.phone = p1.phone)
         OR (p1.email IS NOT NULL AND p1.email != '' AND p2.email = p1.email))
    AND p2.id > p1.id
);

-- Step 3: Delete duplicate patient profiles (keep only the most recent)
-- Delete duplicates by phone
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

-- Delete duplicates by email (that weren't already deleted)
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

-- Step 4: Verify cleanup
SELECT 'AFTER CLEANUP - Remaining duplicates by phone:' as info;
SELECT phone, COUNT(*) as count
FROM patient_profiles
WHERE phone IS NOT NULL AND phone != ''
GROUP BY phone
HAVING COUNT(*) > 1;

SELECT 'AFTER CLEANUP - Remaining duplicates by email:' as info;
SELECT email, COUNT(*) as count
FROM patient_profiles
WHERE email IS NOT NULL AND email != ''
GROUP BY email
HAVING COUNT(*) > 1;

SELECT 'Total patient profiles remaining:' as info, COUNT(*) as count
FROM patient_profiles;
