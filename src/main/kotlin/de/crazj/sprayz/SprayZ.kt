package de.crazj.sprayz

import com.github.retrooper.packetevents.PacketEvents
import de.crazj.sprayz.bttv.EmoteManager
import de.crazj.sprayz.cmd.SprayZCommand
import de.crazj.sprayz.spray.SprayManager
import de.tr7zw.changeme.nbtapi.NBT
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import me.tofaa.entitylib.APIConfig
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform
import net.axay.kspigot.event.unregister
import net.axay.kspigot.extensions.pluginManager
import net.axay.kspigot.main.KSpigot
import org.bukkit.Bukkit

class SprayZ : KSpigot() {

    companion object {
        lateinit var instance: SprayZ
        lateinit var sprayManager: SprayManager
    }

    lateinit var emoteManager: EmoteManager

    fun log(message: String) {
        Bukkit.getLogger().info("SprayZ: $message")
    }

    override fun load() {
        instance = this
        log("SprayZ loaded")

        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
        PacketEvents.getAPI().load()

        sprayManager = SprayManager()
    }

    override fun startup() {
        log("SprayZ starting up...")
        initializeConfig()

        if (!NBT.preloadApi()) {
            logger.warning("NBT-API wasn't initialized properly, disabling the plugin");
            pluginManager.disablePlugin(this);
            return;
        }

        PacketEvents.getAPI().init()
        EntityLib.init(
            SpigotEntityLibPlatform(this),
            APIConfig(PacketEvents.getAPI())
                .forceBundles()
                .tickTickables()
                .usePlatformLogger()
        )

        emoteManager = EmoteManager()
        Bukkit.getPluginManager().registerEvents(sprayManager.listener, this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { commands ->
            commands.registrar().register(SprayZCommand().commandNode)
        }
    }

    private fun initializeConfig() {
        log("Initialising config...")
        config.options().copyDefaults(true)
        for (configPath in ConfPath.entries)
            config.addDefault(configPath.path, configPath.defaultValue)
        saveConfig()
    }

    override fun shutdown() {
        log("SprayZ shutting down...")
        sprayManager.listener.unregister()
        sprayManager.sprays.forEach { it.remove() }

        PacketEvents.getAPI()?.terminate()
    }

}