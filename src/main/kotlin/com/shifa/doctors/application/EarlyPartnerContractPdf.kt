package com.shifa.doctors.application

import com.shifa.shared.pdf.PdfAlign
import com.shifa.shared.pdf.PdfBox
import com.shifa.shared.pdf.PdfFlow
import com.shifa.shared.pdf.PdfMeasure
import com.shifa.shared.pdf.PdfOp
import com.shifa.shared.pdf.PdfPage
import java.time.LocalDate

/** The partner fields and dates of the early-partner contract PDF (the admin panel's `EarlyPartnerContractPdfData`). */
data class EarlyPartnerContractPdfData(
    val contractNumber: String,
    val effectiveDate: LocalDate,
    val termMonths: Int,
    val partnerFullName: String?,
    val partnerFirstName: String?,
    val partnerLastName: String?,
    val partnerClinic: String?,
    val partnerPhone: String?,
    val partnerEmail: String?,
    // Both signature dates: the day the contract was issued.
    val signedDate: LocalDate,
)

/**
 * The SHIFA early-user partnership contract (Uzbek, A4), ported from the admin panel's
 * `features/legal/pdf/early_partner_contract_pdf_service.dart` with the same text and order: header with the logo,
 * title, the number/date/term strip, sections 1–9 in grey-banded boxes, the parties, both signatures (the partner's
 * typed in a handwriting face), the acknowledgement, and a footer with the number and the page on every page.
 * Differences, because the shared engine draws in grey only: the brand teal is a dark grey, there is no faint logo
 * watermark, no italics, and the partner signature is the typed name without the drawn flourish.
 */
object EarlyPartnerContractPdf {
    const val PAGE_W = 595f
    const val PAGE_H = 842f
    const val MARGIN = 40f
    private const val PAD = 12f
    private const val BODY = 10f
    private const val LOGO_SIZE = 52f

    const val SUPPLIER_LEGAL_NAME = "Qobilov Bekzodbek Olimjon o'g'li YaTT"
    const val SUPPLIER_ADDRESS = "Istiqbol 30, Andijon, O'zbekiston"
    const val SHIFA_SIGNATORY_NAME = "Bekzodbek Qobilov Olimjon O'g'li"
    const val SHIFA_SIGNATORY_TITLE = "CEO, Founder"

    private val LOGO: ByteArray? =
        EarlyPartnerContractPdf::class.java.getResourceAsStream("/branding/shifa_logo.png")?.use { it.readBytes() }

    private val MONTHS_UZ = listOf(
        "Yanvar", "Fevral", "Mart", "Aprel", "May", "Iyun", "Iyul", "Avgust", "Sentabr", "Oktabr", "Noyabr", "Dekabr",
    )

    fun pdf(data: EarlyPartnerContractPdfData): ByteArray = PdfBox.make { pages(data, it) }

    internal fun pages(data: EarlyPartnerContractPdfData, measure: PdfMeasure): List<PdfPage> = Layout(data, measure).run()

