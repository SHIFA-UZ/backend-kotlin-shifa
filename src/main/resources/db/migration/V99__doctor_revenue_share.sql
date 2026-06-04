ALTER TABLE clinics
    ADD COLUMN default_doctor_revenue_share_percent SMALLINT NULL;

ALTER TABLE clinic_memberships
    ADD COLUMN doctor_revenue_share_percent SMALLINT NULL;

ALTER TABLE clinics
    ADD CONSTRAINT chk_clinics_default_doctor_revenue_share_percent
        CHECK (default_doctor_revenue_share_percent IS NULL
            OR (default_doctor_revenue_share_percent >= 0
                AND default_doctor_revenue_share_percent <= 100));

ALTER TABLE clinic_memberships
    ADD CONSTRAINT chk_clinic_memberships_doctor_revenue_share_percent
        CHECK (doctor_revenue_share_percent IS NULL
            OR (doctor_revenue_share_percent >= 0
                AND doctor_revenue_share_percent <= 100));
