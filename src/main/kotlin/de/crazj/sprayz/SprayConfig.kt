package de.crazj.sprayz

object SprayConfig {
    fun initialize() {
        val config = SprayZ.instance.config
        config.options().copyDefaults(true).parseComments(true)

        ConfPath.entries.forEach { configPath ->
            config.addDefault(configPath.path, configPath.defaultValue)
            materializePath(configPath)
            config.setInlineComments(configPath.path, listOf(configPath.description))
        }

        SprayZ.instance.saveConfig()
    }

    fun reload() {
        SprayZ.instance.reloadConfig()
    }

    fun get(path: ConfPath): Any? = SprayZ.instance.config.get(path.path)

    fun getBoolean(path: ConfPath): Boolean = SprayZ.instance.config.getBoolean(path.path)

    fun getLong(path: ConfPath): Long = SprayZ.instance.config.getLong(path.path)

    fun getDouble(path: ConfPath): Double = SprayZ.instance.config.getDouble(path.path)

    fun getStringList(path: ConfPath): List<String> = SprayZ.instance.config.getStringList(path.path)

    fun set(path: ConfPath, value: Any?) {
        SprayZ.instance.config.set(path.path, value)
    }

    private fun materializePath(configPath: ConfPath) {
        val config = SprayZ.instance.config
        if (config.isSet(configPath.path)) return

        val defaultValue = configPath.defaultValue
        if (defaultValue != null) {
            config.set(configPath.path, defaultValue)
            return
        }

        if (!config.isConfigurationSection(configPath.path)) {
            config.createSection(configPath.path)
        }
    }
}
