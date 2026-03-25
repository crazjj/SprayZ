package de.crazj.sprayz.spray

import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.Util
import de.crazj.sprayz.Util.ImageUtil
import de.crazj.sprayz.Util.PacketUtil.sendMapData
import de.crazj.sprayz.Util.PacketUtil.spawnGreenParticlesOnFaceGrid
import de.crazj.sprayz.Util.PacketUtil.toPacketItemFrameOrientation
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import me.tofaa.entitylib.meta.other.ItemFrameMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import net.axay.kspigot.runnables.task
import net.axay.kspigot.runnables.taskRunLater
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.UUID

class SprayManager {
    companion object {
        private const val MIN_ANIMATION_DELAY_TICKS = 2L
    }

    data class PlacedSpray(
        val entity: WrapperEntity,
        val mapId: Int,
        val emote: Emote,
    )

    sealed interface SprayAttemptResult {
        data class Success(val emoteName: String) : SprayAttemptResult
        data object NoTarget : SprayAttemptResult
        data object InvalidTarget : SprayAttemptResult
        data object OccupiedTarget : SprayAttemptResult
        data object SprayAlreadyPresent : SprayAttemptResult
        data object HiddenModeEnabled : SprayAttemptResult
        data object CooldownActive : SprayAttemptResult
        data object NoEmotesLoaded : SprayAttemptResult
    }

    private val placedSprays = LinkedHashMap<WrapperEntity, PlacedSpray>()
    val cooldowns = LinkedHashSet<UUID>()
    val hideFrom = LinkedHashSet<UUID>()
    private var nextVirtualMapId = Int.MAX_VALUE

    val listener = object : Listener {
        @EventHandler
        fun onPlayer(event: PlayerJoinEvent) = refreshAllToPlayer(event.player)

        @EventHandler
        fun onPlayer(event: PlayerQuitEvent) = refreshAllToPlayer(event.player)

        @EventHandler
        fun onPlayer(event: PlayerTeleportEvent) = refreshAllToPlayer(event.player)

        @EventHandler
        fun onPlayer(event: PlayerChunkLoadEvent) {
            refreshToPlayer(
                event.player,
                activeSprays().filter { spray ->
                    SpigotConversionUtil.toBukkitLocation(event.world, spray.entity.location).chunk == event.chunk
                },
            )
        }
    }

    fun clearAll() {
        placedSprays.values.toList().forEach(::removeSpray)
    }

    fun rayTraceSpray(
        player: Player,
        range: Double = 4.0,
        selectedEmote: Pair<String, Emote>? = null,
    ): SprayAttemptResult {
        val hitResult = player.rayTraceBlocks(range, FluidCollisionMode.NEVER) ?: return SprayAttemptResult.NoTarget
        return spray(player, hitResult.hitBlock, hitResult.hitBlockFace, selectedEmote)
    }

    fun refreshAllToPlayer(player: Player) {
        refreshToPlayer(player, activeSprays())
    }

    fun showToPlayer(player: Player, placedSpray: PlacedSpray) {
        when (val emote = placedSpray.emote) {
            is StaticEmote -> sendMapData(player, placedSpray.mapId, ImageUtil.toMapBytes(emote.image))
            is GifEmote -> playGifSpray(player, placedSpray, emote)
        }
        placedSpray.entity.addViewer(player.uniqueId)
    }

    fun hideFrom(player: Player, itemFrame: WrapperEntity) = itemFrame.removeViewer(player.uniqueId)

