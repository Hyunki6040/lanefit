package app.lanefit.engine

// Pure logic behind the navigation and voice adapters, so it runs in JVM tests without a car.

// ponytail: the SDK does not document the unit of TBTInfo.distance; metres assumed. If it is km, every
// manoeuvre reads as "near" and the app only gets quieter on that side. Confirm on a car with a route.
private const val ROUTE_SIGNAL_M = 1_000.0
private const val RAMP_NEAR_M = 300.0

/** Which way a navigation manoeuvre goes, from its TurnType name (TURN_RIGHT, TURN_EXIT_IC_L, ...). */
fun sideOfTurn(turn: String): Side? = when {
    turn == "TURN_RIGHT" || turn.endsWith("_R") -> Side.RIGHT
    turn == "TURN_LEFT" || turn.endsWith("_L") -> Side.LEFT
    else -> null
}

/** Follows the next manoeuvre of the active route. */
class RouteWatch {
    /** The side the route itself needs soon. A turn signal to that side is not a question. */
    var signal: Side? = null
        private set

    /** true once a motorway entry has been passed, false once an exit has; null until the route says either, and again once it ends. */
    var motorway: Boolean? = null
        private set
    private var near: String? = null

    /**
     * Feed the next manoeuvre and its distance. Returns true when a motorway entry that was close has just
     * been passed: the car has merged, which is the one moment the lane is known without asking.
     */
    fun onNext(turn: String?, distanceM: Double): Boolean {
        if (turn == null || !distanceM.isFinite() || distanceM < 0) {
            clear()
            return false
        }
        val passed = near?.takeIf { it != turn }
        near = turn.takeIf { distanceM <= RAMP_NEAR_M }
        signal = if (distanceM <= ROUTE_SIGNAL_M) sideOfTurn(turn) else null
        if (passed?.startsWith("TURN_EXIT_IC") == true) motorway = false
        val merged = passed?.startsWith("TURN_ENTER_IC") == true
        if (merged) motorway = true
        return merged
    }

    /** The route ended: it no longer says which road this is, so the caller falls back to speed. */
    fun clear() {
        signal = null
        near = null
        motorway = null
    }
}

sealed interface Ask {
    /** "지금 바꿀까?" (side == null) or "왼쪽으로 갈까?" */
    data class Change(val side: Side?) : Ask

    /** "지금 3차로야": the driver states the lane. */
    data class Lane(val lane: Int) : Ask
}

private val CHANGE_WORDS = listOf("바꿀까", "바꿔도", "옮길까", "옮겨도")

// Everyday words ("집에 갈까"): a lane question only when the sentence also names a side or a lane.
private val GO_WORDS = listOf("갈까", "가도돼", "빠를까")
private val LANE_WORDS = listOf("왼", "오른쪽", "차선", "차로")
private val NUMBERS = mapOf("일" to 1, "한" to 1, "이" to 2, "두" to 2, "삼" to 3, "세" to 3, "사" to 4, "네" to 4, "오" to 5, "다섯" to 5)

// The ending is required: "이 차선" (this lane) sounds like "2차선", and only a statement may move the estimate.
private val LANE_STATEMENT = Regex("([1-9]|일|이|삼|사|오|한|두|세|네|다섯)(차로|차선)(이?야|이?에요|예요|입니다|에있어(?!야))")

/** What a recognised sentence asks for, or null when it is not for this app. */
fun parseAsk(text: String): Ask? {
    val t = text.filterNot { it.isWhitespace() }
    if (CHANGE_WORDS.any { it in t } || (GO_WORDS.any { it in t } && LANE_WORDS.any { it in t })) {
        return Ask.Change(
            when {
                "왼" in t -> Side.LEFT
                "오른" in t -> Side.RIGHT
                else -> null
            },
        )
    }
    val number = LANE_STATEMENT.find(t)?.groupValues?.get(1) ?: return null
    return (number.toIntOrNull() ?: NUMBERS[number])?.let(Ask::Lane)
}
