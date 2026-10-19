package com.example.mimic.update

/**
 * A release version like `v4.1.0` or `4.2.0-beta.1`, compared numerically part by part
 * (1.0.9 < 1.0.10, 1.1.0 > 1.0.99 — never as strings). A pre-release suffix sorts below the same
 * version without one (4.2.0-beta.1 < 4.2.0). Ported from ENAGELYUCA.
 */
data class AppVersion(val parts: List<Int>, val preRelease: String?) : Comparable<AppVersion> {

    val isPreRelease: Boolean get() = preRelease != null

    override fun compareTo(other: AppVersion): Int {
        val size = maxOf(parts.size, other.parts.size)
        for (i in 0 until size) {
            val diff = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> comparePreRelease(preRelease, other.preRelease)
        }
    }

    override fun toString(): String = parts.joinToString(".") + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d+(?:\.\d+){0,3})(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** Parses "v1.2.3", "1.2", "1.2.3-rc.1"; returns null for anything else. */
        fun parse(text: String?): AppVersion? {
            val match = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            return AppVersion(numbers, match.groupValues[2].ifEmpty { null })
        }

        /**
         * Must match versionCodeFor in app/build.gradle.kts: 4.1.2 → 400_100_299; pre-releases
         * get 1..98 instead of 99 (alpha.N = N, beta.N = 33+N, rc.N = 66+N), so each one has its
         * own code below the final release.
         */
        fun versionCodeFor(name: String): Int {
            val v = parse(name) ?: return 1
            val (major, minor, patch) = List(3) { v.parts.getOrElse(it) { 0 }.coerceIn(0, 999) }
            val pre = v.preRelease?.lowercase()
            val n = pre?.substringAfterLast('.')?.toIntOrNull()?.coerceIn(1, 32) ?: 1
            val suffix = when {
                pre == null -> 99
                pre.startsWith("alpha") -> n
                pre.startsWith("beta") -> 33 + n
                pre.startsWith("rc") -> 66 + n
                else -> n
            }
            return (major * 1_000_000 + minor * 1_000 + patch) * 100 + suffix
        }

        private fun comparePreRelease(a: String, b: String): Int {
            val x = a.split('.')
            val y = b.split('.')
            for (i in 0 until maxOf(x.size, y.size)) {
                val p = x.getOrNull(i) ?: return -1
                val q = y.getOrNull(i) ?: return 1
                val pn = p.toIntOrNull()
                val qn = q.toIntOrNull()
                val diff = when {
                    pn != null && qn != null -> pn.compareTo(qn)
                    pn != null -> -1
                    qn != null -> 1
                    else -> p.compareTo(q)
                }
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
