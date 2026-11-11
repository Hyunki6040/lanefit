package app.lanefit.engine

enum class RiskLevel(val ko: String) { LOW("낮음"), MID("보통"), HIGH("높음") }

data class RiskInput(
    val signalLeadMs: Long?,
    val speedKmh: Double,
    val peakSteeringRateDegS: Double,
    val hardBrakeAfter: Boolean,
    /** Other lane changes in the previous 60 s. */
    val recentChanges: Int,
    val highway: Boolean = true,
)

data class RiskResult(
    val score: Int,
    val level: RiskLevel,
    val leadDistanceM: Double?,
    val coaching: String,
    /** false when the coaching line is praise, so it is shown but not spoken. */
    val needsCoaching: Boolean,
)

/**
 * 0 = clean, 100 = worst. The signal distance rule is 도로교통법 시행령 별표 2:
 * signal from 100 m before the change on highways, 30 m on other roads.
 *
 * ponytail: weights and the steering-rate band are first-pass design values, not fitted to data.
 * Tune against pilot drives; add a collision-warning term when a public ADAS feed exists.
 */
fun scoreLaneChange(i: RiskInput): RiskResult {
    val requiredM = if (i.highway) 100.0 else 30.0
    val leadM = i.signalLeadMs?.let { i.speedKmh / 3.6 * it / 1000.0 }
    val parts = listOf(
        "signal" to if (leadM == null) 40.0 else 30.0 * (1 - leadM / requiredM).coerceIn(0.0, 1.0),
        "quick" to if (i.signalLeadMs != null && i.signalLeadMs < 1000) 10.0 else 0.0,
        "steer" to 20.0 * ((i.peakSteeringRateDegS - 10.0) / 20.0).coerceIn(0.0, 1.0),
        "brake" to if (i.hardBrakeAfter) 15.0 else 0.0,
        "repeat" to (10.0 * i.recentChanges).coerceIn(0.0, 20.0),
    )
    val score = parts.sumOf { it.second }.toInt().coerceIn(0, 100)
    val (worst, points) = parts.maxBy { it.second }
    val coaching = when {
        points < 5.0 -> "부드럽게 바꿨어요"
        worst == "signal" && leadM == null -> "방향지시등을 켜고 바꿔보세요"
        worst == "signal" || worst == "quick" -> "신호를 조금 더 일찍 켜보세요"
        worst == "steer" -> "핸들을 조금 더 천천히 돌려보세요"
        worst == "brake" -> "변경 직후 급제동이 있었어요"
        else -> "연달아 바꿨어요, 한 차선에 조금 더 머물러 보세요"
    }
    val level = when {
        score < 30 -> RiskLevel.LOW
        score < 60 -> RiskLevel.MID
        else -> RiskLevel.HIGH
    }
    return RiskResult(score, level, leadM, coaching, needsCoaching = points >= 5.0)
}
