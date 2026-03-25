package de.crazj.sprayz

import com.github.retrooper.packetevents.PacketEvents
import de.crazj.sprayz.cmd.SprayZCommand
import de.crazj.sprayz.map.MapItemService
import de.crazj.sprayz.spray.EmoteManager
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

    lateinit var mapItemService: MapItemService

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
        SprayConfig.initialize()

        Bukkit.getPluginManager()
            .addPermissions(Permission.entries.map { org.bukkit.permissions.Permission(it.full, it.permissionDefault) })

        if (!NBT.preloadApi()) {
            logger.warning("NBT-API wasn't initialized properly, disabling the plugin")
            pluginManager.disablePlugin(this)
            return
        }

        PacketEvents.getAPI().init()
        EntityLib.init(
            SpigotEntityLibPlatform(this),
            APIConfig(PacketEvents.getAPI())
                .forceBundles()
//                .checkForUpdates()
                .usePlatformLogger()
        )

        emoteManager = EmoteManager()
        mapItemService = MapItemService()
        Bukkit.getPluginManager().registerEvents(sprayManager.listener, this)
        Bukkit.getPluginManager().registerEvents(mapItemService.listener, this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { commands ->
            commands.registrar().register(SprayZCommand().commandNode)
        }
    }

    override fun shutdown() {
        log("Shutting down...")
        sprayManager.listener.unregister()
        sprayManager.clearAll()
        if (::mapItemService.isInitialized) {
            mapItemService.shutdown()
        }
        if (::emoteManager.isInitialized) {
            emoteManager.shutdown()
        }

        PacketEvents.getAPI()?.terminate()
    }
}
