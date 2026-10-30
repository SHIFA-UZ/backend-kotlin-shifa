package com.shifa.admin.api

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.servlet.view.RedirectView

/** `/admin-metrics` and `/admin-metrics/` open the static admin health page, which Spring serves only by its file name. */
@Controller
class AdminMetricsPageController {
    @GetMapping("/admin-metrics", "/admin-metrics/")
    fun page(): RedirectView = RedirectView("/admin-metrics/index.html")
}
