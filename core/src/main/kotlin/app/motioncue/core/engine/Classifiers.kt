package app.motioncue.core.engine

import app.motioncue.core.model.Side

enum class Strength { SOFT, NORMAL, STRONG }

data class TurnClass(val side: Side, val strength: Strength, val label: String)

data class CautionClass(val strength: Strength, val label: String)

/**
 * SDK TurnType 이름 → 큐 방향·강도.
 * 큐를 내지 않는 기동(직진, 톨게이트, 도착 등)은 null.
 */
object TurnClassifier {

    fun classify(turnType: String): TurnClass? = TABLE[turnType]

    private val TABLE: Map<String, TurnClass> = buildMap {
        put("TURN_LEFT", TurnClass(Side.LEFT, Strength.NORMAL, "좌회전"))
        put("TURN_RIGHT", TurnClass(Side.RIGHT, Strength.NORMAL, "우회전"))
        put("TURN_SHARP_L", TurnClass(Side.LEFT, Strength.STRONG, "급좌회전"))
        put("TURN_SHARP_R", TurnClass(Side.RIGHT, Strength.STRONG, "급우회전"))
        put("TURN_KEEP_L", TurnClass(Side.LEFT, Strength.SOFT, "왼쪽 방향"))
        put("TURN_KEEP_R", TurnClass(Side.RIGHT, Strength.SOFT, "오른쪽 방향"))
        // 우측통행 국가에서 유턴은 왼쪽으로 크게 돈다
        put("TURN_UTURN", TurnClass(Side.LEFT, Strength.STRONG, "유턴"))

        put("TURN_ENTER_IC_L", TurnClass(Side.LEFT, Strength.NORMAL, "IC 진입(왼쪽)"))
        put("TURN_ENTER_IC_R", TurnClass(Side.RIGHT, Strength.NORMAL, "IC 진입(오른쪽)"))
        put("TURN_EXIT_IC_L", TurnClass(Side.LEFT, Strength.NORMAL, "IC 진출(왼쪽)"))
        put("TURN_EXIT_IC_R", TurnClass(Side.RIGHT, Strength.NORMAL, "IC 진출(오른쪽)"))

        listOf(
            "ENTER_UNDER" to "지하차도 진입",
            "AVOID_UNDER" to "지하차도 옆길",
            "ENTER_OVER" to "고가차도 진입",
            "AVOID_OVER" to "고가차도 옆길",
        ).forEach { (key, name) ->
            put("TURN_${key}_L", TurnClass(Side.LEFT, Strength.SOFT, "$name(왼쪽)"))
            put("TURN_${key}_R", TurnClass(Side.RIGHT, Strength.SOFT, "$name(오른쪽)"))
        }

        // 회전교차로: 출구의 시계 방향 기준. 06시(직진)는 큐를 내지 않는다.
        listOf("02", "03", "04").forEach {
            put("TURN_ENTER_ROUNDABOUT_$it", TurnClass(Side.RIGHT, Strength.NORMAL, "회전교차로 ${it.toInt()}시 방향"))
        }
        listOf("08", "09", "10").forEach {
            put("TURN_ENTER_ROUNDABOUT_$it", TurnClass(Side.LEFT, Strength.NORMAL, "회전교차로 ${it.toInt()}시 방향"))
        }
        put("TURN_ENTER_ROUNDABOUT_12", TurnClass(Side.LEFT, Strength.STRONG, "회전교차로 12시 방향"))
    }
}

/** SDK CautionPointType 이름 → 감속 큐 강도. 사용하지 않는 타입은 null. */
object CautionClassifier {

    fun classify(type: String): CautionClass? = when (type) {
        "SPEED_BUMP" -> CautionClass(Strength.STRONG, "과속방지턱")
        "DOWNHILL_CAUTION_AREA" -> CautionClass(Strength.NORMAL, "내리막 주의")
        "NARROW_ROAD" -> CautionClass(Strength.SOFT, "좁은 길")
        else -> null
    }
}
