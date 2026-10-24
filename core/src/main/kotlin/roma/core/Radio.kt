package roma.core

class RadioSettings(
    /** "close", "medium" or "free". */
    val leash: String = "medium",
    val preferAudio: Boolean = true,
    val hideVersions: Boolean = true,
    val skipBanned: Boolean = true,
)

class Bans(val songs: Set<String> = emptySet(), val artists: Set<String> = emptySet()) {
    fun banned(s: Song) = s.id in songs || s.artists.any { it.id != null && it.id in artists }
}

private val VERSIONS = Regex("""(?i)\b(live|sped[ -]?up|speed[ -]?up|nightcore|slowed|reverb)\b|ライブ|现场|라이브""")

private fun key(s: Song) = Romanizer.stripMarks(s.title.original).lowercase().replace(Regex("""\s*[(\[].*?[)\]]"""), "").trim() +
    "|" + (s.artists.firstOrNull()?.name?.original?.lowercase() ?: "")

/**
 * Picks which radio candidates to append. [already] holds the songs in the queue (all sections), [related] the
 * artist IDs of the seed's related artists. Each kept song gets a reason when we can tell one.
 */
fun filterRadio(
    candidates: List<Song>,
    seed: Song,
    related: Set<String>,
    already: List<Song>,
    bans: Bans,
    settings: RadioSettings,
): List<Song> {
    val seedArtists = seed.artists.mapNotNull { it.id }.toSet()
    val seen = already.map { it.id }.toMutableSet()
    val seenKeys = already.map(::key).toMutableSet()
    val audioKeys = candidates.filter { !it.video }.map(::key).toSet()
    val seedIsVersion = VERSIONS.containsMatchIn(seed.title.original)
    val out = ArrayList<Song>()
    var onLeash = 0
    var offLeash = 0
    for (s in candidates) {
        if (s.id in seen) continue
        if (settings.skipBanned && bans.banned(s)) continue
        if (settings.hideVersions && !seedIsVersion && (VERSIONS.containsMatchIn(s.title.original) || VERSIONS.containsMatchIn(s.title.latin))) continue
        val k = key(s)
        if (k in seenKeys) continue // the same song again, e.g. its video next to its audio track
        if (settings.preferAudio && s.video && k in audioKeys) continue
        val ids = s.artists.mapNotNull { it.id }
        val reason = when {
            ids.any { it in seedArtists } -> "same artist"
            ids.any { it in related } -> "related artist"
            else -> null
        }
        when (settings.leash) {
            "close" -> if (reason == null) continue
            // Medium: other artists are allowed, but never more of them than of the seed's own circle.
            "medium" -> if (reason == null) { if (offLeash >= onLeash) continue else offLeash++ } else onLeash++
        }
        seen.add(s.id)
        seenKeys.add(k)
        out.add(s.withReason(reason))
    }
    return out
}
