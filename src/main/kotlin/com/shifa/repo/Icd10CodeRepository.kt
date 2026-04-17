package com.shifa.repo

import com.shifa.domain.Icd10Code
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface Icd10CodeRepository : JpaRepository<Icd10Code, String> {

    /**
     * Production-grade hybrid search using:
     * - exact code match / prefix match (highest priority)
     * - full-text ranking (tsvector)
     * - trigram similarity (pg_trgm) for typo tolerance (lower weight)
     *
     * Returns raw rows with a computed score to allow smarter ranking downstream.
     */
    @Query(
        value = """
        SELECT
          c.code as code,
          c.title as title,
          c.title_ru as titleRu,
          c.keywords as keywords,
          c.parent_code as parentCode,
          (
            -- Hard priority for code matches
            CASE
              WHEN lower(c.code) = :qNorm THEN 1000
              WHEN lower(c.code) LIKE (:qNorm || '%') THEN 700
              ELSE 0
            END
            +
            -- Full-text rank (titles + keywords)
            (ts_rank_cd(c.search_tsv, websearch_to_tsquery('simple', :qTs)) * 200)
            +
            -- Trigram similarity (typo tolerance)
            (GREATEST(
              similarity(c.title, :qRaw),
              similarity(COALESCE(c.title_ru, ''), :qRaw),
              similarity(COALESCE(c.keywords, ''), :qRaw)
            ) * 80)
          ) as score
        FROM icd10_codes c
        WHERE
          -- keep query fast: must match at least one channel
          lower(c.code) LIKE (:qNorm || '%')
          OR c.search_tsv @@ websearch_to_tsquery('simple', :qTs)
          OR similarity(c.title, :qRaw) > :simThreshold
          OR similarity(COALESCE(c.title_ru, ''), :qRaw) > :simThreshold
          OR similarity(COALESCE(c.keywords, ''), :qRaw) > :simThreshold
        ORDER BY score DESC, c.code ASC
        LIMIT :limit
        """,
        nativeQuery = true
    )
    fun searchRankedNative(
        @Param("qRaw") qRaw: String,
        @Param("qNorm") qNorm: String,
        @Param("qTs") qTs: String,
        @Param("simThreshold") simThreshold: Double,
        @Param("limit") limit: Int
    ): List<Icd10SearchRow>
}

interface Icd10SearchRow {
    fun getCode(): String
    fun getTitle(): String
    fun getTitleru(): String? // native alias -> getter is lowercased
    fun getKeywords(): String?
    fun getParentcode(): String?
    fun getScore(): Double
}

