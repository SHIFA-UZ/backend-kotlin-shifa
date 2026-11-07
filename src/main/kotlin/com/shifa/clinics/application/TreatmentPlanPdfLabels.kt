package com.shifa.clinics.application

import com.shifa.shared.pdf.PdfLanguage

/**
 * The plan PDF's words in one language: the doctor app's strings (`docs/kmp-spec/strings/{en,uz,ru}.json`, the keys
 * the app passes into its `TreatmentPlanPdfLabels`). Templates use `{id}`, `{date}`, `{fdi}`, `{status}`, `{total}`,
 * `{page}`, `{pages}`.
 */
class TreatmentPlanPdfLabels private constructor(
    private val words: Map<String, String>,
) {
    private fun w(key: String) = words.getValue(key)

    val title get() = w("tpPdfTitle")
    val summary get() = w("tpPdfSummary")
    val patient get() = w("patient")
    val patientId get() = w("patientId")
    val planTitle get() = w("treatmentPlanTitle")
    val diagnosis get() = w("diagnosis")
    val created get() = w("tpPdfCreated")
    val updated get() = w("tpPdfUpdated")
    val doctors get() = w("tpPdfDoctors")
    val status get() = w("tpPdfStatus")
    val total get() = w("clinicTreatmentPlansTotal")
    val paid get() = w("clinicTreatmentPlansPaid")
    val outstanding get() = w("clinicFinanceOutstanding")
    val symptoms get() = w("tpPdfSymptoms")
    val notes get() = w("tpPdfNotes")
    val procedures get() = w("tpPdfProcedures")
    val otherServices get() = w("tpPdfOtherServices")
    val service get() = w("wizardService")
    val quantity get() = w("tpPdfQty")
    val unitPrice get() = w("tpPdfUnitPrice")
    val discount get() = w("tpPdfDiscount")
    val lineTotal get() = w("tpPdfLineTotal")
    val lineStatus get() = w("tpPdfLineStatus")
    val installments get() = w("tpPdfInstallments")
    val dueDate get() = w("tpPdfDueDate")
    val amountDue get() = w("tpPdfAmountDue")
    val partStatus get() = w("tpPdfPartStatus")
    val acknowledgement get() = w("tpPdfAcknowledgement")
    val acknowledgementNote get() = w("tpPdfAcknowledgementNote")
    val patientSignature get() = w("tpPdfPatientSignature")
    val date get() = w("tpPdfDate")
    val footer get() = w("tpPdfFooter")

    fun planId(id: Long) = w("tpPdfPlanId").replace("{id}", "$id")

    fun generated(date: String) = w("tpPdfGenerated").replace("{date}", date)

    fun tooth(fdi: String) = w("tpPdfTooth").replace("{fdi}", fdi)

    fun schedule(
        id: Long,
        status: String,
        total: String,
    ) = w("tpPdfSchedule").replace("{id}", "$id").replace("{status}", status).replace("{total}", total)

    fun page(
        page: Int,
        pages: Int,
    ) = w("tpPdfPage").replace("{page}", "$page").replace("{pages}", "$pages")

    /** A plan status (TreatmentPlan.Status name); an unknown one reads as a draft (the app's rule). */
    fun planStatus(status: String): String =
        when (status.uppercase()) {
            "ACTIVE" -> w("active")
            "IN_PROGRESS" -> w("statusInProgress")
            "ON_HOLD" -> w("planStatusOnHold")
            "COMPLETED" -> w("appointmentStatusCompleted")
            "CANCELLED" -> w("appointmentStatusCancelled")
            else -> w("draft")
        }

    /** A line status (TreatmentPlanLine.LineStatus name); anything else reads as planned. */
    fun lineStatusOf(status: String): String =
        when (status.uppercase()) {
            "SCHEDULED" -> w("clinicTreatmentPlanLineStatusScheduled")
            "IN_PROGRESS" -> w("statusInProgress")
            "COMPLETED" -> w("appointmentStatusCompleted")
            "CANCELLED" -> w("appointmentStatusCancelled")
            else -> w("clinicTreatmentPlanLineStatusPlanned")
        }

    /** An installment's status; anything else reads as pending. */
    fun installmentStatus(status: String): String =
        when (status.uppercase()) {
            "PAID" -> w("clinicFinanceInstallStatusPaid")
            "OVERDUE" -> w("clinicFinanceInstallStatusOverdue")
            "WAIVED" -> w("clinicFinanceInstallStatusWaived")
            "CANCELLED" -> w("clinicFinanceInstallStatusCancelled")
            else -> w("clinicFinanceInstallStatusPending")
        }

    /** A schedule's own status in the plan's words when it is one of them (ACTIVE, COMPLETED, CANCELLED…), else as sent. */
    fun scheduleStatus(raw: String): String =
        if (raw.uppercase() in PLAN_STATUSES) planStatus(raw) else raw

    companion object {
        private val PLAN_STATUSES = setOf("DRAFT", "ACTIVE", "IN_PROGRESS", "ON_HOLD", "COMPLETED", "CANCELLED")

        /** The labels in [language] (`en`, `uz`, `ru`; English for anything else). */
        fun of(language: String?): TreatmentPlanPdfLabels =
            TreatmentPlanPdfLabels(
                when (PdfLanguage.of(language)) {
                    "uz" -> UZ
                    "ru" -> RU
                    else -> EN
                },
            )

        private val EN = mapOf(
            "tpPdfTitle" to "Treatment Plan",
            "tpPdfPlanId" to "Plan ID: #{id}",
            "tpPdfGenerated" to "Generated: {date}",
            "tpPdfSummary" to "Plan summary",
            "patient" to "Patient",
            "patientId" to "Patient ID",
            "treatmentPlanTitle" to "Title",
            "diagnosis" to "Diagnosis",
            "tpPdfCreated" to "Created",
            "tpPdfUpdated" to "Updated",
            "tpPdfDoctors" to "Attending doctor(s)",
            "tpPdfStatus" to "Plan status",
            "clinicTreatmentPlansTotal" to "Total",
            "clinicTreatmentPlansPaid" to "Paid",
            "clinicFinanceOutstanding" to "Outstanding",
            "tpPdfSymptoms" to "Symptoms / complaints",
            "tpPdfNotes" to "Plan notes",
            "tpPdfProcedures" to "Procedures & line items",
            "tpPdfTooth" to "Tooth {fdi} (FDI)",
            "tpPdfOtherServices" to "Other services",
            "wizardService" to "Service",
            "tpPdfQty" to "Qty",
            "tpPdfUnitPrice" to "Unit price",
            "tpPdfDiscount" to "Discount",
            "tpPdfLineTotal" to "Line total",
            "tpPdfLineStatus" to "Status",
            "tpPdfInstallments" to "Installment schedules",
            "tpPdfSchedule" to "Schedule #{id} · {status} · {total}",
            "tpPdfDueDate" to "Due date",
            "tpPdfAmountDue" to "Amount due",
            "tpPdfPartStatus" to "Part status",
            "tpPdfAcknowledgement" to "Patient acknowledgement & signature",
            "tpPdfAcknowledgementNote" to
                "By signing below, I confirm that I have reviewed this treatment plan and the payment/installment arrangement shown above.",
            "tpPdfPatientSignature" to "Patient signature",
            "tpPdfDate" to "Date",
            "tpPdfFooter" to "Generated by SHIFA · Confidential medical / billing summary",
            "tpPdfPage" to "Page {page} / {pages}",
            "draft" to "Draft",
            "active" to "Active",
            "statusInProgress" to "In progress",
            "planStatusOnHold" to "On hold",
            "appointmentStatusCompleted" to "Completed",
            "appointmentStatusCancelled" to "Cancelled",
            "clinicTreatmentPlanLineStatusScheduled" to "Scheduled",
            "clinicTreatmentPlanLineStatusPlanned" to "Planned",
            "clinicFinanceInstallStatusPaid" to "Paid",
            "clinicFinanceInstallStatusOverdue" to "Overdue",
            "clinicFinanceInstallStatusWaived" to "Waived",
            "clinicFinanceInstallStatusCancelled" to "Cancelled",
            "clinicFinanceInstallStatusPending" to "Pending",
        )

        private val UZ = mapOf(
            "tpPdfTitle" to "Davolash rejasi",
            "tpPdfPlanId" to "Reja ID: #{id}",
            "tpPdfGenerated" to "Chiqarilgan: {date}",
            "tpPdfSummary" to "Reja qisqacha",
            "patient" to "Bemor",
            "patientId" to "Bemorning shaxsiy raqami",
            "treatmentPlanTitle" to "Sarlavha",
            "diagnosis" to "Tashxis",
            "tpPdfCreated" to "Yaratilgan",
            "tpPdfUpdated" to "Yangilangan",
            "tpPdfDoctors" to "Mas'ul shifokor(lar)",
            "tpPdfStatus" to "Reja holati",
            "clinicTreatmentPlansTotal" to "Jami",
            "clinicTreatmentPlansPaid" to "To'langan",
            "clinicFinanceOutstanding" to "Qoldiq",
            "tpPdfSymptoms" to "Alomatlar / shikoyatlar",
            "tpPdfNotes" to "Reja izohlari",
            "tpPdfProcedures" to "Tadbirlar va qatorlar",
            "tpPdfTooth" to "Tish {fdi} (FDI)",
            "tpPdfOtherServices" to "Boshqa xizmatlar",
            "wizardService" to "Xizmat",
            "tpPdfQty" to "Miqdor",
            "tpPdfUnitPrice" to "Bir narxi",
            "tpPdfDiscount" to "Chegirma",
            "tpPdfLineTotal" to "Yig'indi",
            "tpPdfLineStatus" to "Holat",
            "tpPdfInstallments" to "Bo'lib-bo'lib to'lov jadvallari",
            "tpPdfSchedule" to "Jadval #{id} · {status} · {total}",
            "tpPdfDueDate" to "Muddati",
            "tpPdfAmountDue" to "Summasi",
            "tpPdfPartStatus" to "Bo'lak holati",
            "tpPdfAcknowledgement" to "Bemor tasdiqi va imzo",
            "tpPdfAcknowledgementNote" to
                "Pastda imzo chekish orqali ushbu davolash rejasini va yuqorida ko'rsatilgan to'lov / bo'lib-bo'lib to'lash " +
                "jadvalini tushunganligim va qabul qilganligimni tasdiqlayman.",
            "tpPdfPatientSignature" to "Bemorning imzosi",
            "tpPdfDate" to "Sana",
            "tpPdfFooter" to "SHIFA tomonidan yaratilgan · Maxfiy tibbiy / hisob-kitob hujjati",
            "tpPdfPage" to "Sahifa {page} / {pages}",
            "draft" to "Qoralama",
            "active" to "Faol",
            "statusInProgress" to "Jarayonda",
            "planStatusOnHold" to "To'xtatilgan",
            "appointmentStatusCompleted" to "Yakunlangan",
            "appointmentStatusCancelled" to "Bekor qilingan",
            "clinicTreatmentPlanLineStatusScheduled" to "Belgilangan",
            "clinicTreatmentPlanLineStatusPlanned" to "Rejalashtirilgan",
            "clinicFinanceInstallStatusPaid" to "To'langan",
            "clinicFinanceInstallStatusOverdue" to "Muddati o'tgan",
            "clinicFinanceInstallStatusWaived" to "Kechirilgan",
            "clinicFinanceInstallStatusCancelled" to "Bekor qilingan",
            "clinicFinanceInstallStatusPending" to "Kutilmoqda",
        )

        private val RU = mapOf(
            "tpPdfTitle" to "План лечения",
            "tpPdfPlanId" to "ID плана: #{id}",
            "tpPdfGenerated" to "Создано: {date}",
            "tpPdfSummary" to "Сводка плана",
            "patient" to "Пациент",
            "patientId" to "ID пациента",
            "treatmentPlanTitle" to "Название",
            "diagnosis" to "Диагноз",
            "tpPdfCreated" to "Создан",
            "tpPdfUpdated" to "Обновлён",
            "tpPdfDoctors" to "Лечащие врачи",
            "tpPdfStatus" to "Статус плана",
            "clinicTreatmentPlansTotal" to "Всего",
            "clinicTreatmentPlansPaid" to "Оплачено",
            "clinicFinanceOutstanding" to "Задолженность",
            "tpPdfSymptoms" to "Симптомы / жалобы",
            "tpPdfNotes" to "Заметки к плану",
            "tpPdfProcedures" to "Процедуры и позиции",
            "tpPdfTooth" to "Зуб {fdi} (FDI)",
            "tpPdfOtherServices" to "Другие услуги",
            "wizardService" to "Услуга",
            "tpPdfQty" to "Кол-во",
            "tpPdfUnitPrice" to "Цена",
            "tpPdfDiscount" to "Скидка",
            "tpPdfLineTotal" to "Сумма",
            "tpPdfLineStatus" to "Статус",
            "tpPdfInstallments" to "Графики рассрочки",
            "tpPdfSchedule" to "График #{id} · {status} · {total}",
            "tpPdfDueDate" to "Срок",
            "tpPdfAmountDue" to "К оплате",
            "tpPdfPartStatus" to "Статус части",
            "tpPdfAcknowledgement" to "Подтверждение и подпись пациента",
            "tpPdfAcknowledgementNote" to
                "Подписывая ниже, я подтверждаю, что ознакомился с этим планом лечения и указанным выше порядком оплаты / рассрочки.",
            "tpPdfPatientSignature" to "Подпись пациента",
            "tpPdfDate" to "Дата",
            "tpPdfFooter" to "Создано в SHIFA · Конфиденциальная медицинская / финансовая сводка",
            "tpPdfPage" to "Стр. {page} / {pages}",
            "draft" to "Черновик",
            "active" to "Активно",
            "statusInProgress" to "Идёт приём",
            "planStatusOnHold" to "Приостановлен",
            "appointmentStatusCompleted" to "Завершён",
            "appointmentStatusCancelled" to "Отменён",
            "clinicTreatmentPlanLineStatusScheduled" to "Назначено",
            "clinicTreatmentPlanLineStatusPlanned" to "Запланировано",
            "clinicFinanceInstallStatusPaid" to "Оплачено",
            "clinicFinanceInstallStatusOverdue" to "Просрочено",
            "clinicFinanceInstallStatusWaived" to "Списано",
            "clinicFinanceInstallStatusCancelled" to "Отменено",
            "clinicFinanceInstallStatusPending" to "Ожидает",
        )
    }
}
