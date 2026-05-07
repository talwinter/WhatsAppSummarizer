package com.example.whatsapp_summarizer.util

object ChatNameNormalizer {
    
    // Regex to strip WhatsApp message count suffixes like "(2 messages)", "(5 messages)"
    private val MESSAGE_COUNT_REGEX = Regex("\\s*\\(\\d+\\s+(new\\s+)?messages?\\)\\s*$")
    
    fun normalize(name: String): String {
        if (name.isBlank()) return "Unknown"
        
        return name
            // Step 1: Trim obvious whitespace
            .trim()
            // Step 2: Strip WhatsApp message count suffixes like "(2 messages)", "(5 messages)"
            .replace(MESSAGE_COUNT_REGEX, "")
            // Step 3: Replace ALL Unicode control, format, and space characters with regular space
            // \p{Cc} = Control characters (0x00-0x1F, 0x7F-0x9F)
            // \p{Cf} = Format characters (includes zero-width chars)
            // \p{Zs} = Space separators (includes non-breaking space, narrow no-break space, etc.)
            .replace(Regex("[\\p{Cc}\\p{Cf}\\p{Zs}]+"), " ")
            // Step 4: Collapse multiple regular spaces
            .replace(Regex(" +"), " ")
            // Step 5: Trim again
            .trim()
            // Step 6: If empty after cleaning, return original with basic trim
            .ifEmpty { name.trim() }
    }
    
    fun isSimilar(name1: String, name2: String): Boolean {
        return normalize(name1) == normalize(name2)
    }
}
