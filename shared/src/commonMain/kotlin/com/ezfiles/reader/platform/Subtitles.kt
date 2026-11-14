package com.ezfiles.reader.platform

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** Sorted cues plus a max-end interval tree: long overlapping cues do not force a full scan per frame. */
class SubtitleDocument internal constructor(val name: String, cues: List<SubtitleCue>) {
    val cues: List<SubtitleCue> = cues.sortedBy { it.startMs }
    private val leafCount = generateSequence(1) { it * 2 }.first { it >= this.cues.size.coerceAtLeast(1) }
    private val endTree = LongArray(leafCount * 2) { Long.MIN_VALUE }.also { tree ->
        this.cues.forEachIndexed { index, cue -> tree[leafCount + index] = cue.endMs }
        for (index in leafCount - 1 downTo 1) tree[index] = maxOf(tree[index * 2], tree[index * 2 + 1])
    }

    fun textAt(positionMs: Long): String {
        if (positionMs < 0 || cues.isEmpty()) return ""
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startMs <= positionMs) low = middle + 1 else high = middle
        }
        val result = ArrayList<String>(4)
        fun collect(node: Int, first: Int, last: Int) {
            if (first >= low || endTree[node] <= positionMs || result.size >= 8) return
            if (last - first == 1) {
                if (first < cues.size) result += cues[first].text
            } else {
                val middle = (first + last) ushr 1
                collect(node * 2, first, middle)
                collect(node * 2 + 1, middle, last)
            }
        }
        collect(1, 0, leafCount)
        return result.distinct().joinToString("\n").take(2_000)
    }
}

/** Bounded plain-text captions. Formatting is reduced to text; no markup is executed. */
fun parseSubtitles(name: String, text: String): SubtitleDocument {
    require(text.length <= 1_048_576) { "Choose a subtitle file smaller than 1 MiB." }
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    require(!normalized.contains('\u0000')) { "This subtitle encoding is unsupported." }
    val cues = ArrayList<SubtitleCue>()
    for (block in normalized.split(Regex("\n[ \t]*\n"))) {
        val lines = block.lines()
        val first = lines.firstOrNull()?.trim().orEmpty()
        if (first == "NOTE" || first.startsWith("NOTE ") || first == "STYLE" || first == "REGION") continue
        val timingIndex = lines.indexOfFirst { "-->" in it }
        if (timingIndex < 0) continue
        val timings = lines[timingIndex].split("-->", limit = 2)
        val start = subtitleTimestamp(timings[0].trim()) ?: continue
        val end = subtitleTimestamp(timings[1].trim().substringBefore(' ')) ?: continue
        if (start >= end) continue
        val body = plainSubtitleText(lines.drop(timingIndex + 1).joinToString("\n"))
        if (body.isEmpty()) continue
        require(cues.size < 10_000) { "This subtitle file has too many cues." }
        cues += SubtitleCue(start, end, body)
    }
    require(cues.isNotEmpty()) { "No readable SRT or WebVTT captions were found." }
    return SubtitleDocument(name.take(160), cues)
}

internal fun subtitleTimestamp(value: String): Long? {
    val parts = value.replace(',', '.').split(':')
    if (parts.size !in 2..3) return null
    val hours = if (parts.size == 3) parts[0].toLongOrNull()?.takeIf { it in 0..999 } ?: return null else 0L
    val minutes = parts[parts.size - 2].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
    val seconds = parts.last().split('.')
    if (seconds.size != 2 || seconds[1].length != 3) return null
    val whole = seconds[0].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
    val fraction = seconds[1].toLongOrNull()?.takeIf { it in 0..999 } ?: return null
    return ((hours * 60 + minutes) * 60 + whole) * 1000 + fraction
}

internal fun plainSubtitleText(value: String): String = value.take(8_000)
    .replace(Regex("<[^>\n]{0,256}>"), "")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")
    .trim().take(1_000)

/** WebVTT in ISO BMFF can arrive as bounded vttc/payl boxes rather than a text file. */
internal fun embeddedWebVtt(data: ByteArray): String {
    if (data.size > 65_536) return ""
    val texts = ArrayList<String>()
    fun boxes(first: Int, end: Int, depth: Int) {
        if (depth > 1) return
        var offset = first
        while (offset + 8 <= end) {
            val size = ((data[offset].toInt() and 255) shl 24) or ((data[offset + 1].toInt() and 255) shl 16) or
                ((data[offset + 2].toInt() and 255) shl 8) or (data[offset + 3].toInt() and 255)
            if (size < 8 || size > end - offset) return
            val type = data.decodeToString(offset + 4, offset + 8)
            if (type == "payl") texts += data.decodeToString(offset + 8, offset + size)
            if (type == "vttc") boxes(offset + 8, offset + size, depth + 1)
            offset += size
        }
    }
    boxes(0, data.size, 0)
    if (texts.isEmpty() && data.any { it == 0.toByte() }) return ""
    return plainSubtitleText(if (texts.isEmpty()) data.decodeToString() else texts.joinToString("\n"))
}
