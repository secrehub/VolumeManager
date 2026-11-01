package moe.chensi.volume.engine

/**
 * Parses `dumpsys media.audio_policy` and `dumpsys media.audio_flinger` output to find
 * which audio session belongs to which app. These dumps are readable by the `shell` user
 * (Shizuku) on Android 10+, unlike the full `AudioPlaybackConfiguration` list which needs
 * `MODIFY_AUDIO_ROUTING` (only granted to shell since Android 13).
 *
 * Pure Kotlin on purpose, so it can be unit tested without a device.
 */
object AudioDumpParser {
    data class PolicyClient(
        val portId: Int,
        val sessionId: Int,
        val uid: Int,
        /** null if the dump did not include a state line */
        val active: Boolean?,
        /** Stream type, null for record clients or unknown */
        val stream: Int?,
        val isRecord: Boolean
    )

    data class FlingerTrack(
        val sessionId: Int,
        val pid: Int,
        val active: Boolean
    )

    // AOSP: "- Port Id: %d Session Id: %d UID: %d"
    private val policyClientRegex =
        Regex("""Port Id:\s*(-?\d+)\s+Session Id:\s*(-?\d+)\s+UID:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val stateRegex = Regex("""^\s*-?\s*State:\s*(\w+)""", RegexOption.IGNORE_CASE)
    private val streamRegex = Regex("""^\s*-?\s*Stream:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val sourceRegex = Regex("""^\s*-?\s*Source:\s*""", RegexOption.IGNORE_CASE)

    /**
     * Parse clients from `dumpsys media.audio_policy`.
     * Returns playback clients only (record clients are dropped) with sessionId > 0.
     */
    fun parsePolicyClients(dump: String): List<PolicyClient> {
        val result = mutableListOf<PolicyClient>()
        val lines = dump.lines()
        var i = 0
        while (i < lines.size) {
            val match = policyClientRegex.find(lines[i])
            if (match == null) {
                i++
                continue
            }

            val portId = match.groupValues[1].toInt()
            val sessionId = match.groupValues[2].toInt()
            val uid = match.groupValues[3].toInt()
            var active: Boolean? = null
            var stream: Int? = null
            var isRecord = false

            // Look at the following detail lines until the next client entry
            var j = i + 1
            while (j < lines.size && j <= i + 12) {
                val line = lines[j]
                if (policyClientRegex.containsMatchIn(line)) break
                stateRegex.find(line)?.let {
                    active = it.groupValues[1].equals("active", ignoreCase = true)
                }
                streamRegex.find(line)?.let { stream = it.groupValues[1].toIntOrNull() }
                if (sourceRegex.containsMatchIn(line)) isRecord = true
                j++
            }

            if (!isRecord && sessionId > 0) {
                result.add(PolicyClient(portId, sessionId, uid, active, stream, false))
            }
            i = j
        }

        // The same client can be listed under several outputs; merge by port id
        return result
            .groupBy { it.portId to it.sessionId }
            .map { (_, list) ->
                list.first().copy(active = when {
                    list.any { it.active == true } -> true
                    list.any { it.active == false } -> false
                    else -> null
                })
            }
    }

    // AOSP playback track row: "[F%d| ] %c %6d %7s %6u %7u %7u %2s 0x%03X ..."
    //                           type  Id  Active Client Session PortId S Flags
    private val flingerTrackRegex =
        Regex("""^\s*(?:F\d+\s+)?[ SP?]?\s*(\d+)\s+(yes|no)\s+(\d+)\s+(\d+)\s+(-?\d+)\s""")

    /** Parse playback tracks from `dumpsys media.audio_flinger`. */
    fun parseFlingerTracks(dump: String): List<FlingerTrack> {
        val result = linkedMapOf<Pair<Int, Int>, FlingerTrack>()
        var inRecordSection = false
        for (line in dump.lines()) {
            val trimmed = line.trim()
            // Record threads list their tracks too; skip them
            if (trimmed.startsWith("Input thread", ignoreCase = true) ||
                trimmed.startsWith("RecordThread", ignoreCase = true)
            ) {
                inRecordSection = true
            } else if (trimmed.startsWith("Output thread", ignoreCase = true) ||
                trimmed.startsWith("MixerThread", ignoreCase = true) ||
                trimmed.startsWith("DirectOutputThread", ignoreCase = true) ||
                trimmed.startsWith("OffloadThread", ignoreCase = true) ||
                trimmed.startsWith("MmapPlaybackThread", ignoreCase = true)
            ) {
                inRecordSection = false
            }
            if (inRecordSection) continue

            val match = flingerTrackRegex.find(line) ?: continue
            val active = match.groupValues[2] == "yes"
            val pid = match.groupValues[3].toInt()
            val session = match.groupValues[4].toInt()
            if (session <= 0 || pid <= 0) continue

            val key = session to pid
            val existing = result[key]
            result[key] = FlingerTrack(session, pid, active || (existing?.active == true))
        }
        return result.values.toList()
    }
}
