package de.crazj.sprayz

import com.github.retrooper.packetevents.PacketEvents
import de.crazj.sprayz.spray.EmoteManager
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
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit


class SprayZ : KSpigot() {

    companion object {
        lateinit var instance: SprayZ

        val PREFIX = Component.text("[SprayZ]: ", NamedTextColor.DARK_PURPLE)
    }

    lateinit var sprayManager: SprayManager

    lateinit var emoteManager: EmoteManager

    fun log(message: String) {
        Bukkit.getConsoleSender().sendMessage(PREFIX.append(Component.text(message, NamedTextColor.WHITE)))
    }

    override fun load() {
        instance = this
        log("Loaded")

        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
        PacketEvents.getAPI().load()

        sprayManager = SprayManager()
    }

    override fun startup() {
        log("Starting up...")
        initializeConfig()

        Bukkit.getPluginManager()
            .addPermissions(Permission.entries.map { org.bukkit.permissions.Permission(it.full, it.permissionDefault) })

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
//                .tickTickables()
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
        log("Shutting down...")
        sprayManager.listener.unregister()
        sprayManager.sprays.forEach { it.remove() }

        PacketEvents.getAPI()?.terminate()
    }

}