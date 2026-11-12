package com.example.monoplayer.data

import java.text.Normalizer
import java.util.Locale

/**
 * Normalizes artist names to absorb spelling/formatting variations (表記の揺れ吸収):
 * - NFKC Unicode Normalization (full-width ASCII/katakana to standard, e.g., "ｄｊ ｒｅｔｒｏ １９９９" -> "DJ RETRO 1999")
 * - Case-insensitive matching
 * - "Artist, The" -> "The Artist"
 * - Strips featuring / collaboration suffixes ("feat.", "ft.", "featuring", "with", "cv:") for canonical artist grouping while keeping raw artist metadata intact
 * - Absorbs punctuation/whitespace differences ("AC/DC" vs "AC / DC", "B'z" vs "B’z")
 * - Supports user-defined custom alias overrides
 */
object ArtistNormalizer {

    private val FEAT_REGEX = Regex(
        """(?i)\s*(?:\(|\[)?\s*(?:feat\.?|ft\.?|featuring|with|cv[:：])\s+.*$"""
    )
    private val THE_SUFFIX_REGEX = Regex("""(?i)^(.+?),\s*the$""")
    private val PUNCT_CLEAN_REGEX = Regex("""[\s・･/／\-_.,'’"“”&＆]+""")

    fun computeCanonicalKey(
        rawArtist: String,
        customAliasMap: Map<String, String> = emptyMap()
    ): String {
        val trimmed = rawArtist.trim()
        if (trimmed.isEmpty() || trimmed.equals("<unknown>", ignoreCase = true)) {
            return "unknown_artist"
        }

        // Check if user explicitly mapped this raw artist
        customAliasMap[trimmed]?.let { mapped ->
            return computeInternalKey(mapped)
        }

        return computeInternalKey(trimmed)
    }

    private fun computeInternalKey(input: String): String {
        // 1. NFKC Unicode normalization (converts full-width letters/numbers/spaces to half-width, half-width katakana to full-width)
        var s = Normalizer.normalize(input, Normalizer.Form.NFKC).trim()

        // 2. Strip featuring suffix
        s = FEAT_REGEX.replace(s, "").trim()

        // 3. Reorder "Artist, The" -> "The Artist"
        val theMatch = THE_SUFFIX_REGEX.find(s)
        if (theMatch != null) {
            s = "The " + theMatch.groupValues[1].trim()
        }

        // 4. Lowercase + remove leading "the " for canonical comparison key, and strip punctuation/spaces
        val lower = s.lowercase(Locale.ROOT).removePrefix("the ").trim()
        val key = PUNCT_CLEAN_REGEX.replace(lower, "")
        return key.ifEmpty { lower }
    }

    /**
     * Picks the cleanest, most standard display name from a collection of raw artist variations
     * (e.g., ["Mono Lab, The", "The Mono Lab"] -> "The Mono Lab",
     *  ["DJ RETRO 1999 feat. Synth", "ｄｊ ｒｅｔｒｏ １９９９"] -> "DJ RETRO 1999").
     */
    fun chooseCanonicalDisplayName(
        rawVariations: Collection<String>,
        customAliasMap: Map<String, String> = emptyMap()
    ): String {
        if (rawVariations.isEmpty()) return "Unknown Artist"

        // If any variation has a custom alias target, use that target display name
        for (raw in rawVariations) {
            customAliasMap[raw.trim()]?.let { return it }
        }

        val cleanedCandidates = rawVariations.map { raw ->
            var s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
            s = FEAT_REGEX.replace(s, "").trim()
            val theMatch = THE_SUFFIX_REGEX.find(s)
            if (theMatch != null) {
                s = "The " + theMatch.groupValues[1].trim()
            }
            s.replace(Regex("""\s+"""), " ")
        }.filter { it.isNotEmpty() }

        if (cleanedCandidates.isEmpty()) return rawVariations.first()

        // Prefer candidate with Mixed/Upper case over all-lowercase, and highest frequency
        return cleanedCandidates
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { entry ->
                    val name = entry.key
                    val hasUpper = name.any { it.isUpperCase() }
                    val notTrailingThe = !THE_SUFFIX_REGEX.matches(name)
                    (if (hasUpper) 10 else 0) + (if (notTrailingThe) 5 else 0) + entry.value
                }.thenBy { it.key.length }
            )
            .first()
            .key
    }
}
