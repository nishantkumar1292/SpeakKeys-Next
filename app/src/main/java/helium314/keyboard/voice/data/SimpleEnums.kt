package helium314.keyboard.voice.data

enum class KeepScreenAwakeMode {
    NEVER, WHEN_LISTENING, WHEN_OPEN
}

enum class HapticIntensity(val amplitude: Int) {
    LIGHT(40), MEDIUM(80), STRONG(180)
}
