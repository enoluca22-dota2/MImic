package com.example.mimic.data

import com.example.mimic.ui.designsystem.ThemeColors
import com.example.mimic.ui.designsystem.ThemeDefinition
import com.example.mimic.ui.designsystem.ThemeStyle

/**
 * Versioned JSON for custom themes, hand-written in pure Kotlin so export/import and its
 * validation are testable on the JVM and carry no parser dependency. Colors are "#AARRGGBB".
 *
 * Schema history:
 *  - v1 (2.2): id, name, isDark + seven color tokens.
 *  - v2 (2.9): adds an optional "style" object ([ThemeStyle]). A v1 file — or a v2 file without
 *    "style" — imports with the default flat style, so older exports keep working unchanged.
 *
 * The parser is intentionally strict: it accepts only the known flat fields, rejects anything
 * missing or malformed, clamps every style value into range, ignores unknown keys and never
 * evaluates code — an imported theme is pure data.
 */
object ThemeSerialization {

    const val VERSION = 2
    private const val MAX_BYTES = 16_384
    private const val MAX_NAME = 60
    private val ID_PATTERN = Regex("^[A-Za-z0-9_\\-]{1,64}$")

    fun toJson(theme: ThemeDefinition): String {
        val c = theme.colors
        return buildString {
            append("{\n")
            append("  \"mimicThemeVersion\": ").append(VERSION).append(",\n")
            append("  \"id\": \"").append(escape(theme.id)).append("\",\n")
            append("  \"name\": \"").append(escape(theme.name)).append("\",\n")
            append("  \"isDark\": ").append(c.isDark).append(",\n")
            append(colorLine("primary", c.primary)).append(",\n")
            append(colorLine("secondary", c.secondary)).append(",\n")
            append(colorLine("background", c.background)).append(",\n")
            append(colorLine("surface", c.surface)).append(",\n")
            append(colorLine("surfaceElevated", c.surfaceElevated)).append(",\n")
            append(colorLine("textPrimary", c.textPrimary)).append(",\n")
            append(colorLine("textSecondary", c.textSecondary)).append(",\n")
            append("  \"style\": ").append(styleToJson(theme.style, indent = "  ")).append("\n")
            append("}\n")
        }
    }

    fun parse(json: String): Result<ThemeDefinition> = runCatching {
        if (json.length > MAX_BYTES) error("Theme file too large")
        val version = Regex("\"mimicThemeVersion\"\\s*:\\s*(\\d{1,3})").find(json)
            ?.groupValues?.get(1)?.toInt() ?: 1
        if (version > VERSION) error("This theme was made with a newer MImic (v$version)")
        val id = requireString(json, "id").takeIf { ID_PATTERN.matches(it) }
            ?: error("Invalid or missing id")
        val name = requireString(json, "name").trim()
        require(name.isNotEmpty() && name.length <= MAX_NAME) { "Invalid name" }
        val isDark = requireBool(json, "isDark")
        ThemeDefinition(
            id = id,
            name = name,
            builtIn = false,
            colors = ThemeColors(
                primary = requireColor(json, "primary"),
                secondary = requireColor(json, "secondary"),
                background = requireColor(json, "background"),
                surface = requireColor(json, "surface"),
                surfaceElevated = requireColor(json, "surfaceElevated"),
                textPrimary = requireColor(json, "textPrimary"),
                textSecondary = requireColor(json, "textSecondary"),
                isDark = isDark,
            ),
            style = if (version >= 2) parseStyle(styleBlock(json)) else ThemeStyle(),
        )
    }

    /**
     * Serializes a [ThemeStyle] as a flat JSON object. Also used by Room to persist the style of
     * custom themes in one column, so the database and the export format can never drift apart.
     */
    fun styleToJson(style: ThemeStyle, indent: String = ""): String {
        val st = style.sanitized()
        val fields = listOf(
            "glass" to st.glass.toString(),
            "glassOpacity" to st.glassOpacity.toString(),
            "blurRadius" to st.blurRadius.toString(),
            "refraction" to st.refraction.toString(),
            "edgeHighlight" to st.edgeHighlight.toString(),
            "shadow" to st.shadow.toString(),
            "glassTint" to "\"${toHex(st.glassTint)}\"",
            "glassTone" to "\"${st.glassTone.name}\"",
            "accentColor" to "\"${toHex(st.accentColor)}\"",
            "gradientBackground" to st.gradientBackground.toString(),
            "gradientColor" to "\"${toHex(st.gradientColor)}\"",
            "albumAdaptive" to st.albumAdaptive.toString(),
            "cornerRadius" to st.cornerRadius.toString(),
            "navCornerRadius" to st.navCornerRadius.toString(),
            "controlShape" to "\"${st.controlShape.name}\"",
            "pillNav" to st.pillNav.toString(),
            "navIndicator" to "\"${st.navIndicator.name}\"",
            "fontFamily" to "\"${st.fontFamily.name}\"",
            "fontScale" to st.fontScale.toString(),
            "headerWeight" to st.headerWeight.toString(),
            "titleWeight" to st.titleWeight.toString(),
            "artistWeight" to st.artistWeight.toString(),
            "motion" to st.motion.toString(),
            "animationScale" to st.animationScale.toString(),
            "transitionSpeed" to st.transitionSpeed.toString(),
        )
        val inner = "$indent  "
        return fields.joinToString(",\n", prefix = "{\n", postfix = "\n$indent}") { (k, v) -> "$inner\"$k\": $v" }
    }

