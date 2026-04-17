-- Create remote_care_tasks table
CREATE TABLE remote_care_tasks (
    id BIGSERIAL PRIMARY KEY,
    doctor_id BIGINT NOT NULL REFERENCES doctor_profiles(id),
    patient_id BIGINT NOT NULL REFERENCES patient_profiles(id),
    task_name VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    times_per_day INTEGER NOT NULL DEFAULT 1,
    morning_time TIME,
    afternoon_time TIME,
    evening_time TIME,
    start_date DATE NOT NULL,
    end_date DATE,
    duration_days INTEGER,
    input_type VARCHAR(50) NOT NULL,
    input_label VARCHAR(255),
    notes_required BOOLEAN DEFAULT FALSE,
    notes_label VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE
);

-- Create task_check_ins table
CREATE TABLE task_check_ins (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES remote_care_tasks(id) ON DELETE CASCADE,
    scheduled_date DATE NOT NULL,
    scheduled_time TIME,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    numeric_value DOUBLE PRECISION,
    text_value TEXT,
    boolean_value BOOLEAN,
    notes TEXT,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes
CREATE INDEX idx_remote_care_tasks_doctor_id ON remote_care_tasks(doctor_id);
CREATE INDEX idx_remote_care_tasks_patient_id ON remote_care_tasks(patient_id);
CREATE INDEX idx_remote_care_tasks_status ON remote_care_tasks(status);
CREATE INDEX idx_task_check_ins_task_id ON task_check_ins(task_id);
CREATE INDEX idx_task_check_ins_scheduled_date ON task_check_ins(scheduled_date);
CREATE INDEX idx_task_check_ins_status ON task_check_ins(status);
