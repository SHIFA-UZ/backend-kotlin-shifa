package com.shifa.admin.api

import com.shifa.admin.api.dto.LegalDocumentsDto
import com.shifa.admin.application.LegalDocuments
import com.shifa.shared.pdf.PdfLanguage
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** The terms, privacy policy and licences links, public (both apps show them before sign-in). */
@RestController
@RequestMapping("/api/public/legal")
class PublicLegalController(private val legal: LegalDocuments) {

    /** [lang] `en` (default), `uz` or `ru`; another value is 400. */
    @GetMapping
    fun legalDocuments(@RequestParam(required = false) lang: String?): LegalDocumentsDto {
        val links = legal.links(PdfLanguage.parse(lang) ?: "en")
        return LegalDocumentsDto(links.terms, links.privacy, links.licences, links.version, links.updatedAt)
    }
}
