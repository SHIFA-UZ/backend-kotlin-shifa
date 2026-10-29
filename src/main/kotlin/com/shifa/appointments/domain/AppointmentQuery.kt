package com.shifa.appointments.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Metric {
    COUNT,
    DISTINCT_PATIENT_COUNT,
    GROUPED_COUNT,
    CANCELLATION_RATE,
    SUM_COLLECTED,
    SUM_APPOINTMENT_VALUE,
    SUM_OUTSTANDING,
}

enum class Shape { AGGREGATE, GROUPED, LIST }

enum class Presentation { TOTAL, STATUS_BREAKDOWN }

enum class GroupDimension {
    STATUS,
    LOCAL_DAY,
    LOCAL_WEEK,
    LOCAL_MONTH,
    PAYMENT_STATUS,
    MODALITY,
    PATIENT,
}

enum class DocumentationFilter {
    NO_CONSULTATION_NOTE,
    HAS_CONSULTATION_NOTE,
    PATIENT_SIGNED,
    PATIENT_UNSIGNED,
}

enum class PaymentSnapshot { NOT_REQUIRED, PENDING, PAID, FAILED, REFUNDED }

enum class Modality { VIDEO, IN_PERSON }

enum class TimeAnchor { APPOINTMENT_START, PAYMENT_COLLECTED_AT }

object AppointmentIntelligenceLimits {
    const val RESCHEDULED =
        "Shifa does not store a rescheduled status. A doctor reschedule changes the visit time. A patient reschedule cancels the old visit and books a new one. Those cannot be counted as reschedules."
    const val FINALIZED =
        "Consultation notes do not have a finalized or doctor-signed state. I can count visits with or without a consultation note, and whether the patient has signed."
    const val PARTIAL =
        "Partial payments live on treatment plans, not as an appointment status. I can't count partially paid visits without mixing plan balances and visit prices."
    const val AVERAGE =
        "An average is not defined for this. I can count appointments, or total collected money by currency."
    const val REFUND =
        "Refunds are not posted by the current payment flow, and they are not subtracted from the clinic ledger. I can't report a refund total."
    const val BOOKING_CHANNEL = "Appointments do not store a booking channel."
    const val OTHER_DOCTOR = "I can only report appointments for the doctor who is signed in."
    const val COLLECTED =
        "Collected money is paid video-consultation checkouts plus clinic ledger payments for plans you attend, excluding waived amounts. A gateway treatment-plan payment is copied onto the ledger, so it is counted once. Appointment prices and receipt copies are not added. Currencies stay separate. The period is the payment time, not the visit time."
    const val APPOINTMENT_VALUE =
        "Appointment value is the price stored on the visit. It is not money collected."
    const val OUTSTANDING =
        "Outstanding here is the price on visits marked pending. It does not include treatment-plan balances."
    const val CANCEL_TIME =
        "This uses the visit time, not the time the appointment was cancelled, because that time is not stored."
    const val SCHEDULED =
        "Counts use the scheduled start of the visit. Cancellation time is not stored."
    const val COLLECTED_FILTERS =
        "Collected money uses payment time for your practice. I can't combine that with a patient, status, or documentation filter without mixing different records."
    const val RATE_FILTERS =
        "The cancellation rate already chooses which visits count. Ask for a count if you want one status only."
    const val UPCOMING_CONFLICT =
        "Upcoming visits are requested, confirmed, or in progress."
    const val PERIOD_NEEDED = "Which period should I use?"
    const val COULD_NOT_RETRIEVE = "I could not retrieve that appointment information."
    const val PATIENT_MISSING = "I could not find that patient on your roster."
}

data class IntelligenceRequest(
    val metric: Metric = Metric.COUNT,
    val shape: Shape = Shape.AGGREGATE,
    val presentation: Presentation = Presentation.TOTAL,
    val time: TimeSpec? = null,
    val comparison: TimeSpec? = null,
    val statuses: Set<String> = emptySet(),
    val upcoming: Boolean = false,
    val patientId: Long? = null,
    val patientName: String? = null,
    val documentation: DocumentationFilter? = null,
    val paymentStatus: PaymentSnapshot? = null,
    val modality: Modality? = null,
    val groupBy: GroupDimension? = null,
    val sortDescending: Boolean = false,
    val limit: Int? = null,
    val limitCapped: Boolean = false,
)

data class AppointmentFilter(
    val startInclusive: Instant,
    val endExclusive: Instant,
    val zoneId: String,
    val statuses: Set<String> = emptySet(),
    val upcomingOnly: Boolean = false,
    val excludeFuture: Boolean = false,
    val now: Instant,
    val patientId: Long? = null,
    val documentation: DocumentationFilter? = null,
    val paymentStatus: PaymentSnapshot? = null,
    val modality: Modality? = null,
)

