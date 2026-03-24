package de.crazj.sprayz

enum class ConfPath(sub: String, val defaultValue: Any?, parent: ConfPath?) {
    SPRAY("spray", null, null),
    DISAPPEAR_AFTER("disappear-after", 3, SPRAY),
    SPRAY_COOLDOWN("cooldown", 5, SPRAY),
    SPRAY_RANGE("range", 8.0, SPRAY),
    BTTV("bttv", null, null),
    BTTV_GLOBAL("global-emotes-enabled", true, BTTV),
    BTTV_CHANNELS("emote-channels", listOf("38121996"), BTTV),
    ;

    var path: String

    init {
        path = if (parent != null) parent.path + "." + sub else sub
    }

    override fun toString(): String {
        return path
    }

    fun get(): Any {
        return SprayZ.instance.config.get(path)!!
    }

    fun set(value: Any?) {
        SprayZ.instance.config.set(path, value)
    }
}