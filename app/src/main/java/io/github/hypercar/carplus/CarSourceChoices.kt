package io.github.hypercar.carplus

internal data class CarSourceChoice(val key: String, val label: String, val selected: Boolean, val enabled: Boolean)

internal fun carSourceChoices(
    current: String?,
    shizukuReady: Boolean,
    carWithInstalled: Boolean,
    carPlusInstalled: Boolean,
    connected: Boolean,
): List<CarSourceChoice> = listOf(
    Triple("car_ui", "自绘", shizukuReady),
    Triple("carwith", "CarWith", carWithInstalled),
    Triple("system_carlife", "CarLife+", true),
    Triple("carplus", "Car+", carPlusInstalled),
).map { (key, label, available) ->
    CarSourceChoice(key, label, selected = current == key, enabled = !connected && available)
}