    private fun spray(
        player: Player,
        clickedBlock: Block?,
        clickedFace: BlockFace?,
        selectedEmote: Pair<String, Emote>? = null,
    ): SprayAttemptResult {
        if (clickedBlock == null || clickedFace == null) return SprayAttemptResult.NoTarget
        if (!clickedBlock.type.isSolid) return SprayAttemptResult.InvalidTarget
        if (!clickedBlock.getRelative(clickedFace).isEmpty) return SprayAttemptResult.OccupiedTarget
        if (findSprayAt(player.world, clickedBlock, clickedFace) != null) return SprayAttemptResult.SprayAlreadyPresent
        if (player.uniqueId in hideFrom) return SprayAttemptResult.HiddenModeEnabled
        if (player.uniqueId in cooldowns) return SprayAttemptResult.CooldownActive

        val emotes = SprayZ.instance.emoteManager.getAllEmotes()
        if (emotes.isEmpty()) return SprayAttemptResult.NoEmotesLoaded

        val cooldownSeconds = ConfPath.SPRAY_COOLDOWN.getLong()
        if (cooldownSeconds > 0) {
            cooldowns.add(player.uniqueId)
            taskRunLater(cooldownSeconds * 20, false) {
                cooldowns.remove(player.uniqueId)
            }
        }

        val entry = selectedEmote ?: emotes.entries.random().let { it.key to it.value }
        val mapId = allocateVirtualMapId()
        val itemFrame = Util.PacketUtil.sendMapItemFrame(
            player,
            clickedBlock.getRelative(clickedFace).location,
            entry.first,
            mapId,
            clickedFace,
        )
        itemFrame.addViewerRule { it.uuid !in hideFrom }

        val placedSpray = PlacedSpray(itemFrame, mapId, entry.second)
        placedSprays[itemFrame] = placedSpray
        visiblePlayers().forEach { showToPlayer(it, placedSpray) }

        val disappearAfter = ConfPath.DISAPPEAR_AFTER.getLong()
        if (disappearAfter > 0) {
            taskRunLater(disappearAfter * 20, false) {
                removeSpray(placedSpray)
            }
        }

        spawnGreenParticlesOnFaceGrid(this, player.world, clickedBlock, clickedFace)
        return SprayAttemptResult.Success(entry.first)
    }

    private fun refreshToPlayer(player: Player, sprays: Iterable<PlacedSpray>) {
        task(false) {
            sprays.forEach { placedSpray ->
                hideFrom(player, placedSpray.entity)
                if (player.uniqueId !in hideFrom) {
                    showToPlayer(player, placedSpray)
                }
            }
        }
    }

    private fun playGifSpray(player: Player, placedSpray: PlacedSpray, emote: GifEmote) {
        var frameIndex = 0

        fun scheduleNextFrame() {
            if (placedSpray.entity !in placedSprays || player.uniqueId in hideFrom || !player.isOnline) return

            sendMapData(player, placedSpray.mapId, emote.byteFrames[frameIndex])

            val delayMs = emote.delays.getOrNull(frameIndex) ?: 100
            val delayTicks = maxOf(1L, kotlin.math.ceil(delayMs / 50.0).toLong())
            frameIndex = (frameIndex + 1) % emote.frames.size

            taskRunLater(maxOf(delayTicks, MIN_ANIMATION_DELAY_TICKS), false) {
                scheduleNextFrame()
            }
        }

        scheduleNextFrame()
    }

    private fun activeSprays(): List<PlacedSpray> = placedSprays.values.toList()

    private fun visiblePlayers(): List<Player> = Bukkit.getOnlinePlayers().filterNot { it.uniqueId in hideFrom }

    private fun removeSpray(placedSpray: PlacedSpray) {
        placedSprays.remove(placedSpray.entity)
        placedSpray.entity.despawn()
    }

    private fun allocateVirtualMapId(): Int {
        val activeMapIds = placedSprays.values
            .mapTo(linkedSetOf(), PlacedSpray::mapId)

        while (nextVirtualMapId > Int.MIN_VALUE) {
            val candidate = nextVirtualMapId--
            if (candidate !in activeMapIds && Bukkit.getMap(candidate) == null) {
                return candidate
            }
        }

        throw IllegalStateException("No free virtual spray map IDs are available.")
    }

    private fun findSprayAt(world: World, clickedBlock: Block, clickedFace: BlockFace): PlacedSpray? {
        val targetBlock = clickedBlock.getRelative(clickedFace)
        val targetOrientation = clickedFace.toPacketItemFrameOrientation()

        return activeSprays().firstOrNull { placedSpray ->
            val location = SpigotConversionUtil.toBukkitLocation(world, placedSpray.entity.location)
            location.block == targetBlock &&
                (placedSpray.entity.entityMeta as ItemFrameMeta).orientation == targetOrientation
        }
    }
}
