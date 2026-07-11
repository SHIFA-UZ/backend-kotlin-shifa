package com.shifa.clinicalengine.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "clinical_group")
class ClinicalGroup(
    @Id
    @Column(name = "group_id", length = 32)
    var groupId: String = "",

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "name_ru", nullable = false, columnDefinition = "TEXT")
    var nameRu: String = "",

    @Column(name = "name_uz", nullable = false, columnDefinition = "TEXT")
    var nameUz: String = "",

    @Column(name = "name_en", nullable = false, columnDefinition = "TEXT")
    var nameEn: String = "",
) {
    protected constructor() : this(groupId = "")
}
