package com.shifa.intelligence.appointment

import com.shifa.domain.DoctorProfile
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Service
class AppointmentIntelligenceService(
    private val clock: Clock,
    private val ranges: ClinicTimeRangeResolver,
    private val patients: AppointmentIntelligencePatients,
    private val readModel: AppointmentIntelligenceReadModel,
) {
    private val parser = AppointmentIntelligenceQueryParser()
    private val merger = AppointmentQueryContextMerger(ranges)
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val spokenDayFmt = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)

    @Transactional(readOnly = true)
    fun execute(
        doctor: DoctorProfile,
        args: Map<String, Any?>,
        priorAssistantMessages: List<String>,
        latestUserText: String,
        focusPatientId: Long?,
        searchedPatientIds: Set<Long> = emptySet(),
        resolvedPatientId: Long? = null,
    ): Map<String, Any?> {
        try {
            return run(
                doctor, args, priorAssistantMessages, latestUserText,
                focusPatientId, searchedPatientIds, resolvedPatientId,
            )
        } catch (ex: TimeRangeParseException) {
            return unsupported(ex.message ?: AppointmentIntelligenceLimits.PERIOD_NEEDED)
        } catch (ex: Exception) {
            log.warn("appointment intelligence query failed doctor={}", doctor.id, ex)
            return unsupported(AppointmentIntelligenceLimits.COULD_NOT_RETRIEVE, "QUERY_FAILED")
        }
    }

    private fun run(
        doctor: DoctorProfile,
        args: Map<String, Any?>,
        priorAssistantMessages: List<String>,
        latestUserText: String,
        focusPatientId: Long?,
        searchedPatientIds: Set<Long>,
        resolvedPatientId: Long?,
    ): Map<String, Any?> {
        when (val parsed = parser.parse(args)) {
            is ParseOutcome.Unsupported -> return unsupported(parsed.reason)
            is ParseOutcome.Ok -> {
                val zone = ranges.zoneOf(doctor.timeZone)
                val previous = AppointmentQueryContextCodec.latest(priorAssistantMessages)
                when (
                    val merged = merger.merge(doctor.id, zone, parsed.request, previous, latestUserText)
                ) {
                    is MergeResult.Unsupported -> return unsupported(merged.reason)
                    is MergeResult.Ready -> {
                        val request = merged.request
                        val patient = resolvePatient(
                            doctor.id, request, merged.continued, latestUserText,
                            focusPatientId, searchedPatientIds, resolvedPatientId,
                        )
                        when (patient) {
                            is PatientPick.Missing -> return unsupported(AppointmentIntelligenceLimits.PATIENT_MISSING)
                            is PatientPick.Ambiguous -> return ambiguous(patient.matches)
                            is PatientPick.Chosen -> return answer(doctor.id, zone, request, patient)
                        }
                    }
                }
            }
        }
    }

    private fun answer(
        doctorId: Long,
        zone: ZoneId,
        request: IntelligenceRequest,
        patient: PatientPick.Chosen,
    ): Map<String, Any?> {
        val now = clock.instant()
        val range = resolveRange(request, zone, now) ?: return unsupported(AppointmentIntelligenceLimits.PERIOD_NEEDED)
        val comparison = request.comparison?.let { ranges.resolve(it, zone) }
        val statuses = effectiveStatuses(request) ?: return unsupported(AppointmentIntelligenceLimits.UPCOMING_CONFLICT)
        if (request.groupBy != null &&
            request.metric != Metric.COUNT &&
            request.metric != Metric.GROUPED_COUNT &&
            request.metric != Metric.DISTINCT_PATIENT_COUNT
        ) {
            return unsupported("I can group appointment counts. I can't group that total.")
        }
        if (request.metric == Metric.SUM_COLLECTED && hasAppointmentSlice(request, patient.patientId)) {
            return unsupported(AppointmentIntelligenceLimits.COLLECTED_FILTERS)
        }
        if (request.metric == Metric.CANCELLATION_RATE && (statuses.isNotEmpty() || request.upcoming)) {
            return unsupported(AppointmentIntelligenceLimits.RATE_FILTERS)
        }
        if (request.metric == Metric.SUM_OUTSTANDING &&
            request.paymentStatus != null &&
            request.paymentStatus != PaymentSnapshot.PENDING
        ) {
            return unsupported("Outstanding amounts are visits marked pending. That conflicts with the payment filter.")
        }
        val filter = AppointmentFilter(
            startInclusive = range.startInclusive,
            endExclusive = range.endExclusive,
            zoneId = zone.id,
            statuses = statuses,
            upcomingOnly = request.upcoming,
            now = now,
            patientId = patient.patientId,
            documentation = request.documentation,
            paymentStatus = if (request.metric == Metric.SUM_OUTSTANDING) PaymentSnapshot.PENDING else request.paymentStatus,
            modality = request.modality,
        )
        val anchor = if (request.metric == Metric.SUM_COLLECTED) {
            TimeAnchor.PAYMENT_COLLECTED_AT
        } else {
            TimeAnchor.APPOINTMENT_START
        }
        val body = when (request.metric) {
            Metric.SUM_COLLECTED -> collectedBody(doctorId, range, comparison, zone)
            Metric.SUM_APPOINTMENT_VALUE -> moneyBody(
                readModel.appointmentValue(doctorId, filter),
                range,
                AppointmentIntelligenceLimits.APPOINTMENT_VALUE,
                "Appointment prices scheduled ${range.label}",
            )
            Metric.SUM_OUTSTANDING -> moneyBody(
                readModel.pendingAppointmentValue(doctorId, filter),
                range,
                AppointmentIntelligenceLimits.OUTSTANDING,
                "Pending amounts scheduled ${range.label}",
            )
            Metric.CANCELLATION_RATE -> rateBody(doctorId, filter, range, comparison, zone)
            Metric.DISTINCT_PATIENT_COUNT -> countBody(
                doctorId, filter, range, comparison, zone, request, patient, distinct = true,
            )
            else -> when (request.shape) {
                Shape.LIST -> listBody(doctorId, filter, range, request, patient, zone)
                Shape.GROUPED -> groupedBody(doctorId, filter, range, comparison, zone, request, patient)
                Shape.AGGREGATE -> countBody(
                    doctorId, filter, range, comparison, zone, request, patient, distinct = false,
                )
            }
        }
        val contextRequest = request.copy(statuses = statuses, patientId = patient.patientId)
        return body + mapOf(
            "supported" to true,
            "metric" to request.metric.name,
            "timeAnchor" to anchor.name,
            "timeAnchorNote" to if (anchor == TimeAnchor.PAYMENT_COLLECTED_AT) {
                AppointmentIntelligenceLimits.COLLECTED
            } else {
                AppointmentIntelligenceLimits.SCHEDULED
            },
            "definition" to definition(request.metric),
            "range" to rangeMap(range),
            "filters" to filterMap(contextRequest, patient),
            "queryContext" to AppointmentQueryContextCodec.encode(doctorId, contextRequest, range, patient.patientId),
        )
    }

    private fun countBody(
        doctorId: Long,
        filter: AppointmentFilter,
        range: ResolvedTimeRange,
        comparison: ResolvedTimeRange?,
        zone: ZoneId,
        request: IntelligenceRequest,
        patient: PatientPick.Chosen,
        distinct: Boolean,
    ): Map<String, Any?> {
        val count = if (distinct) {
            readModel.countDistinctPatients(doctorId, filter)
        } else {
            readModel.count(doctorId, filter)
        }
        val other = comparison?.let { otherRange ->
            val otherFilter = filter.copy(
                startInclusive = otherRange.startInclusive,
                endExclusive = otherRange.endExclusive,
            )
            if (distinct) readModel.countDistinctPatients(doctorId, otherFilter) else readModel.count(doctorId, otherFilter)
        }
        val hint = countHint(count, range, request.statuses, patient.name, distinct, other, comparison)
        return mapOf(
            "cardType" to "appointment_intelligence",
            "count" to count,
            "spokenHint" to hint,
            "comparison" to comparisonPayload(comparison, other),
        )
    }

    private fun groupedBody(
        doctorId: Long,
        filter: AppointmentFilter,
        range: ResolvedTimeRange,
        comparison: ResolvedTimeRange?,
        zone: ZoneId,
        request: IntelligenceRequest,
        patient: PatientPick.Chosen,
    ): Map<String, Any?> {
        val dimension = request.groupBy ?: GroupDimension.STATUS
        val groups = readModel.group(doctorId, filter, dimension)
        val otherGroups = comparison?.let {
            readModel.group(
                doctorId,
                filter.copy(startInclusive = it.startInclusive, endExclusive = it.endExclusive),
                dimension,
            )
        }
        val hint = if (request.presentation == Presentation.STATUS_BREAKDOWN || dimension == GroupDimension.STATUS) {
            statusHint(groups, range, patient.name)
        } else {
            groupHint(groups, range, dimension)
        }
        return mapOf(
            "cardType" to "appointment_intelligence",
            "count" to groups.sumOf { it.count },
            "groupBy" to dimension.name,
            "groups" to groups.map { mapOf("key" to it.key, "count" to it.count) },
            "comparisonGroups" to otherGroups?.map { mapOf("key" to it.key, "count" to it.count) },
            "spokenHint" to hint,
        )
    }

    private fun listBody(
        doctorId: Long,
        filter: AppointmentFilter,
        range: ResolvedTimeRange,
        request: IntelligenceRequest,
        patient: PatientPick.Chosen,
        zone: ZoneId,
    ): Map<String, Any?> {
        val total = readModel.count(doctorId, filter)
        val limit = request.limit ?: DEFAULT_LIST
        val rows = readModel.list(doctorId, filter, request.sortDescending, limit)
        val appointments = rows.map { row ->
            val local = row.startAt.atZone(zone)
            mapOf(
                "appointmentId" to row.appointmentId,
                "patientId" to row.patientId,
                "patientName" to row.patientName,
                "date" to local.toLocalDate().toString(),
                "dateLabel" to local.format(dateFmt),
                "dayOfWeek" to local.dayOfWeek.name,
                "startLocal" to local.format(timeFmt),
                "startAt" to row.startAt.toString(),
                "status" to row.status,
                "isVideo" to row.location.contains("video", ignoreCase = true),
                "locationLabel" to row.location.takeIf { it.isNotBlank() },
                "paymentStatus" to row.paymentStatus,
            )
        }
        val hint = listHint(total, rows, range, zone, patient.name, request.statuses)
        return mapOf(
            "cardType" to "patient_appointments",
            "patientId" to patient.patientId,
            "patientName" to if (patient.patientId != null) patient.name else null,
            "rangeLabel" to range.label,
            "count" to total,
            "shown" to rows.size,
            "appointments" to appointments,
            "spokenHint" to hint,
        )
    }

    private fun rateBody(
        doctorId: Long,
        filter: AppointmentFilter,
        range: ResolvedTimeRange,
        comparison: ResolvedTimeRange?,
        zone: ZoneId,
    ): Map<String, Any?> {
        val past = filter.copy(excludeFuture = true, statuses = emptySet(), upcomingOnly = false)
        val denominator = readModel.count(doctorId, past)
        val numerator = readModel.count(doctorId, past.copy(statuses = setOf("CANCELLED")))
        val other = comparison?.let {
            val otherPast = past.copy(startInclusive = it.startInclusive, endExclusive = it.endExclusive)
            val denom = readModel.count(doctorId, otherPast)
            val numer = readModel.count(doctorId, otherPast.copy(statuses = setOf("CANCELLED")))
            RateFigures(numer, denom, it.label)
        }
        return mapOf(
            "cardType" to "appointment_intelligence",
            "numerator" to numerator,
            "denominator" to denominator,
            "ratePercent" to percent(numerator, denominator),
            "spokenHint" to rateHint(numerator, denominator, range.label, other),
            "comparison" to other?.let {
                mapOf(
                    "label" to it.label,
                    "numerator" to it.numerator,
                    "denominator" to it.denominator,
                    "ratePercent" to percent(it.numerator, it.denominator),
                )
            },
        )
    }

    private fun collectedBody(
        doctorId: Long,
        range: ResolvedTimeRange,
        comparison: ResolvedTimeRange?,
        zone: ZoneId,
    ): Map<String, Any?> {
        val rows = mergeCollected(
            readModel.consultationCollected(doctorId, range.startInclusive, range.endExclusive),
            readModel.ledgerCollected(doctorId, range.startInclusive, range.endExclusive),
        )
        val otherRows = comparison?.let {
            mergeCollected(
                readModel.consultationCollected(doctorId, it.startInclusive, it.endExclusive),
                readModel.ledgerCollected(doctorId, it.startInclusive, it.endExclusive),
            )
        }
        return mapOf(
            "cardType" to "appointment_intelligence",
            "collected" to rows,
            "comparisonCollected" to otherRows,
            "spokenHint" to collectedHint(rows, range.label),
        )
    }

    private fun moneyBody(
        rows: List<MoneyByCurrency>,
        range: ResolvedTimeRange,
        definition: String,
        lead: String,
    ): Map<String, Any?> = mapOf(
        "cardType" to "appointment_intelligence",
        "amounts" to rows.map { mapOf("currency" to it.currency, "minor" to it.minor, "count" to it.count) },
        "spokenHint" to if (rows.isEmpty()) {
            "$lead: nothing with a stored price. $definition"
        } else {
            val parts = rows.joinToString(", ") { "${it.minor} ${it.currency} across ${it.count} visits" }
            "$lead: $parts. $definition"
        },
    )

    private fun resolveRange(request: IntelligenceRequest, zone: ZoneId, now: Instant): ResolvedTimeRange? {
        request.time?.let { return ranges.resolve(it, zone) }
        if (request.upcoming) {
            val end = now.atZone(zone).plusYears(1).toInstant()
            return ResolvedTimeRange(now, end, "the upcoming year", TimeRangeKind.ROLLING, capped = true)
        }
        return null
    }

    private fun effectiveStatuses(request: IntelligenceRequest): Set<String>? {
        if (!request.upcoming) {
            if (request.statuses.isNotEmpty()) return request.statuses
            // "This week's appointments" is the live book, not cancelled/no-show visits.
            if (request.shape == Shape.LIST) {
                return BOOKED_LIST_STATUSES
            }
            return request.statuses
        }
        val allowed = setOf("REQUESTED", "CONFIRMED", "IN_PROGRESS")
        val selected = if (request.statuses.isEmpty()) allowed else request.statuses.intersect(allowed)
        return selected.takeIf { it.isNotEmpty() }
    }

    private fun hasAppointmentSlice(request: IntelligenceRequest, patientId: Long?): Boolean =
        patientId != null ||
            request.statuses.isNotEmpty() ||
            request.upcoming ||
            request.documentation != null ||
            request.paymentStatus != null ||
            request.modality != null

    private fun resolvePatient(
        doctorId: Long,
        request: IntelligenceRequest,
        continued: Boolean,
        latestUserText: String,
        focusPatientId: Long?,
        searchedPatientIds: Set<Long>,
        resolvedPatientId: Long?,
    ): PatientPick {
        if (resolvedPatientId != null) return authorized(doctorId, resolvedPatientId, request.patientName)
        val name = request.patientName?.trim()?.takeIf { it.isNotEmpty() }
        val anaphora = Regex("""\b(this patient|him|her|them|their|his|hers)\b""")
            .containsMatchIn(normalize(latestUserText))
        if (searchedPatientIds.size == 1 && (name != null || anaphora)) {
            return authorized(doctorId, searchedPatientIds.first(), name)
        }
        if (name != null) {
            val matches = patients.findByName(doctorId, name)
            return when (matches.size) {
                0 -> PatientPick.Missing
                1 -> authorized(doctorId, matches.first().id, matches.first().name)
                else -> PatientPick.Ambiguous(matches)
            }
        }
        val inherited = when {
            anaphora && focusPatientId != null -> focusPatientId
            continued -> request.patientId
            else -> null
        }
        if (inherited == null) return PatientPick.Chosen(null, null)
        return authorized(doctorId, inherited, null)
    }

    private fun authorized(doctorId: Long, patientId: Long, fallbackName: String?): PatientPick {
        if (!patients.mayAccess(doctorId, patientId)) return PatientPick.Missing
        val name = patients.nameOf(patientId) ?: fallbackName ?: "this patient"
        return PatientPick.Chosen(patientId, name)
    }

    private fun ambiguous(matches: List<PatientMatch>): Map<String, Any?> = mapOf(
        "supported" to false,
        "reason" to "AMBIGUOUS_PATIENT",
        "cardType" to "patient_search",
        "fuzzy" to false,
        "matches" to matches.map {
            mapOf("patientId" to it.id, "patientName" to it.name, "phone" to "")
        },
        "spokenHint" to "I found more than one patient. Which one did you mean?",
    )

    private fun countHint(
        count: Long,
        range: ResolvedTimeRange,
        statuses: Set<String>,
        patientName: String?,
        distinct: Boolean,
        other: Long?,
        comparison: ResolvedTimeRange?,
    ): String {
        val who = patientName?.let { " with $it" }.orEmpty()
        val base = when {
            statuses == setOf("CANCELLED") -> {
                val noun = if (count == 1L) "appointment" else "appointments"
                val verb = if (count == 1L) "is" else "are"
                "$count $noun scheduled ${range.label}$who $verb currently cancelled. ${AppointmentIntelligenceLimits.CANCEL_TIME}"
            }
            distinct -> {
                val noun = if (count == 1L) "patient" else "patients"
                "You ${verbFor(range)} $count $noun with appointments scheduled ${range.label}$who."
            }
            else -> {
                val noun = if (count == 1L) "appointment" else "appointments"
                "You ${verbFor(range)} $count $noun scheduled ${range.label}$who."
            }
        }
        if (other == null || comparison == null) return base
        return "$base Compared with ${comparison.label}, that was $other."
    }

    private fun statusHint(groups: List<CountGroup>, range: ResolvedTimeRange, patientName: String?): String {
        val by = groups.associate { it.key to it.count }
        val total = groups.sumOf { it.count }
        val confirmed = by["CONFIRMED"] ?: 0L
        val requested = by["REQUESTED"] ?: 0L
        val who = patientName?.let { " with $it" }.orEmpty()
        val extras = groups.filter {
            it.count > 0 && it.key != "CONFIRMED" && it.key != "REQUESTED"
        }.joinToString(", ") { "${it.count} ${it.key.lowercase()}" }
        val extraSentence = if (extras.isEmpty()) "" else " Also $extras."
        val cancelNote = if ((by["CANCELLED"] ?: 0L) > 0L) " ${AppointmentIntelligenceLimits.CANCEL_TIME}" else ""
        val noun = if (total == 1L) "appointment" else "appointments"
        return "You ${verbFor(range)} $total $noun scheduled ${range.label}$who, of which $confirmed were confirmed and $requested are awaiting confirmation.$extraSentence$cancelNote"
    }

    private fun groupHint(groups: List<CountGroup>, range: ResolvedTimeRange, dimension: GroupDimension): String {
        if (groups.isEmpty()) return "No appointments scheduled ${range.label}."
        val shown = groups.take(8).joinToString(", ") { "${it.key}: ${it.count}" }
        val more = if (groups.size > 8) ", and more" else ""
        return "Scheduled ${range.label} by ${dimension.name.lowercase()}: $shown$more."
    }

    private fun listHint(
        total: Long,
        rows: List<ListedVisit>,
        range: ResolvedTimeRange,
        zone: ZoneId,
        patientName: String?,
        statuses: Set<String>,
    ): String {
        val who = patientName?.let { " with $it" }.orEmpty()
        if (total == 0L) {
            val cancelled = if (statuses == setOf("CANCELLED")) " cancelled" else ""
            return "You ${verbFor(range)} no$cancelled appointments scheduled ${range.label}$who."
        }
        val details = rows.take(4).joinToString("; ") { row ->
            val local = row.startAt.atZone(zone)
            val name = if (patientName == null) "${row.patientName} " else ""
            "$name${local.format(spokenDayFmt)} at ${local.format(timeFmt)}"
        }
        val more = if (total > rows.size) " Showing ${rows.size} of $total." else ""
        val noun = if (total == 1L) "appointment" else "appointments"
        val cancel = if (statuses == setOf("CANCELLED")) " ${AppointmentIntelligenceLimits.CANCEL_TIME}" else ""
        val listed = if (details.isBlank()) "" else ": $details."
        return "You ${verbFor(range)} $total $noun scheduled ${range.label}$who$listed$more$cancel"
    }

    private fun rateHint(numerator: Long, denominator: Long, label: String, other: RateFigures?): String {
        val main = if (denominator == 0L) {
            "There are no past visits scheduled $label, so the cancellation rate is not available."
        } else {
            "Cancellation rate for visits scheduled $label, excluding future visits, is ${percent(numerator, denominator)} percent ($numerator of $denominator). Visits that have not happened yet are left out. ${AppointmentIntelligenceLimits.CANCEL_TIME}"
        }
        if (other == null) return main
        val extra = if (other.denominator == 0L) {
            " For ${other.label} the rate is not available."
        } else {
            " For ${other.label} it was ${percent(other.numerator, other.denominator)} percent (${other.numerator} of ${other.denominator})."
        }
        return main + extra
    }

    private fun collectedHint(rows: List<Map<String, Any?>>, label: String): String {
        if (rows.isEmpty()) {
            return "You collected nothing $label. ${AppointmentIntelligenceLimits.COLLECTED}"
        }
        val parts = rows.joinToString("; ") { row ->
            val total = row["totalMinor"]
            val currency = row["currency"]
            val consult = row["consultationMinor"]
            val ledger = row["ledgerMinor"]
            "$total $currency ($consult from video consultation checkouts and $ledger from clinic ledger payments)"
        }
        return "Collected $label: $parts. ${AppointmentIntelligenceLimits.COLLECTED}"
    }

    private fun mergeCollected(
        consultation: List<MoneyByCurrency>,
        ledger: List<MoneyByCurrency>,
    ): List<Map<String, Any?>> {
        val currencies = (consultation.map { it.currency } + ledger.map { it.currency }).distinct()
        return currencies.map { currency ->
            val consult = consultation.filter { it.currency == currency }
            val desk = ledger.filter { it.currency == currency }
            val consultMinor = consult.sumOf { it.minor }
            val ledgerMinor = desk.sumOf { it.minor }
            mapOf(
                "currency" to currency,
                "consultationMinor" to consultMinor,
                "ledgerMinor" to ledgerMinor,
                "totalMinor" to consultMinor + ledgerMinor,
                "consultationTransactions" to consult.sumOf { it.count },
                "ledgerTransactions" to desk.sumOf { it.count },
            )
        }
    }

    private fun verbFor(range: ResolvedTimeRange): String =
        if (range.endExclusive.isAfter(clock.instant())) "have" else "had"

    private fun percent(numerator: Long, denominator: Long): String? {
        if (denominator == 0L) return null
        val tenths = numerator * 1000 / denominator
        return "${tenths / 10}.${tenths % 10}"
    }

    private fun definition(metric: Metric): String = when (metric) {
        Metric.SUM_COLLECTED -> AppointmentIntelligenceLimits.COLLECTED
        Metric.SUM_APPOINTMENT_VALUE -> AppointmentIntelligenceLimits.APPOINTMENT_VALUE
        Metric.SUM_OUTSTANDING -> AppointmentIntelligenceLimits.OUTSTANDING
        Metric.CANCELLATION_RATE ->
            "Cancellation rate is cancelled visits divided by all visits with a scheduled start in the period and before now. Future visits are excluded. ${AppointmentIntelligenceLimits.CANCEL_TIME}"
        else -> AppointmentIntelligenceLimits.SCHEDULED
    }

    private fun rangeMap(range: ResolvedTimeRange) = mapOf(
        "start" to range.startInclusive.toString(),
        "endExclusive" to range.endExclusive.toString(),
        "label" to range.label,
        "kind" to range.kind.name,
        "capped" to range.capped,
    )

    private fun filterMap(request: IntelligenceRequest, patient: PatientPick.Chosen) = mapOf(
        "statuses" to request.statuses.toList(),
        "upcoming" to request.upcoming,
        "patientId" to patient.patientId,
        "documentation" to request.documentation?.name,
        "paymentStatus" to request.paymentStatus?.name,
        "modality" to request.modality?.name,
        "groupBy" to request.groupBy?.name,
    )

    private fun comparisonPayload(range: ResolvedTimeRange?, count: Long?): Map<String, Any?>? {
        if (range == null || count == null) return null
        return mapOf(
            "label" to range.label,
            "start" to range.startInclusive.toString(),
            "endExclusive" to range.endExclusive.toString(),
            "count" to count,
        )
    }

    private fun unsupported(reason: String, code: String = "UNSUPPORTED") = mapOf(
        "supported" to false,
        "error" to code,
        "spokenHint" to reason,
    )

    private data class RateFigures(val numerator: Long, val denominator: Long, val label: String)

    private sealed class PatientPick {
        data class Chosen(val patientId: Long?, val name: String?) : PatientPick()
        data object Missing : PatientPick()
        data class Ambiguous(val matches: List<PatientMatch>) : PatientPick()
    }

    companion object {
        private const val DEFAULT_LIST = 20
        private val BOOKED_LIST_STATUSES = setOf("REQUESTED", "CONFIRMED", "IN_PROGRESS", "COMPLETED")
    }
}
