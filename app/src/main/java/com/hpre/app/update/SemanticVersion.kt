package com.hpre.app.update

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val installedPattern = Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")
        private val tagPattern = Regex("v(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")

        fun parseInstalled(value: String): SemanticVersion? = parse(value, installedPattern)

        fun parseTag(value: String): SemanticVersion? = parse(value, tagPattern)

        private fun parse(value: String, pattern: Regex): SemanticVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val components = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return SemanticVersion(components[0], components[1], components[2])
        }
    }
}