    /** "Shohruhmirzo S.": the first name and the last name's initial, like a real signature. */
    internal fun signatureInk(first: String?, last: String?, full: String?): String {
        val f = first?.trim().orEmpty()
        val l = last?.trim().orEmpty()
        if (f.isNotEmpty() && l.isNotEmpty()) return "$f ${l.first().uppercaseChar()}."
        val parts = full?.trim().orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 -> "${parts.first()} ${parts.last().first().uppercaseChar()}."
            else -> parts.firstOrNull().orEmpty()
        }
    }

    private fun dotted(d: LocalDate) = "%02d.%02d.%d".format(d.dayOfMonth, d.monthValue, d.year)

    private fun spelled(d: LocalDate) = "%02d %s %d".format(d.dayOfMonth, MONTHS_UZ[d.monthValue - 1], d.year)

    // The admin text always says "(olti)" (six); only six is spelled out here.
    private fun term(months: Int) = if (months == 6) "6 (olti)" else "$months"

    private class Layout(private val d: EarlyPartnerContractPdfData, measure: PdfMeasure) :
        PdfFlow(PAGE_W, PAGE_H, MARGIN, measure) {

        /** The open section body on the current page: where its background box goes and where it starts. */
        private class Box(val ops: MutableList<PdfOp>, val index: Int, val top: Float)

        private var box: Box? = null
        private val innerW = contentW - 2 * PAD

        fun run(): List<PdfPage> {
            header()
            title()
            meta()
            body()
            parties()
            signatures()
            acknowledgement()
            val all = finish()
            return all.mapIndexed { i, page -> PdfPage(page.width, page.height, page.ops + footer(i + 1, all.size)) }
        }

        override fun newPage() {
            val open = box
            open?.let { close(it, bottom) }
            super.newPage()
            if (open != null) {
                box = Box(ops, ops.size, y)
                y += PAD
            }
        }

        private fun close(b: Box, end: Float) {
            b.ops.add(b.index, PdfOp.Rect(margin, b.top, contentW, end - b.top, fill = 0.98f, stroke = 0.45f, strokeWidth = 0.75f))
        }

        private fun header() {
            var x = margin
            LOGO?.let {
                ops += PdfOp.Image(margin, y, LOGO_SIZE, LOGO_SIZE, it)
                x += LOGO_SIZE + 12
            }
            val leftW = contentW / 2
            ops += PdfOp.Text(x, y + 8, leftW, "SHIFA", 22f, bold = true)
            ops += PdfOp.Text(x, y + 8 + measure("SHIFA", 22f, true, leftW), leftW, "Raqamli tibbiy platforma", 9f, gray = 0.38f)
            val rightX = margin + contentW / 2
            val first = "Hujjat turi: Hamkorlik shartnomasi"
            ops += PdfOp.Text(rightX, y, contentW / 2, first, 9f, gray = 0.38f, align = PdfAlign.End)
            ops += PdfOp.Text(
                rightX, y + measure(first, 9f, false, contentW / 2), contentW / 2, "Versiya: Erta foydalanuvchi", 9f,
                gray = 0.38f, align = PdfAlign.End,
            )
            y += LOGO_SIZE + 10
            // The admin panel's brand teal line.
            ops += PdfOp.Rect(margin, y, contentW, 2f, fill = 0.45f)
            y += 2
        }

        private fun title() {
            y += 12
            val t = "SHIFA ERTA FOYDALANUVCHI HAMKORLIK SHARTNOMASI"
            ops += PdfOp.Text(margin, y, contentW, t, 16f, bold = true, align = PdfAlign.Center)
            y += measure(t, 16f, true, contentW) + 8
            ops += PdfOp.Rect(margin, y, contentW, 1f, fill = 0.74f)
            y += 1
        }

        private fun meta() {
            y += 14
            val labelW = 155f
            val rows = listOf(
                "Shartnoma raqami:" to d.contractNumber,
                "Kuchga kirish sanasi:" to dotted(d.effectiveDate),
                "Amal qilish muddati:" to "Kuchga kirgan kundan boshlab ${term(d.termMonths)} oy",
            )
            val valueW = innerW - labelW
            val heights = rows.map { (l, v) -> maxOf(measure(l, BODY, true, labelW), measure(v, BODY, false, valueW)) }
            val h = heights.sum() + 6 * (rows.size - 1) + 2 * PAD
            need(h)
            ops += PdfOp.Rect(margin, y, contentW, h, fill = 0.95f, stroke = 0.45f, strokeWidth = 1.2f)
            var top = y + PAD
            rows.forEachIndexed { i, (label, value) ->
                ops += PdfOp.Text(margin + PAD, top, labelW, label, BODY, bold = true)
                ops += PdfOp.Text(margin + PAD + labelW, top, valueW, value, BODY)
                top += heights[i] + 6
            }
            y += h + 6
        }

        @Suppress("LongMethod", "MaxLineLength")
        private fun body() {
            section("1. MAQSAD") {
                text(
                    "Mazkur Shartnoma SHIFA va Hamkor o'rtasida SHIFA telemeditsina platformasini dastlabki joriy etish bosqichida sinovdan o'tkazish, tekshirish va takomillashtirish maqsadida hamkorlik munosabatlarini belgilaydi. Har ikki tomon ushbu hamkorlik O'zbekistonda qulay raqamli sog'liqni saqlash tizimini rivojlantirish uchun o'zaro manfaatli ekanini tan oladi.",
                )
            }
            section("2. SHIFA TOMONIDAN TAQDIM ETILADIGAN XIZMATLAR") {
                text("Mazkur Shartnoma amal qiladigan davr mobaynida SHIFA Hamkorga quyidagilarni bepul taqdim etadi:")
                y += 8
                subsection("2.1 Platformadan to'liq foydalanish")
                bullets(
                    "SHIFA shifokorlar ilovasining barcha funksiyalaridan to'liq foydalanish (shifokor-hamkorlar uchun)",
                    "SHIFA bemorlar ilovasining barcha funksiyalaridan to'liq foydalanish (bemor-hamkorlar uchun)",
                    "Shartnoma muddati davomida chiqarilgan barcha yangilanishlar va yangi funksiyalar",
                    "Qabul yozuvlari, raqamli tibbiy kartalar va telemeditsina konsultatsiyalarini boshqarishda hech qanday cheklovlarning yo'qligi",
                )
                y += 8
                subsection("2.2 Masofaviy qo'llab-quvvatlash")
                bullets(
                    "Telegram/WhatsApp orqali alohida texnik yordam",
                    "Javob berish muddati: 24 ish soati ichida",
                    "Ulanish, sozlash va nosozliklarni bartaraf etishda yordam",
                    "Muammolarni ustuvor tartibda hal qilish",
                )
                y += 8
                subsection("2.3 Hujjatlar va o'qitish")
                bullets(
                    "Platformaning barcha funksiyalari bo'yicha to'liq foydalanuvchi hujjatlari",
                    "Muhim funksiyalar bo'yicha videoqo'llanmalar va bosqichma-bosqich ko'rsatmalar",
                    "Yangi funksiyalar chiqarilishi bilan yangilanadigan materiallar",
                )
            }
            section("3. HAMKORNING MAJBURIYATLARI") {
                text("2-bo'limda ko'rsatilgan xizmatlar evaziga Hamkor quyidagilarga rozilik bildiradi:")
                y += 8
                subsection("3.1 Haftalik fikr-mulohaza sessiyalari")
                bullets(
                    "Haftasiga bir (1) marta masofaviy fikr-mulohaza sessiyasida ishtirok etish (taxminan 20–30 daqiqa)",
                    "Sessiyalar har ikki tomon kelishgan vaqtda o'tkaziladi",
                    "Platformaning qulayligi, funksiyalari va ishlashi bo'yicha halol va konstruktiv fikr-mulohaza bildirish",
                    "Hafta davomida aniqlangan xatolar, muammolar yoki takliflar haqida xabar berish",
                    "Minimal ishtirok: rejalashtirilgan sessiyalarning kamida 80 foizida qatnashish (bayramlar va favqulodda holatlar hisobga olingan holda)",
                )
                y += 8
                subsection("3.2 Platforma vakilligi")
                bullets(
                    "SHIFA platformasini professional va shaxsiy doiralarda faol targ'ib qilish",
                    "Ijobiy tajriba bilan hamkasblar, bemorlar yoki hamjamiyat a'zolari bilan bo'lishish",
                    "Zarurat tug'ilganda yangi foydalanuvchilarni jalb qilishga ko'maklashish",
                    "Shartnoma muddati davomida kamida bir (1) ta fikr-mulohaza yoki keyslar tavsifini taqdim etish (Hamkor tomonidan mazmuni tasdiqlangan holda)",
                    "Oldindan yozma rozilik mavjud bo'lsa, reklama materiallarida (foto, iqtibos yoki video) ishtirok etish",
                )
            }
            section("4. MA'LUMOTLAR VA MAXFIYLIK") {
                bullets(
                    "SHIFA barcha bemor ma'lumotlari va tibbiy axborotni O'zbekiston Respublikasining amaldagi ma'lumotlarni himoya qilish qonunchiligiga muvofiq qayta ishlash majburiyatini oladi.",
                    "Fikr-mulohaza sessiyalarining yozuvlari (agar mavjud bo'lsa) faqat mahsulotni ichki takomillashtirish maqsadlarida ishlatiladi.",
                    "Hech qanday shaxsiy tibbiy ma'lumotlar aniq roziliksiz uchinchi shaxslarga berilmaydi.",
                    "Hamkor SHIFAning yozma ruxsatisiz platformaga oid maxfiy ma'lumotlar, chiqarilmagan funksiyalar skrinshotlari yoki ichki yozishmalarni uchinchi shaxslarga bermaslikka rozilik bildiradi.",
                )
            }
            section("5. INTELLEKTUAL MULK") {
                bullets(
                    "Fikr-mulohaza sessiyalarida bildirilgan barcha takliflar va g'oyalar SHIFA tomonidan mahsulotni rivojlantirish uchun qo'shimcha kompensatsiyasiz ishlatilishi mumkin.",
                    "SHIFA platformasi, uning dizayni, kodi va brendi SHIFAning mutlaq mulki bo'lib qoladi.",
                )
            }
            section("6. AMAL QILISH MUDDATI VA BEKOR QILISH") {
                bullets(
                    "Mazkur Shartnoma kuchga kirgan kundan boshlab ${term(d.termMonths)} oy davomida amal qiladi.",
                    "Har qanday tomon 14 kun oldin yozma ravishda xabardor qilish orqali Shartnomani bekor qilishi mumkin.",
                    "Bekor qilingandan so'ng Hamkorning platformaga kirishi standart tarif shartlariga o'tkaziladi (qo'llaniladigan tariflar haqida 30 kun oldin xabardor qilinadi).",
                )
            }
            section("7. SHARTNOMA YAKUNLANGANDAN KEYINGI AFZALLIKLAR") {
                text("${d.termMonths} oylik hamkorlik muvaffaqiyatli yakunlangandan so'ng Hamkor quyidagilarga ega bo'ladi:")
                y += 6
                bullets(
                    "Har qanday pullik tarif rejasining dastlabki 3 oyi uchun 50% chegirma",
                    "Platforma profilida \"Founding Partner\" belgisi",
                    "Kelajakdagi beta-funksiyalarga ustuvor kirish huquqi",
                )
            }
            section("8. JAVOBGARLIKNI CHEKLASH") {
                bullets(
                    "SHIFA platformani dastlabki joriy etish bosqichida \"qanday bo'lsa, shunday\" holatda taqdim etadi. SHIFA platforma barqarorligini ta'minlash uchun barcha sa'y-harakatlarni amalga oshirsa-da, vaqtinchalik nosozliklar yoki xatolar yuz berishi mumkin.",
                    "SHIFA sinov davrida platformadan foydalana olmaslik natijasida yuzaga kelgan bilvosita zararlar uchun javobgar emas.",
                    "Hamkor bu sinov bosqichi ekanini tasdiqlaydi va zaxira variant sifatida bemorlar bilan aloqa qilishning muqobil usullarini saqlab turishga rozilik bildiradi.",
                )
            }
            section("9. UMUMIY QOIDALAR") {
                bullets(
                    "Mazkur Shartnoma tomonlar o'rtasidagi to'liq o'zaro tushunishni ifodalaydi.",
                    "Har qanday o'zgartirishlar yozma shaklda rasmiylashtirilishi va har ikki tomon tomonidan imzolanishi kerak.",
                    "Mazkur Shartnoma O'zbekiston Respublikasi qonunchiligiga muvofiq tartibga solinadi.",
                    "Nizolar birinchi navbatda halol muzokaralar yo'li bilan hal qilinadi. Agar nizo 30 kun ichida hal qilinmasa, u O'zbekistonning vakolatli sudlariga topshiriladi.",
                )
            }
        }

        private fun parties() {
            y += 8
            section("TOMONLAR") {
                subsection("Ta'minotchi (SHIFA)")
                text("SHIFA raqamli tibbiy platformasi")
                text(SUPPLIER_LEGAL_NAME)
                text(SUPPLIER_ADDRESS)
                text("(keyingi o'rinlarda \"SHIFA\" deb yuritiladi)")
                y += 10
                subsection("Erta foydalanuvchi hamkor")
                labelLine("F.I.Sh.", d.partnerFullName)
                labelLine("Klinika/Tashkilot", d.partnerClinic)
                // The doctor's own contract: the doctor role is ticked.
                text("Roli: [X] Shifokor   [ ] Bemor   [ ] Ikkalasi ham", gap = 6f)
                labelLine("Bog'lanish telefoni", d.partnerPhone)
                labelLine("Elektron pochta", d.partnerEmail)
                text("(keyingi o'rinlarda \"Hamkor\" deb yuritiladi)")
            }
        }

        private fun signatures() {
            y += 6
            // The band, its box and both signature boxes stay together.
            need(SIGNATURE_H)
            section("IMZOLAR") {
                val colW = (innerW - 20) / 2
                val left = margin + PAD
                val top = y
                val h1 = signatureColumn(left, top, colW, "SHIFA nomidan", SHIFA_SIGNATORY_NAME, SHIFA_SIGNATORY_TITLE, null)
                val ink = signatureInk(d.partnerFirstName, d.partnerLastName, d.partnerFullName).takeIf { it.isNotEmpty() }
                val h2 = signatureColumn(left + colW + 20, top, colW, "Hamkor", d.partnerFullName?.trim()?.takeIf { it.isNotEmpty() }, null, ink)
                y = top + maxOf(h1, h2) + 4
            }
        }

        /** One signature box; its height. A null [ink] leaves a line to sign on. */
        private fun signatureColumn(x: Float, top: Float, w: Float, title: String, name: String?, role: String?, ink: String?): Float {
            val inner = w - 20
            var cy = top + 10
            fun put(text: String, size: Float, bold: Boolean = false) {
                ops += PdfOp.Text(x + 10, cy, inner, text, size, bold = bold)
                cy += measure(text, size, bold, inner)
            }
            put(title, 11f, bold = true)
            cy += 8
            fun line(label: String, value: String?) {
                put("$label:", 9f, bold = true)
                cy += 2
                put(value ?: "_______________________________", BODY)
                cy += 8
            }
            line("Ism", name)
            role?.let { line("Lavozim", it) }
            cy += 4
            put("Imzo:", 9f, bold = true)
            cy += 4
            if (ink != null) {
                ops += PdfOp.Script(x + 10, cy, ink, 30f)
                cy += 52
            } else {
                cy += 18
                ops += PdfOp.Rect(x + 10, cy, inner, 1f, fill = 0.26f)
                cy += 1
            }
            cy += 8
            put("Sana: ${spelled(d.signedDate)}", BODY)
            cy += 10
            ops += PdfOp.Rect(x, top, w, cy - top, stroke = 0.46f, strokeWidth = 0.75f)
            return cy - top
        }

        private fun acknowledgement() {
            y += 14
            val t = "Har ikki tomon ushbu Shartnoma shartlarini o'qib chiqqani, tushungani va ularga rozilik bildirganini tasdiqlaydi."
            val h = measure(t, BODY, false, contentW - 20) + 20
            need(h)
            ops += PdfOp.Rect(margin, y, contentW, h, fill = 0.96f, stroke = 0.62f, strokeWidth = 0.5f)
            ops += PdfOp.Text(margin + 10, y + 10, contentW - 20, t, BODY, align = PdfAlign.Center)
            y += h
        }

        /** A grey band with [title] and a bordered body box that continues on the next page when it doesn't fit. */
        private fun section(title: String, content: () -> Unit) {
            val h = measure(title, 11f, true, contentW - 16) + 12
            need(10 + h + 40)
            y += 10
            ops += PdfOp.Rect(margin, y, contentW, h, fill = 0.88f)
            ops += PdfOp.Text(margin + 8, y + 6, contentW - 16, title, 11f, bold = true)
            y += h
            box = Box(ops, ops.size, y)
            y += PAD
            content()
            y += PAD - 4
            box?.let { close(it, y) }
            box = null
        }

        private fun subsection(title: String) = text(title, size = 10.5f, bold = true, gray = 0.26f)

        private fun text(t: String, size: Float = BODY, bold: Boolean = false, gray: Float = 0f, gap: Float = 4f) {
            val h = measure(t, size, bold, innerW)
            need(h + gap)
            ops += PdfOp.Text(margin + PAD, y, innerW, t, size, bold = bold, gray = gray)
            y += h + gap
        }

        private fun bullets(vararg items: String) = items.forEach { item ->
            val x = margin + PAD + 4
            val w = innerW - 4 - BULLET_W
            val h = measure(item, BODY, false, w)
            need(h + 4)
            ops += PdfOp.Text(x, y, BULLET_W, "•", BODY)
            ops += PdfOp.Text(x + BULLET_W, y, w, item, BODY)
            y += h + 4
        }

        /** "Label: value", or the label and a line to write on when there is no value. */
        private fun labelLine(label: String, value: String?) {
            val labelW = 120f
            val valueW = innerW - labelW
            val v = value?.trim()?.takeIf { it.isNotEmpty() }
            val h = maxOf(measure("$label:", BODY, true, labelW), v?.let { measure(it, BODY, false, valueW) } ?: 12f)
            need(h + 6)
            ops += PdfOp.Text(margin + PAD, y, labelW, "$label:", BODY, bold = true)
            if (v != null) {
                ops += PdfOp.Text(margin + PAD + labelW, y, valueW, v, BODY)
            } else {
                ops += PdfOp.Line(margin + PAD + labelW, y + h, margin + PAD + innerW, y + h, gray = 0.62f, width = 0.75f)
            }
            y += h + 6
        }

        private fun footer(page: Int, pages: Int): List<PdfOp> {
            val fy = pageH - margin + 4
            return listOf(
                PdfOp.Line(margin, fy, pageW - margin, fy, gray = 0.74f, width = 0.75f),
                PdfOp.Text(margin, fy + 6, contentW * 0.7f, "SHIFA — ${d.contractNumber}", 9f, gray = 0.38f),
                PdfOp.Text(margin + contentW * 0.7f, fy + 6, contentW * 0.3f, "Sahifa $page / $pages", 9f, gray = 0.38f, align = PdfAlign.End),
                PdfOp.Text(margin, fy + 19, contentW, "Maxfiy hujjat. Ruxsatsiz tarqatish taqiqlanadi.", 8f, gray = 0.46f, align = PdfAlign.Center),
            )
        }

        private companion object {
            const val BULLET_W = 10f
            const val SIGNATURE_H = 260f
        }
    }
}