sealed class ParseOutcome {
    data class Ok(val request: IntelligenceRequest) : ParseOutcome()
    data class Unsupported(val reason: String) : ParseOutcome()
}

class AppointmentIntelligenceQueryParser {
    fun parse(args: Map<String, Any?>): ParseOutcome {
        val unknownTop = args.keys.filter { it !in TOP_LEVEL }
        if (unknownTop.any { it in FORBIDDEN }) {
            return ParseOutcome.Unsupported(AppointmentIntelligenceLimits.OTHER_DOCTOR)
        }
        if (unknownTop.isNotEmpty()) {
            return ParseOutcome.Unsupported("I can't filter by ${unknownTop.first()}.")
        }
        val filters = asMap(args["filters"]) ?: emptyMap()
        val unknownFilters = filters.keys.filter { it !in FILTER_KEYS }
        if (unknownFilters.isNotEmpty()) {
            return ParseOutcome.Unsupported("I can't filter by ${unknownFilters.first()}.")
        }

        val metricParsed = parseMetric(args["metric"] as? String, args["shape"] as? String)
        if (metricParsed is ParseOutcome.Unsupported) return metricParsed
        var request = (metricParsed as ParseOutcome.Ok).request

        val time = parseTime(args["timeRange"])
        if (time is ParseOutcome.Unsupported) return time
        val comparison = parseTime(args["comparisonPeriod"])
        if (comparison is ParseOutcome.Unsupported) return comparison

        val statuses = parseStatuses(filters["statuses"] ?: filters["status"])
        if (statuses is ParseOutcome.Unsupported) return statuses
        val statusOk = statuses as ParseOutcome.Ok

        val documentation = parseDocumentation(filters["documentation"] as? String)
        if (documentation is ParseOutcome.Unsupported) return documentation
        val payment = parsePayment(filters["paymentStatus"] as? String)
        if (payment is ParseOutcome.Unsupported) return payment
        val modality = parseModality(filters["modality"] as? String)
        if (modality is ParseOutcome.Unsupported) return modality
        val group = parseGroup(args["groupBy"])
        if (group is ParseOutcome.Unsupported) return group
        val sort = parseSort(args["sort"] as? String, request.shape)
        if (sort is ParseOutcome.Unsupported) return sort
        val limit = parseLimit(args["limit"])
        if (limit is ParseOutcome.Unsupported) return limit

        val groupDim = (group as ParseOutcome.Ok).request.groupBy
        val shape = when {
            request.shape == Shape.LIST -> Shape.LIST
            groupDim != null || request.metric == Metric.GROUPED_COUNT -> Shape.GROUPED
            else -> request.shape
        }
        val metric = if (shape == Shape.GROUPED && request.metric == Metric.COUNT) {
            Metric.GROUPED_COUNT
        } else {
            request.metric
        }
        request = request.copy(
            metric = metric,
            shape = shape,
            time = (time as ParseOutcome.Ok).request.time,
            comparison = (comparison as ParseOutcome.Ok).request.time,
            statuses = statusOk.request.statuses.ifEmpty { request.statuses },
            upcoming = statusOk.request.upcoming || bool(filters["upcoming"]),
            patientId = (filters["patientId"] as? Number)?.toLong(),
            patientName = (filters["patientName"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            documentation = (documentation as ParseOutcome.Ok).request.documentation,
            paymentStatus = (payment as ParseOutcome.Ok).request.paymentStatus,
            modality = (modality as ParseOutcome.Ok).request.modality,
            groupBy = groupDim ?: request.groupBy,
            sortDescending = (sort as ParseOutcome.Ok).request.sortDescending,
            limit = (limit as ParseOutcome.Ok).request.limit,
            limitCapped = (limit as ParseOutcome.Ok).request.limitCapped,
        )
        return ParseOutcome.Ok(request)
    }

    private fun parseMetric(metric: String?, shapeRaw: String?): ParseOutcome {
        val shape = when (shapeRaw?.trim()?.uppercase()) {
            null, "" -> Shape.AGGREGATE
            "AGGREGATE" -> Shape.AGGREGATE
            "GROUPED" -> Shape.GROUPED
            "LIST" -> Shape.LIST
            else -> return ParseOutcome.Unsupported("I can't return results as $shapeRaw.")
        }
        val token = metric?.trim()?.uppercase()
        val resolved = when (token) {
            null, "", "COUNT" -> Metric.COUNT
            "LIST" -> return ParseOutcome.Ok(IntelligenceRequest(metric = Metric.COUNT, shape = Shape.LIST))
            "DISTINCT_PATIENT_COUNT" -> Metric.DISTINCT_PATIENT_COUNT
            "GROUPED_COUNT" -> Metric.GROUPED_COUNT
            "CANCELLATION_RATE" -> Metric.CANCELLATION_RATE
            "SUM_COLLECTED" -> Metric.SUM_COLLECTED
            "SUM_APPOINTMENT_VALUE" -> Metric.SUM_APPOINTMENT_VALUE
            "SUM_OUTSTANDING" -> Metric.SUM_OUTSTANDING
            "AVERAGE", "AVERAGE_COLLECTED", "AVERAGE_COLLECTED_MINOR" ->
                return ParseOutcome.Unsupported(AppointmentIntelligenceLimits.AVERAGE)
            "REFUND", "SUM_REFUNDED", "REFUND_AMOUNT" ->
                return ParseOutcome.Unsupported(AppointmentIntelligenceLimits.REFUND)
            "NO_SHOW", "NO_SHOW_RATE" ->
                return ParseOutcome.Ok(
                    IntelligenceRequest(metric = Metric.COUNT, shape = Shape.AGGREGATE, statuses = setOf("NO_SHOW")),
                )
            "PARTIAL", "SUM_PARTIAL" ->
                return ParseOutcome.Unsupported(AppointmentIntelligenceLimits.PARTIAL)
            else -> return ParseOutcome.Unsupported("I can't calculate $metric.")
        }
        val finalShape = if (resolved == Metric.GROUPED_COUNT) Shape.GROUPED else shape
        return ParseOutcome.Ok(IntelligenceRequest(metric = resolved, shape = finalShape))
    }

    private fun parseTime(raw: Any?): ParseOutcome {
        if (raw == null) return ParseOutcome.Ok(IntelligenceRequest())
        val map = asMap(raw) ?: return ParseOutcome.Unsupported("I could not read that time range.")
        val unknown = map.keys.filter { it !in TIME_KEYS }
        if (unknown.isNotEmpty()) return ParseOutcome.Unsupported("I can't filter by ${unknown.first()}.")
        val period = (map["period"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val expression = (map["expression"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val spec = when {
            period != null -> periodToken(period, map)
            expression != null -> TimeSpec.Expression(expression)
            map["startDate"] != null && map["endDate"] != null -> {
                val start = parseDate(map["startDate"]) ?: return ParseOutcome.Unsupported("I could not read that start date.")
                val end = parseDate(map["endDate"]) ?: return ParseOutcome.Unsupported("I could not read that end date.")
                TimeSpec.ExplicitRange(start, end)
            }
            map["startDate"] != null -> {
                val start = parseDate(map["startDate"]) ?: return ParseOutcome.Unsupported("I could not read that date.")
                TimeSpec.ExplicitRange(start, start)
            }
            else -> null
        }
        return when (spec) {
            is TimeSpec -> ParseOutcome.Ok(IntelligenceRequest(time = spec))
            is ParseOutcome.Unsupported -> spec
            else -> ParseOutcome.Ok(IntelligenceRequest())
        }
    }

    private fun periodToken(period: String, map: Map<String, Any?>): Any {
        val token = period.trim().uppercase().replace(' ', '_')
        CalendarPeriod.entries.firstOrNull { it.name == token }?.let { return TimeSpec.Calendar(it) }
        val n = (map["n"] as? Number)?.toInt()
        fun rolling(direction: RollingDirection, count: Int?, unit: RollingUnit): Any {
            if (count == null) return ParseOutcome.Unsupported("How many ${unit.name.lowercase()} should I use?")
            return TimeSpec.Rolling(direction, count, unit)
        }
        return when (token) {
            "LAST_7_DAYS" -> TimeSpec.Rolling(RollingDirection.LAST, 7, RollingUnit.DAYS)
            "LAST_14_DAYS" -> TimeSpec.Rolling(RollingDirection.LAST, 14, RollingUnit.DAYS)
            "LAST_30_DAYS" -> TimeSpec.Rolling(RollingDirection.LAST, 30, RollingUnit.DAYS)
            "LAST_60_DAYS" -> TimeSpec.Rolling(RollingDirection.LAST, 60, RollingUnit.DAYS)
            "LAST_90_DAYS" -> TimeSpec.Rolling(RollingDirection.LAST, 90, RollingUnit.DAYS)
            "NEXT_7_DAYS" -> TimeSpec.Rolling(RollingDirection.NEXT, 7, RollingUnit.DAYS)
            "NEXT_14_DAYS" -> TimeSpec.Rolling(RollingDirection.NEXT, 14, RollingUnit.DAYS)
            "NEXT_30_DAYS" -> TimeSpec.Rolling(RollingDirection.NEXT, 30, RollingUnit.DAYS)
            "LAST_N_DAYS" -> rolling(RollingDirection.LAST, n, RollingUnit.DAYS)
            "NEXT_N_DAYS" -> rolling(RollingDirection.NEXT, n, RollingUnit.DAYS)
            "LAST_N_WEEKS" -> rolling(RollingDirection.LAST, n, RollingUnit.WEEKS)
            "NEXT_N_WEEKS" -> rolling(RollingDirection.NEXT, n, RollingUnit.WEEKS)
            "LAST_N_MONTHS" -> rolling(RollingDirection.LAST, n, RollingUnit.MONTHS)
            "NEXT_N_MONTHS" -> rolling(RollingDirection.NEXT, n, RollingUnit.MONTHS)
            else -> TimeSpec.Expression(period)
        }
    }

    private fun parseStatuses(raw: Any?): ParseOutcome {
        val tokens = when (raw) {
            null -> emptyList()
            is String -> listOf(raw)
            is Collection<*> -> raw.mapNotNull { it?.toString() }
            else -> return ParseOutcome.Unsupported("I could not read those statuses.")
        }
        val statuses = mutableSetOf<String>()
        var upcoming = false
        for (token in tokens) {
            when (token.trim().uppercase()) {
                "REQUESTED", "UNCONFIRMED" -> statuses += "REQUESTED"
                "CONFIRMED" -> statuses += "CONFIRMED"
                "IN_PROGRESS" -> statuses += "IN_PROGRESS"
                "CANCELLED", "CANCELED" -> statuses += "CANCELLED"
                "COMPLETED" -> statuses += "COMPLETED"
                "NO_SHOW", "NOSHOW", "NO-SHOW" -> statuses += "NO_SHOW"
                "UPCOMING" -> upcoming = true
                "RESCHEDULED" -> return ParseOutcome.Unsupported(AppointmentIntelligenceLimits.RESCHEDULED)
                "PENDING" -> return ParseOutcome.Unsupported(
                    "Pending is not an appointment status. Unconfirmed visits are requested. Pending payment is a payment status.",
                )
                else -> return ParseOutcome.Unsupported("I don't have an appointment status called $token.")
            }
        }
        return ParseOutcome.Ok(IntelligenceRequest(statuses = statuses, upcoming = upcoming))
    }

    private fun parseDocumentation(raw: String?): ParseOutcome {
        if (raw.isNullOrBlank()) return ParseOutcome.Ok(IntelligenceRequest())
        val value = when (raw.trim().uppercase()) {
            "NO_CONSULTATION_NOTE", "NONE", "MISSING" -> DocumentationFilter.NO_CONSULTATION_NOTE
            "HAS_CONSULTATION_NOTE", "HAS_NOTE" -> DocumentationFilter.HAS_CONSULTATION_NOTE
            "PATIENT_SIGNED", "SIGNED" -> DocumentationFilter.PATIENT_SIGNED
            "PATIENT_UNSIGNED", "UNSIGNED" -> DocumentationFilter.PATIENT_UNSIGNED
            "FINALIZED", "DOCTOR_UNSIGNED", "DOCTOR_SIGNED" ->
                return ParseOutcome.Unsupported(AppointmentIntelligenceLimits.FINALIZED)
            else -> return ParseOutcome.Unsupported("I can't filter documentation by $raw.")
        }
        return ParseOutcome.Ok(IntelligenceRequest(documentation = value))
    }

    private fun parsePayment(raw: String?): ParseOutcome {
        if (raw.isNullOrBlank()) return ParseOutcome.Ok(IntelligenceRequest())
        val value = when (raw.trim().uppercase()) {
            "NOT_REQUIRED" -> PaymentSnapshot.NOT_REQUIRED
            "PENDING", "UNPAID" -> PaymentSnapshot.PENDING
            "PAID" -> PaymentSnapshot.PAID
            "FAILED" -> PaymentSnapshot.FAILED
            "REFUNDED" -> PaymentSnapshot.REFUNDED
            "PARTIAL", "PARTIALLY_PAID" -> return ParseOutcome.Unsupported(AppointmentIntelligenceLimits.PARTIAL)
            else -> return ParseOutcome.Unsupported("I can't filter payment by $raw.")
        }
        return ParseOutcome.Ok(IntelligenceRequest(paymentStatus = value))
    }

    private fun parseModality(raw: String?): ParseOutcome {
        if (raw.isNullOrBlank()) return ParseOutcome.Ok(IntelligenceRequest())
        val value = when (raw.trim().uppercase()) {
            "VIDEO", "ONLINE" -> Modality.VIDEO
            "IN_PERSON", "INPERSON" -> Modality.IN_PERSON
            "BOOKING_CHANNEL", "CHANNEL" -> return ParseOutcome.Unsupported(AppointmentIntelligenceLimits.BOOKING_CHANNEL)
            else -> return ParseOutcome.Unsupported("I can't filter visit type by $raw.")
        }
        return ParseOutcome.Ok(IntelligenceRequest(modality = value))
    }

    private fun parseGroup(raw: Any?): ParseOutcome {
        val token = when (raw) {
            null -> return ParseOutcome.Ok(IntelligenceRequest())
            is String -> raw
            is Collection<*> -> {
                val values = raw.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
                if (values.size > 1) {
                    return ParseOutcome.Unsupported("I can group by one thing at a time: status, day, week, month, payment status, visit type, or patient.")
                }
                values.firstOrNull() ?: return ParseOutcome.Ok(IntelligenceRequest())
            }
            else -> return ParseOutcome.Unsupported("I could not read that grouping.")
        }
        val dimension = when (token.trim().uppercase()) {
            "STATUS" -> GroupDimension.STATUS
            "DAY", "LOCAL_DAY" -> GroupDimension.LOCAL_DAY
            "WEEK", "LOCAL_WEEK" -> GroupDimension.LOCAL_WEEK
            "MONTH", "LOCAL_MONTH" -> GroupDimension.LOCAL_MONTH
            "PAYMENT_STATUS" -> GroupDimension.PAYMENT_STATUS
            "MODALITY", "VISIT_TYPE" -> GroupDimension.MODALITY
            "PATIENT" -> GroupDimension.PATIENT
            "BOOKING_CHANNEL", "CHANNEL" -> return ParseOutcome.Unsupported(AppointmentIntelligenceLimits.BOOKING_CHANNEL)
            else -> return ParseOutcome.Unsupported("I can't group by $token.")
        }
        return ParseOutcome.Ok(IntelligenceRequest(groupBy = dimension, shape = Shape.GROUPED, metric = Metric.GROUPED_COUNT))
    }

    private fun parseSort(raw: String?, shape: Shape): ParseOutcome {
        if (raw.isNullOrBlank()) return ParseOutcome.Ok(IntelligenceRequest())
        return when (raw.trim().uppercase()) {
            "START_AT_ASC" -> ParseOutcome.Ok(IntelligenceRequest(sortDescending = false))
            "START_AT_DESC" -> ParseOutcome.Ok(IntelligenceRequest(sortDescending = true))
            "COUNT_DESC" -> if (shape == Shape.LIST) {
                ParseOutcome.Unsupported("Lists are sorted by visit time.")
            } else {
                ParseOutcome.Ok(IntelligenceRequest(sortDescending = true))
            }
            else -> ParseOutcome.Unsupported("I can't sort by $raw.")
        }
    }

    private fun parseLimit(raw: Any?): ParseOutcome {
        val n = (raw as? Number)?.toInt() ?: return ParseOutcome.Ok(IntelligenceRequest())
        if (n < 1) return ParseOutcome.Unsupported("The result limit has to be at least 1.")
        return ParseOutcome.Ok(IntelligenceRequest(limit = n.coerceAtMost(MAX_LIST), limitCapped = n > MAX_LIST))
    }

    private fun parseDate(raw: Any?): LocalDate? =
        (raw as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }

    companion object {
        const val MAX_LIST = 25
        private val TOP_LEVEL = setOf(
            "metric", "timeRange", "comparisonPeriod", "filters", "groupBy", "sort", "limit", "shape",
        )
        private val FORBIDDEN = setOf("doctorId", "clinicId", "sql", "query")
        private val FILTER_KEYS = setOf(
            "patientId", "patientName", "statuses", "status", "upcoming", "documentation", "paymentStatus", "modality",
        )
        private val TIME_KEYS = setOf(
            "type", "period", "n", "unit", "direction", "startDate", "endDate", "expression",
        )
    }
}

data class StoredQuery(
    val doctorId: Long,
    val range: ResolvedTimeRange,
    val metric: Metric,
    val shape: Shape,
    val presentation: Presentation,
    val groupBy: GroupDimension?,
    val statuses: Set<String>,
    val upcoming: Boolean,
    val patientId: Long?,
    val documentation: DocumentationFilter?,
    val paymentStatus: PaymentSnapshot?,
    val modality: Modality?,
    val sortDescending: Boolean,
) {
    fun toRequest(): IntelligenceRequest = IntelligenceRequest(
        metric = metric,
        shape = shape,
        presentation = presentation,
        time = TimeSpec.Fixed(range),
        statuses = statuses,
        upcoming = upcoming,
        patientId = patientId,
        documentation = documentation,
        paymentStatus = paymentStatus,
        modality = modality,
        groupBy = groupBy,
        sortDescending = sortDescending,
    )
}

object AppointmentQueryContextCodec {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val tag = Regex("""<appointment_query>(.*?)</appointment_query>""", RegexOption.DOT_MATCHES_ALL)

    fun latest(messages: List<String>): StoredQuery? {
        val json = messages.asReversed().firstNotNullOfOrNull { message ->
            tag.findAll(message).lastOrNull()?.groupValues?.getOrNull(1)?.trim()
        } ?: return null
        return decode(json)
    }

    fun encode(
        doctorId: Long,
        request: IntelligenceRequest,
        range: ResolvedTimeRange,
        patientId: Long?,
    ): Map<String, Any?> = mapOf(
        "v" to 1,
        "doctorId" to doctorId,
        "metric" to request.metric.name,
        "shape" to request.shape.name,
        "presentation" to request.presentation.name,
        "groupBy" to request.groupBy?.name,
        "statuses" to request.statuses.toList(),
        "upcoming" to request.upcoming,
        "patientId" to patientId,
        "documentation" to request.documentation?.name,
        "paymentStatus" to request.paymentStatus?.name,
        "modality" to request.modality?.name,
        "start" to range.startInclusive.toString(),
        "endExclusive" to range.endExclusive.toString(),
        "label" to range.label,
        "kind" to range.kind.name,
        "capped" to range.capped,
        "sortDescending" to request.sortDescending,
    )

    fun decode(json: String): StoredQuery? {
        if (json.length !in 2..2000) return null
        val tree = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val doctorId = tree.path("doctorId").asLong(0)
        if (doctorId <= 0L) return null
        val start = runCatching { Instant.parse(tree.path("start").asText()) }.getOrNull() ?: return null
        val end = runCatching { Instant.parse(tree.path("endExclusive").asText()) }.getOrNull() ?: return null
        if (!end.isAfter(start)) return null
        val metric = enumOrNull<Metric>(tree.path("metric").asText(null)) ?: return null
        val shape = enumOrNull<Shape>(tree.path("shape").asText(null)) ?: Shape.AGGREGATE
        val presentation = enumOrNull<Presentation>(tree.path("presentation").asText(null)) ?: Presentation.TOTAL
        val groupBy = enumOrNull<GroupDimension>(tree.path("groupBy").asText(null))
        val statuses = tree.path("statuses").mapNotNull { it.asText(null) }.toSet()
        if (statuses.any { it !in ALLOWED_STATUSES }) return null
        val kind = enumOrNull<TimeRangeKind>(tree.path("kind").asText(null)) ?: TimeRangeKind.CALENDAR
        return StoredQuery(
            doctorId = doctorId,
            range = ResolvedTimeRange(
                startInclusive = start,
                endExclusive = end,
                label = tree.path("label").asText("that period").take(80),
                kind = kind,
                capped = tree.path("capped").asBoolean(false),
            ),
            metric = metric,
            shape = shape,
            presentation = presentation,
            groupBy = groupBy,
            statuses = statuses,
            upcoming = tree.path("upcoming").asBoolean(false),
            patientId = tree.path("patientId").takeIf { !it.isMissingNode && !it.isNull }?.asLong(),
            documentation = enumOrNull<DocumentationFilter>(tree.path("documentation").asText(null)),
            paymentStatus = enumOrNull<PaymentSnapshot>(tree.path("paymentStatus").asText(null)),
            modality = enumOrNull<Modality>(tree.path("modality").asText(null)),
            sortDescending = tree.path("sortDescending").asBoolean(false),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(raw: String?): T? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return enumValues<T>().firstOrNull { it.name == raw }
    }

    private val ALLOWED_STATUSES =
        setOf("REQUESTED", "CONFIRMED", "IN_PROGRESS", "CANCELLED", "COMPLETED", "NO_SHOW")
}

sealed class MergeResult {
    data class Ready(val request: IntelligenceRequest, val continued: Boolean) : MergeResult()
    data class Unsupported(val reason: String) : MergeResult()
}

class AppointmentQueryContextMerger(
    private val ranges: ClinicTimeRangeResolver,
) {
    fun merge(
        doctorId: Long,
        zone: ZoneId,
        incoming: IntelligenceRequest,
        previous: StoredQuery?,
        latestUserText: String,
    ): MergeResult {
        val text = normalize(latestUserText)
        unsupportedFromText(text)?.let { return MergeResult.Unsupported(it) }
        val usablePrevious = previous?.takeIf { it.doctorId == doctorId }
        val continued = usablePrevious != null && isFollowUp(text) && !isOtherTopic(text)
        val base = if (continued) usablePrevious.toRequest() else incoming
        return MergeResult.Ready(overlay(base, incoming, text, zone, continued), continued)
    }

    private fun overlay(
        base: IntelligenceRequest,
        incoming: IntelligenceRequest,
        text: String,
        zone: ZoneId,
        continued: Boolean,
    ): IntelligenceRequest {
        var request = base
        val named = incoming.patientName?.trim()?.takeIf { it.isNotEmpty() }
        if (continued && named != null && text.contains(named.lowercase())) {
            request = request.copy(patientId = null, patientName = named)
        } else if (!continued) {
            request = request.copy(patientName = named ?: request.patientName)
        }

        val found = ranges.findRanges(text, zone)
        val compare = Regex("""\b(compare|versus| vs )\b""").containsMatchIn(text)
        if (found.size >= 2 && compare) {
            request = request.copy(
                time = TimeSpec.Fixed(found[0]),
                comparison = TimeSpec.Fixed(found[1]),
            )
        } else if (found.isNotEmpty()) {
            request = request.copy(time = TimeSpec.Fixed(found[0]), comparison = null)
        }

        when {
            confirmationBreakdown(text) -> request = request.copy(
                metric = Metric.GROUPED_COUNT,
                shape = Shape.GROUPED,
                presentation = Presentation.STATUS_BREAKDOWN,
                groupBy = GroupDimension.STATUS,
                statuses = emptySet(),
                upcoming = false,
            )
            wantsList(text) && mentionsUnconfirmed(text) -> request = request.copy(
                metric = Metric.COUNT,
                shape = Shape.LIST,
                presentation = Presentation.TOTAL,
                groupBy = null,
                statuses = setOf("REQUESTED"),
                upcoming = false,
            )
            mentionsUnconfirmed(text) -> request = request.copy(
                metric = Metric.COUNT,
                shape = Shape.AGGREGATE,
                presentation = Presentation.TOTAL,
                groupBy = null,
                statuses = setOf("REQUESTED"),
                upcoming = false,
            )
            else -> {
                val mentioned = statusesInText(text)
                if (mentioned.isNotEmpty()) {
                    request = request.copy(
                        metric = Metric.COUNT,
                        shape = if (wantsList(text)) Shape.LIST else Shape.AGGREGATE,
                        presentation = Presentation.TOTAL,
                        groupBy = null,
                        statuses = mentioned,
                        upcoming = false,
                    )
                } else if (wantsList(text)) {
                    request = request.copy(shape = Shape.LIST, metric = Metric.COUNT, presentation = Presentation.TOTAL)
                }
            }
        }

        documentationFromText(text)?.let { request = request.copy(documentation = it) }
        paymentFromText(text)?.let { request = request.copy(paymentStatus = it) }
        modalityFromText(text)?.let { request = request.copy(modality = it) }
        metricFromText(text)?.let { metric ->
            request = request.copy(
                metric = metric,
                shape = if (metric == Metric.GROUPED_COUNT) Shape.GROUPED else Shape.AGGREGATE,
                presentation = Presentation.TOTAL,
            )
        }
        groupFromText(text)?.let { dimension ->
            request = if (request.metric == Metric.COUNT || request.metric == Metric.GROUPED_COUNT) {
                request.copy(
                    metric = Metric.GROUPED_COUNT,
                    shape = Shape.GROUPED,
                    groupBy = dimension,
                    presentation = if (dimension == GroupDimension.STATUS) {
                        Presentation.STATUS_BREAKDOWN
                    } else {
                        Presentation.TOTAL
                    },
                )
            } else {
                request.copy(groupBy = dimension)
            }
        }
        if (Regex("""\bhow many patients\b|\bdistinct patients\b""").containsMatchIn(text)) {
            request = request.copy(metric = Metric.DISTINCT_PATIENT_COUNT, shape = Shape.AGGREGATE, groupBy = null)
        }
        return request
    }

    private fun unsupportedFromText(text: String): String? = when {
        Regex("""\breschedul""").containsMatchIn(text) -> AppointmentIntelligenceLimits.RESCHEDULED
        Regex("""\bfinali[sz]ed\b|\bdoctor[-\s]?signed\b|\bunsigned by\b""").containsMatchIn(text) ->
            AppointmentIntelligenceLimits.FINALIZED
        Regex("""\bpartial(?:ly)? paid\b|\bpartial payments?\b""").containsMatchIn(text) ->
            AppointmentIntelligenceLimits.PARTIAL
        Regex("""\brefunds?\b""").containsMatchIn(text) -> AppointmentIntelligenceLimits.REFUND
        Regex("""\bbooking channel\b|\bbooking source\b|\bbooked online\b""").containsMatchIn(text) ->
            AppointmentIntelligenceLimits.BOOKING_CHANNEL
        Regex("""\baverage\b""").containsMatchIn(text) -> AppointmentIntelligenceLimits.AVERAGE
        else -> null
    }

    private fun isFollowUp(text: String): Boolean =
        Regex(
            """\b(what about|how about|how many are|how many of (them|those)|show me the|show the|list the|which of)\b""",
        ).containsMatchIn(text)

    private fun isOtherTopic(text: String): Boolean =
        Regex("""\b(free slots?|available times?|book a|book an|open the document|dental diagram|dental chart)\b""")
            .containsMatchIn(text)

    private fun confirmationBreakdown(text: String): Boolean =
        Regex("""\bhow many are confirmed\b""").containsMatchIn(text) && !mentionsUnconfirmed(text)

    private fun mentionsUnconfirmed(text: String): Boolean =
        Regex("""\bunconfirmed\b|\bawaiting confirmation\b|\bnot confirmed\b""").containsMatchIn(text)

    private fun wantsList(text: String): Boolean =
        Regex("""\b(show|list|which)\b""").containsMatchIn(text)

    private fun statusesInText(text: String): Set<String> {
        val out = mutableSetOf<String>()
        if (Regex("""\bcancell?ed\b""").containsMatchIn(text)) out += "CANCELLED"
        if (Regex("""\bcompleted\b""").containsMatchIn(text)) out += "COMPLETED"
        if (Regex("""\bconfirmed\b""").containsMatchIn(text) && !mentionsUnconfirmed(text)) out += "CONFIRMED"
        if (Regex("""\bin progress\b""").containsMatchIn(text)) out += "IN_PROGRESS"
        if (Regex("""\brequested\b""").containsMatchIn(text)) out += "REQUESTED"
        if (Regex("""\bno[-\s]?shows?\b""").containsMatchIn(text)) out += "NO_SHOW"
        return out
    }

    private fun documentationFromText(text: String): DocumentationFilter? = when {
        Regex("""\bundocumented\b|\bmissing notes?\b|\bno consultation\b|\bwithout notes?\b|\black consultation\b""")
            .containsMatchIn(text) -> DocumentationFilter.NO_CONSULTATION_NOTE
        Regex("""\bunsigned\b""").containsMatchIn(text) -> DocumentationFilter.PATIENT_UNSIGNED
        Regex("""\bpatient signed\b|\bsigned documentation\b|\bhave signed\b""").containsMatchIn(text) ->
            DocumentationFilter.PATIENT_SIGNED
        Regex("""\bwith notes\b|\bdocumented\b""").containsMatchIn(text) -> DocumentationFilter.HAS_CONSULTATION_NOTE
        else -> null
    }

    private fun paymentFromText(text: String): PaymentSnapshot? = when {
        Regex("""\bunpaid\b|\bnot paid\b""").containsMatchIn(text) -> PaymentSnapshot.PENDING
        Regex("""\bnot required\b""").containsMatchIn(text) -> PaymentSnapshot.NOT_REQUIRED
        Regex("""\bfailed payment\b""").containsMatchIn(text) -> PaymentSnapshot.FAILED
        Regex("""\bpaid\b""").containsMatchIn(text) -> PaymentSnapshot.PAID
        else -> null
    }

    private fun modalityFromText(text: String): Modality? = when {
        Regex("""\bin[-\s]?person\b""").containsMatchIn(text) -> Modality.IN_PERSON
        Regex("""\bvideo\b|\bonline appointments?\b""").containsMatchIn(text) -> Modality.VIDEO
        else -> null
    }

    private fun metricFromText(text: String): Metric? = when {
        Regex("""\bhow much\b|\bcollected\b|\bdid i collect\b|\brevenue\b""").containsMatchIn(text) ->
            Metric.SUM_COLLECTED
        Regex("""\boutstanding\b|\bstill owed\b""").containsMatchIn(text) -> Metric.SUM_OUTSTANDING
        Regex("""\bappointment value\b|\bvisit prices?\b""").containsMatchIn(text) -> Metric.SUM_APPOINTMENT_VALUE
        Regex("""\bcancellation rate\b|\bcancel rate\b""").containsMatchIn(text) -> Metric.CANCELLATION_RATE
        else -> null
    }

    private fun groupFromText(text: String): GroupDimension? = when {
        Regex("""\beach day\b|\bby day\b|\bper day\b""").containsMatchIn(text) -> GroupDimension.LOCAL_DAY
        Regex("""\beach week\b|\bby week\b""").containsMatchIn(text) -> GroupDimension.LOCAL_WEEK
        Regex("""\beach month\b|\bby month\b|\bper month\b""").containsMatchIn(text) -> GroupDimension.LOCAL_MONTH
        Regex("""\bby status\b""").containsMatchIn(text) -> GroupDimension.STATUS
        Regex("""\bwhich patients\b|\bby patient\b""").containsMatchIn(text) -> GroupDimension.PATIENT
        else -> null
    }
}

internal fun asMap(value: Any?): Map<String, Any?>? = when (value) {
    is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
    else -> null
}

internal fun bool(value: Any?): Boolean = when (value) {
    is Boolean -> value
    is String -> value.equals("true", ignoreCase = true)
    else -> false
}
