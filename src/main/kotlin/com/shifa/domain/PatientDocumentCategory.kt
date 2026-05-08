package com.shifa.domain

/**
 * Category / tag chosen by the uploader for a patient document.
 *
 * Categories drive document visibility:
 *   * [isMedicalResult] = true  -> document is shared with every doctor of the
 *     patient (visibility = team).
 *   * [isMedicalResult] = false -> document is doctor-private and only the
 *     creator (or doctors granted access) can view it.
 *
 * Patient-uploaded documents are always shared with the team regardless of
 * the chosen category (visibility is enforced separately in the service).
 *
 * NOTE: The wire format used between backend and clients is the enum [code].
 * Keep these stable; UIs and stored rows reference them.
 */
enum class PatientDocumentCategory(
    val code: String,
    val isMedicalResult: Boolean
) {
    // ---------- Medical result categories (visible to all doctors) ----------
    BLOOD_TEST("BLOOD_TEST", true),
    URINE_TEST("URINE_TEST", true),
    STOOL_TEST("STOOL_TEST", true),
    LAB_RESULT("LAB_RESULT", true),
    MRI("MRI", true),
    CT_SCAN("CT_SCAN", true),
    XRAY("XRAY", true),
    ULTRASOUND("ULTRASOUND", true),
    MAMMOGRAPHY("MAMMOGRAPHY", true),
    ECG("ECG", true),
    EEG("EEG", true),
    ENDOSCOPY("ENDOSCOPY", true),
    BIOPSY("BIOPSY", true),
    PATHOLOGY("PATHOLOGY", true),
    IMAGING_OTHER("IMAGING_OTHER", true),
    PRESCRIPTION("PRESCRIPTION", true),
    VACCINATION_RECORD("VACCINATION_RECORD", true),
    DISCHARGE_SUMMARY("DISCHARGE_SUMMARY", true),
    REFERRAL("REFERRAL", true),
    HOSPITAL_REPORT("HOSPITAL_REPORT", true),
    ALLERGY_REPORT("ALLERGY_REPORT", true),
    OTHER_MEDICAL("OTHER_MEDICAL", true),

    // ---------- Doctor-private categories (creator only + grants) ----------
    APPOINTMENT_NOTE("APPOINTMENT_NOTE", false),
    REMOTE_TASK_DOCUMENT("REMOTE_TASK_DOCUMENT", false),
    FORM_025_2("FORM_025_2", false),
    INTERNAL_NOTE("INTERNAL_NOTE", false),
    OTHER_PRIVATE("OTHER_PRIVATE", false);

    companion object {
        private val byCode = values().associateBy { it.code }

        /**
         * Resolve a category from its wire/storage code. Returns null for
         * unknown / blank values so callers can apply their own default.
         */
        fun fromCodeOrNull(code: String?): PatientDocumentCategory? {
            val trimmed = code?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            return byCode[trimmed.uppercase()]
        }
    }
}
