package app.motioncue.core.engine

/** 큐 색 (ARGB, alpha=0xFF). 강할수록 진한 색을 쓴다. 점멸은 쓰지 않는다. */
object CuePalette {

    /** 회전 예고·조향 반응: 코럴·붉은 계열 */
    fun turn(strength: Strength): Int = when (strength) {
        Strength.SOFT -> 0xFFFF9D96.toInt()
        Strength.NORMAL -> 0xFFFF6B63.toInt()
        Strength.STRONG -> 0xFFE63F50.toInt()
    }

    /** 감속·과속방지턱: 앰버 계열 */
    fun decel(strength: Strength): Int = when (strength) {
        Strength.SOFT -> 0xFFFFD27A.toInt()
        Strength.NORMAL -> 0xFFFFB000.toInt()
        Strength.STRONG -> 0xFFFF8A00.toInt()
    }
}
