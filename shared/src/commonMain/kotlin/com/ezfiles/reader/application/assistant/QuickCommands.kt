package com.ezfiles.reader.application.assistant

data class QuickCommand(val operation: String, val value: String? = null)

/**
 * Exact screen commands ("next page", "zoom in", "select B3") recognised by plain matching, so they
 * act at once instead of waiting seconds for the model. Only operations the viewer on screen
 * supports are matched; anything else goes to the model.
 */
object QuickCommands {
    private val filler = Regex("""^(hey |ok |okay )?(fily,? )?(please |can you |could you |would you |now )*|( please| now| for me)+$""")

    fun parse(request: String, operations: Collection<String>, pageCount: Int? = null): QuickCommand? {
        if (operations.isEmpty()) return null
        val text = request.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex(" +"), " ").trim().replace(filler, "").trim()
        fun can(op: String) = op in operations
        fun pick(vararg ops: String) = ops.firstOrNull(::can)?.let { QuickCommand(it) }

        Regex("""^(go to |jump to |turn to |show |open )?(the )?(page|slide) (\w+)$""").find(text)?.let { m ->
            if (can("go_to_page")) return QuickCommand("go_to_page", m.groupValues[4])
        }
        Regex("""^(select|tap|click|click on|tap on|go to|show|open) (cell )?([a-z]{1,3}) ?(\w+)$""").find(text)?.let { m ->
            val row = m.groupValues[4]
            if (can("select_cell") && (m.groupValues[2].isNotEmpty() || row.any(Char::isDigit))) {
                // Spaced, so a spoken row ("C three") stays separate from the column letters.
                return QuickCommand("select_cell", m.groupValues[3] + " " + row)
            }
        }
        Regex("""^(skip|jump|seek|go) (ahead )?to (\w+) seconds?$""").find(text)?.let { m ->
            if (can("seek")) return QuickCommand("seek", m.groupValues[3])
        }
        return when {
            text.matches(Regex("""^(go to )?(the )?(last|final) (page|slide)$""")) && can("go_to_page") && pageCount != null ->
                QuickCommand("go_to_page", pageCount.toString())
            text.matches(Regex("""^(go to )?(the )?first (page|slide)$""")) && can("go_to_page") -> QuickCommand("go_to_page", "1")
            text.matches(Regex("""^(go to |show |show me )?(the )?next (page|slide)$|^(turn the )?page$|^page down$""")) -> pick("next_page")
            text.matches(Regex("""^(go to |show |go back to )?(the )?previous (page|slide)$|^(go )?back (a|one) (page|slide)$|^page up$""")) -> pick("previous_page")
            text.matches(Regex("""^(show |show me )?(the )?next (photo|picture|image|pic)$""")) -> pick("next_photo")
            text.matches(Regex("""^(show |show me )?(the )?previous (photo|picture|image|pic)$""")) -> pick("previous_photo")
            text.matches(Regex("""^(go )?(next|forward)( one)?$""")) -> pick("next_page", "next_photo")
            text.matches(Regex("""^(go )?(back|previous)( one)?$""")) -> pick("previous_page", "previous_photo")
            text.matches(Regex("""^(next|previous) (sheet|tab)$""")) -> pick(if (text.startsWith("next")) "next_sheet" else "previous_sheet")
            text.matches(Regex("""^zoom in( more| a (little|bit))?$|^(make it )?bigger$|^enlarge( it)?$""")) -> pick("zoom_in")
            text.matches(Regex("""^zoom out( more| a (little|bit))?$|^(make it )?smaller$""")) -> pick("zoom_out")
            text.matches(Regex("""^scroll down( more)?$|^(go )?down$""")) -> pick("scroll_down")
            text.matches(Regex("""^scroll up( more)?$|^(go )?up$""")) -> pick("scroll_up")
            text.matches(Regex("""^(scroll|go|jump) to (the )?(top|start|beginning)$""")) -> pick("scroll_top")
            text.matches(Regex("""^(scroll|go|jump) to (the )?(bottom|end)$""")) -> pick("scroll_bottom")
            text.matches(Regex("""^rotate( it| this| the (photo|picture|image))?( clockwise)?$""")) -> pick("rotate_photo")
            text.matches(Regex("""^(play|resume|continue)( it| this| the (video|song|audio|track))?$""")) -> pick("play")
            text.matches(Regex("""^(pause|stop)( it| this| the (video|song|audio|track)| playback)?$""")) -> pick("pause")
            else -> null
        }
    }

    /** What Fily says after a quick command, from the controlReader result. */
    fun reply(command: QuickCommand, page: Int?, pageCount: Int?, sheet: String?, cell: String?, failure: String?): String {
        if (failure != null) return when (failure) {
            "at_last_page" -> "That's the last page."
            "at_first_page" -> "That's the first page."
            "at_last_photo" -> "That's the last photo."
            "at_first_photo" -> "That's the first photo."
            "at_last_sheet" -> "That's the last sheet."
            "at_first_sheet" -> "That's the first sheet."
            "out_of_range" -> if (pageCount != null && command.operation == "go_to_page") "There are only $pageCount pages." else "That isn't in this file."
            "at_limit" -> if (command.operation == "zoom_in") "That's as close as it zooms." else "That's as far as it zooms out."
            "still_loading" -> "That part is still loading. Try again in a moment."
            "missing_page", "missing_cell", "missing_time" -> "I didn't catch the number. Try again?"
            "not_ready" -> "It isn't ready to play yet."
            else -> "I can't do that here."
        }
        val where = when {
            page != null && pageCount != null -> "Page $page of $pageCount."
            else -> null
        }
        return when (command.operation) {
            "next_page", "previous_page", "go_to_page", "scroll_top", "scroll_bottom" -> where ?: "Done."
            "next_photo", "previous_photo" -> if (page != null && pageCount != null) "Photo $page of $pageCount." else "Done."
            "zoom_in" -> "Zoomed in."
            "zoom_out" -> "Zoomed out."
            "scroll_down" -> "Scrolled down."
            "scroll_up" -> "Scrolled up."
            "rotate_photo" -> "Rotated."
            "play" -> "Playing."
            "pause" -> "Paused."
            "seek" -> "Jumped to ${command.value} seconds."
            "select_cell" -> "Selected ${cell ?: command.value?.uppercase()}."
            "next_sheet", "previous_sheet" -> sheet?.let { "Now on $it." } ?: "Done."
            else -> "Done."
        }
    }
}
