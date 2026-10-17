package com.example.mimic.data

import com.example.mimic.ui.designsystem.ThemeColors
import com.example.mimic.ui.designsystem.ThemeDefinition

/**
 * Versioned JSON for custom themes, hand-written in pure Kotlin so export/import and its
 * validation are testable on the JVM and carry no parser dependency. Colors are "#AARRGGBB".
 *
 * The parser is intentionally strict: it accepts only the known flat fields, rejects
 * anything missing or malformed, and never evaluates code — an imported theme is pure data.
 */
object ThemeSerialization {

    const val VERSION = 1
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
            append(colorLine("textSecondary", c.textSecondary)).append("\n")
            append("}\n")
        }
    }

    fun parse(json: String): Result<ThemeDefinition> = runCatching {
        if (json.length > 8_192) error("Theme file too large")
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
        )
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
}
