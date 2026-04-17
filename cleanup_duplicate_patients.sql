-- Clean up duplicate patient profiles
-- This script keeps the most recent patient profile (highest ID) for each phone/email
-- and deletes older duplicates, handling foreign key constraints

DO $$
DECLARE
    duplicate_phone RECORD;
    duplicate_email RECORD;
    patient_to_keep BIGINT;
    patient_to_delete BIGINT;
    deleted_count INTEGER := 0;
BEGIN
    RAISE NOTICE 'Starting cleanup of duplicate patient profiles...';
    
    -- ============================================
    -- Step 1: Handle duplicates by phone
    -- ============================================
    RAISE NOTICE 'Checking for duplicate phone numbers...';
    
    FOR duplicate_phone IN 
        SELECT phone, COUNT(*) as count, ARRAY_AGG(id ORDER BY id DESC) as ids
        FROM patient_profiles
        WHERE phone IS NOT NULL AND phone != ''
        GROUP BY phone
        HAVING COUNT(*) > 1
    LOOP
        -- Keep the most recent (highest ID)
        patient_to_keep := duplicate_phone.ids[1];
        RAISE NOTICE 'Found % duplicates for phone: %. Keeping patient ID: %', 
            duplicate_phone.count, duplicate_phone.phone, patient_to_keep;
        
        -- Delete older duplicates
        FOR i IN 2..array_length(duplicate_phone.ids, 1) LOOP
            patient_to_delete := duplicate_phone.ids[i];
            
            -- Update foreign key references before deleting
            -- Update appointments
            UPDATE appointments 
            SET patient_id = patient_to_keep 
            WHERE patient_id = patient_to_delete;
            
            -- Update doctor reviews
            UPDATE doctor_reviews 
            SET patient_id = patient_to_keep 
            WHERE patient_id = patient_to_delete;
            
            -- Update patient forms (if they reference patient_id)
            -- Note: Check if this table exists and has patient_id column
            -- UPDATE patient_forms SET patient_id = patient_to_keep WHERE patient_id = patient_to_delete;
            
            -- Now delete the duplicate
            DELETE FROM patient_profiles WHERE id = patient_to_delete;
            deleted_count := deleted_count + 1;
            
            RAISE NOTICE 'Deleted duplicate patient ID: % (phone: %)', 
                patient_to_delete, duplicate_phone.phone;
        END LOOP;
    END LOOP;
    
    -- ============================================
    -- Step 2: Handle duplicates by email
    -- ============================================
    RAISE NOTICE 'Checking for duplicate email addresses...';
    
    FOR duplicate_email IN 
        SELECT email, COUNT(*) as count, ARRAY_AGG(id ORDER BY id DESC) as ids
        FROM patient_profiles
        WHERE email IS NOT NULL AND email != ''
        GROUP BY email
        HAVING COUNT(*) > 1
    LOOP
        -- Keep the most recent (highest ID)
        patient_to_keep := duplicate_email.ids[1];
        RAISE NOTICE 'Found % duplicates for email: %. Keeping patient ID: %', 
            duplicate_email.count, duplicate_email.email, patient_to_keep;
        
        -- Delete older duplicates
        FOR i IN 2..array_length(duplicate_email.ids, 1) LOOP
            patient_to_delete := duplicate_email.ids[i];
            
            -- Skip if this patient was already deleted in phone cleanup
            IF EXISTS (SELECT 1 FROM patient_profiles WHERE id = patient_to_delete) THEN
                -- Update foreign key references before deleting
                UPDATE appointments 
                SET patient_id = patient_to_keep 
                WHERE patient_id = patient_to_delete;
                
                UPDATE doctor_reviews 
                SET patient_id = patient_to_keep 
                WHERE patient_id = patient_to_delete;
                
                -- Now delete the duplicate
                DELETE FROM patient_profiles WHERE id = patient_to_delete;
                deleted_count := deleted_count + 1;
                
                RAISE NOTICE 'Deleted duplicate patient ID: % (email: %)', 
                    patient_to_delete, duplicate_email.email;
            END IF;
        END LOOP;
    END LOOP;
    
    RAISE NOTICE 'Cleanup completed. Total duplicates deleted: %', deleted_count;
    
    -- ============================================
    -- Step 3: Show summary
    -- ============================================
    RAISE NOTICE 'Remaining patient profiles: %', (SELECT COUNT(*) FROM patient_profiles);
    
END $$;

-- Verify cleanup results
SELECT 
    'Phone duplicates' as check_type,
    phone,
    COUNT(*) as count
FROM patient_profiles
WHERE phone IS NOT NULL AND phone != ''
GROUP BY phone
HAVING COUNT(*) > 1;

SELECT 
    'Email duplicates' as check_type,
    email,
    COUNT(*) as count
FROM patient_profiles
WHERE email IS NOT NULL AND email != ''
GROUP BY email
HAVING COUNT(*) > 1;

-- If both queries return no rows, cleanup was successful!
