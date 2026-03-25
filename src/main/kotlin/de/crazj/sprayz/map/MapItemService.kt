package de.crazj.sprayz.map

import net.axay.kspigot.event.unregister
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.World
import de.crazj.sprayz.Util.PacketUtil.sendMapData
import de.crazj.sprayz.spray.Emote

class MapItemService {
    private val store = MapItemStore()
    private val animationController = MapItemAnimationController(store)

    val listener = object : Listener {
        @EventHandler
        fun onPlayer(event: PlayerJoinEvent) = animationController.markDirty(event.player)

        @EventHandler
        fun onPlayer(event: PlayerRespawnEvent) = animationController.markDirty(event.player)

        @EventHandler
        fun onPlayer(event: PlayerTeleportEvent) = animationController.markDirty(event.player)

        @EventHandler
        fun onPlayer(event: PlayerItemHeldEvent) = animationController.markDirty(event.player)

        @EventHandler
        fun onPlayer(event: PlayerSwapHandItemsEvent) = animationController.markDirty(event.player)

        @EventHandler
        fun onPlayer(event: PlayerDropItemEvent) = animationController.markDirty(event.player)

        @EventHandler
        fun onPlayer(event: InventoryClickEvent) {
            (event.whoClicked as? Player)?.let(animationController::markDirty)
        }

        @EventHandler
        fun onPlayer(event: InventoryDragEvent) {
            (event.whoClicked as? Player)?.let(animationController::markDirty)
        }

        @EventHandler
        fun onPlayer(event: EntityPickupItemEvent) {
            (event.entity as? Player)?.let(animationController::markDirty)
        }

        @EventHandler
        fun onPlayer(event: PlayerQuitEvent) {
            animationController.stopAnimationIfNeeded(event.player)
        }
    }

    fun createMapItem(emoteName: String, emote: Emote, world: World): ItemStack {
        return store.createMapItem(emoteName, emote, world)
    }

    fun ensureRestored(item: ItemStack?, player: Player? = null) {
        val restored = store.ensureRestored(item, player) ?: return
        if (player != null && !restored.asset.animated && store.isMapHeldByPlayer(player, restored.asset.mapId)) {
            sendMapData(player, restored.asset.mapId, restored.asset.byteFrames.first())
        }
    }

    fun ensureRestored(frame: ItemFrame, player: Player? = null) {
        store.ensureRestored(frame, player)
    }

    fun startAnimationIfNeeded(player: Player, item: ItemStack?) {
        animationController.startAnimationIfNeeded(player, item)
    }

    fun stopAnimationIfNeeded(player: Player) {
        animationController.stopAnimationIfNeeded(player)
    }

    fun shutdown() {
        listener.unregister()
        animationController.shutdown()
        store.shutdown()
    }
}
