package com.example.pokemongrader.data

data class TcgDexLanguage(val code: String, val label: String)

object TcgDexLocale {
    val languages = listOf(
        TcgDexLanguage("en", "English"),
        TcgDexLanguage("ja", "Japanese"),
        TcgDexLanguage("pt-br", "Português (Brasil)"),
        TcgDexLanguage("pt-pt", "Português (Portugal)"),
        TcgDexLanguage("pt", "Português (variante não especificada)"),
        TcgDexLanguage("fr", "Français"),
        TcgDexLanguage("de", "Deutsch"),
        TcgDexLanguage("es", "Español"),
        TcgDexLanguage("it", "Italiano"),
        TcgDexLanguage("ko", "한국어"),
        TcgDexLanguage("zh-tw", "中文（繁體）"),
        TcgDexLanguage("zh-cn", "中文（简体）"),
        TcgDexLanguage("id", "Bahasa Indonesia"),
        TcgDexLanguage("th", "ไทย"),
        TcgDexLanguage("nl", "Nederlands"),
        TcgDexLanguage("pl", "Polski"),
        TcgDexLanguage("ru", "Русский")
    )

    fun isSupported(code: String): Boolean = languages.any { it.code == code }

    fun requireSupported(code: String): String {
        require(isSupported(code)) { "Unsupported TCGdex locale" }
        return code
    }

    fun label(code: String): String = languages.firstOrNull { it.code == code }?.label ?: code
}