    /**
     * Lenient-per-field style parser: each missing or malformed value falls back to its default
     * (a partial style from a hand-edited file still imports), then everything is clamped.
     */
    fun parseStyle(json: String?): ThemeStyle {
        if (json.isNullOrBlank()) return ThemeStyle()
        val d = ThemeStyle()
        return ThemeStyle(
            glass = optBool(json, "glass") ?: d.glass,
            glassOpacity = optFloat(json, "glassOpacity") ?: d.glassOpacity,
            blurRadius = optInt(json, "blurRadius") ?: d.blurRadius,
            refraction = optFloat(json, "refraction") ?: d.refraction,
            edgeHighlight = optFloat(json, "edgeHighlight") ?: d.edgeHighlight,
            shadow = optFloat(json, "shadow") ?: d.shadow,
            glassTint = optColor(json, "glassTint") ?: d.glassTint,
            glassTone = optEnum(json, "glassTone", d.glassTone),
            accentColor = optColor(json, "accentColor") ?: d.accentColor,
            gradientBackground = optBool(json, "gradientBackground") ?: d.gradientBackground,
            gradientColor = optColor(json, "gradientColor") ?: d.gradientColor,
            albumAdaptive = optBool(json, "albumAdaptive") ?: d.albumAdaptive,
            cornerRadius = optInt(json, "cornerRadius") ?: d.cornerRadius,
            navCornerRadius = optInt(json, "navCornerRadius") ?: d.navCornerRadius,
            controlShape = optEnum(json, "controlShape", d.controlShape),
            pillNav = optBool(json, "pillNav") ?: d.pillNav,
            navIndicator = optEnum(json, "navIndicator", d.navIndicator),
            fontFamily = optEnum(json, "fontFamily", d.fontFamily),
            fontScale = optFloat(json, "fontScale") ?: d.fontScale,
            headerWeight = optInt(json, "headerWeight") ?: d.headerWeight,
            titleWeight = optInt(json, "titleWeight") ?: d.titleWeight,
            artistWeight = optInt(json, "artistWeight") ?: d.artistWeight,
            motion = optBool(json, "motion") ?: d.motion,
            animationScale = optFloat(json, "animationScale") ?: d.animationScale,
            transitionSpeed = optFloat(json, "transitionSpeed") ?: d.transitionSpeed,
        ).sanitized()
    }

    // ---- helpers ----

    private fun colorLine(key: String, argb: Long) =
        "  \"$key\": \"${toHex(argb)}\""

    fun toHex(argb: Long): String = "#%08X".format(argb and 0xFFFFFFFFL)

    /** Accepts "#AARRGGBB" or "#RRGGBB" (assumed opaque). Returns 0xAARRGGBB. */
    fun parseHex(raw: String): Long {
        val hex = raw.removePrefix("#")
        require(hex.length == 6 || hex.length == 8) { "Bad color: $raw" }
        require(hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Bad color: $raw" }
        val value = hex.toLong(16)
        return if (hex.length == 6) 0xFF000000L or value else value
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The text of the "style" object (it is flat, so the first closing brace ends it). */
    private fun styleBlock(json: String): String? =
        Regex("\"style\"\\s*:\\s*\\{([^{}]*)\\}").find(json)?.groupValues?.get(1)

    private fun requireString(json: String, key: String): String {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
            ?: error("Missing field: $key")
        return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun requireBool(json: String, key: String): Boolean {
        val m = Regex("\"$key\"\\s*:\\s*(true|false)").find(json) ?: error("Missing field: $key")
        return m.groupValues[1].toBoolean()
    }

    private fun requireColor(json: String, key: String): Long {
        val m = Regex("\"$key\"\\s*:\\s*\"(#?[0-9A-Fa-f]{6,8})\"").find(json)
            ?: error("Missing/invalid color: $key")
        return parseHex(m.groupValues[1])
    }

    private fun optBool(json: String, key: String): Boolean? =
        Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBoolean()

    private fun optFloat(json: String, key: String): Float? =
        Regex("\"$key\"\\s*:\\s*(-?\\d{1,6}(?:\\.\\d{1,8})?)").find(json)?.groupValues?.get(1)?.toFloatOrNull()

    private fun optInt(json: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(-?\\d{1,6})(?![.\\d])").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun optColor(json: String, key: String): Long? =
        Regex("\"$key\"\\s*:\\s*\"(#?[0-9A-Fa-f]{8}|#?[0-9A-Fa-f]{6})\"").find(json)
            ?.groupValues?.get(1)?.let { runCatching { parseHex(it) }.getOrNull() }

    private inline fun <reified T : Enum<T>> optEnum(json: String, key: String, fallback: T): T {
        val raw = Regex("\"$key\"\\s*:\\s*\"([A-Z_]{1,24})\"").find(json)?.groupValues?.get(1)
        return raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
    }
}
