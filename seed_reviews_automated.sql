-- Automated SQL script to insert 10 reviews (4-5 stars) for each doctor
-- This script ONLY uses existing patients from the database (no new patients created)

DO $$
DECLARE
    doctor_rec RECORD;
    patient_rec RECORD;
    review_count INTEGER;
    available_patients BIGINT[];
    review_comments TEXT[] := ARRAY[
        'Excellent doctor, very professional and caring.',
        'Great bedside manner and thorough examination.',
        'Highly recommend, very knowledgeable and patient.',
        'Wonderful experience, felt heard and understood.',
        'Best doctor I have ever visited, very attentive.',
        'Professional service with excellent communication.',
        'Very satisfied with the treatment and care provided.',
        'Outstanding medical expertise and friendly approach.',
        'Great doctor who takes time to explain everything clearly.',
        'Exceptional care and attention to detail.',
        'Very professional and made me feel comfortable.',
        'Excellent diagnosis and treatment plan.',
        'Highly skilled doctor with great communication skills.',
        'Very thorough examination and clear explanations.',
        'Wonderful doctor, very caring and understanding.',
        'Great experience, felt well taken care of.',
        'Professional and knowledgeable, highly recommend.',
        'Excellent service and very patient with questions.',
        'Very satisfied with the quality of care.',
        'Outstanding doctor with great expertise.'
    ];
    ratings INTEGER[] := ARRAY[4, 4, 4, 4, 5, 5, 5, 5, 5, 5];
    comment_text TEXT;
    rating_val INTEGER;
    days_offset INTEGER;
    used_patients BIGINT[];
    patient_index INTEGER;
    total_patients INTEGER;
BEGIN
    -- Get total count of existing patients
    SELECT COUNT(*) INTO total_patients FROM patient_profiles;
    
    -- Check if we have any patients
    IF total_patients = 0 THEN
        RAISE NOTICE 'No patients found in database. Please create patients first.';
        RETURN;
    END IF;
    
    -- Loop through each doctor
    FOR doctor_rec IN SELECT id FROM doctor_profiles LOOP
        review_count := 0;
        used_patients := ARRAY[]::BIGINT[];
        
        -- Get all available patients for this doctor (those who haven't reviewed this doctor yet)
        SELECT ARRAY_AGG(id) INTO available_patients
        FROM patient_profiles
        WHERE id NOT IN (
            SELECT patient_id FROM doctor_reviews WHERE doctor_id = doctor_rec.id
        );
        
        -- If no available patients, skip this doctor
        IF available_patients IS NULL OR array_length(available_patients, 1) = 0 THEN
            RAISE NOTICE 'No available patients for doctor ID %. Skipping.', doctor_rec.id;
            CONTINUE;
        END IF;
        
        -- Shuffle the available patients array
        FOR i IN 1..array_length(available_patients, 1) LOOP
            patient_index := 1 + (RANDOM() * (array_length(available_patients, 1) - 1))::INTEGER;
            -- Simple swap to shuffle
            available_patients[i] := available_patients[patient_index];
        END LOOP;
        
        -- Create up to 10 reviews (or as many as we have available patients)
        FOR i IN 1..LEAST(10, array_length(available_patients, 1)) LOOP
            -- Select random comment and rating
            comment_text := review_comments[1 + (RANDOM() * (array_length(review_comments, 1) - 1))::INTEGER];
            rating_val := ratings[1 + (RANDOM() * (array_length(ratings, 1) - 1))::INTEGER];
            days_offset := (RANDOM() * 180)::INTEGER; -- Random date within last 6 months
            
            -- Insert review using existing patient
            INSERT INTO doctor_reviews (doctor_id, patient_id, rating, comment, created_at, updated_at)
            VALUES (
                doctor_rec.id,
                available_patients[i],
                rating_val,
                comment_text,
                CURRENT_TIMESTAMP - (days_offset || ' days')::INTERVAL,
                CURRENT_TIMESTAMP - (days_offset || ' days')::INTERVAL
            )
            ON CONFLICT (doctor_id, patient_id) DO NOTHING;
            
            review_count := review_count + 1;
        END LOOP;
        
        RAISE NOTICE 'Created % reviews for doctor ID %', review_count, doctor_rec.id;
    END LOOP;
    
    RAISE NOTICE 'Review seeding completed. Used only existing patients from database.';
END $$;
