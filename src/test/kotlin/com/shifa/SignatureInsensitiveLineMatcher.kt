package com.shifa

import com.tngtech.archunit.library.freeze.ViolationLineMatcher

/**
 * Matches frozen ArchUnit violations by what they mean, not by how they are printed.
 *
 * ArchUnit describes a constructor-injection dependency with the constructor's full parameter list, so removing or
 * adding one unrelated parameter would turn every frozen violation of that class into a "new" one. This matcher
 * compares violation lines with CONSTRUCTOR parameter lists and source line numbers removed, so the baseline survives
 * constructor changes while a genuinely new dependency still fails. Method signatures are kept: a call to
 * `Repo.find(Long)` replacing a frozen call to `Repo.find(String)` is a new dependency. Registered in archunit.properties.
 */
class SignatureInsensitiveLineMatcher : ViolationLineMatcher {
    override fun matches(lineFromFirstViolation: String, lineFromSecondViolation: String): Boolean =
        normalize(lineFromFirstViolation) == normalize(lineFromSecondViolation)

    private fun normalize(line: String): String = line
        .replace(PARAMETER_LIST, "(…)")
        .replace(SOURCE_LOCATION, "")
        .trim()

    private companion object {
        /** The parameter list of a constructor, `<init>(a.b.C, d.e.F)`, in an ArchUnit member description. */
        val PARAMETER_LIST = Regex("""(?<=<init>)\([^()<>]*\)""")
        /** `(AppointmentController.kt:57)` at the end of a line. */
        val SOURCE_LOCATION = Regex("""\s*\([\w.$]+\.kt:\d+\)""")
    }
}
