-- Seed doctor reviews: 10 reviews (4-5 stars) for each doctor
-- This migration is a no-op since reviews were already seeded manually via seed_reviews_automated.sql
-- It checks if reviews exist and skips seeding if they do

DO $$
DECLARE
    existing_reviews_count INTEGER;
BEGIN
    -- Check if reviews already exist
    SELECT COUNT(*) INTO existing_reviews_count FROM doctor_reviews;
    
    -- If reviews already exist, skip seeding
    IF existing_reviews_count > 0 THEN
        RAISE NOTICE 'Reviews already exist in database (count: %). Skipping review seeding.', existing_reviews_count;
        RETURN;
    END IF;
    
    -- If no reviews exist, this migration will be skipped since reviews were seeded manually
    -- This is just a safety check to prevent conflicts
    RAISE NOTICE 'No reviews found. If you need to seed reviews, use seed_reviews_automated.sql manually.';
END $$;
