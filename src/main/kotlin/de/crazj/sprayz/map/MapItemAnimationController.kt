package de.crazj.sprayz.map

import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.Util.PacketUtil.sendMapData
import org.bukkit.Bukkit
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.ceil

internal class MapItemAnimationController(
    private val store: MapItemStore,
) {
    private data class ViewerAnimationState(
        val mapId: Int,
        val asset: StoredMapAsset,
        var frameIndex: Int = 0,
        var nextFrameAtTick: Long = 0,
    )

    companion object {
        private const val MIN_ANIMATION_DELAY_TICKS = 2L
        private const val VISIBILITY_RESCAN_INTERVAL_TICKS = 10L
    }

    private val activeAnimations = linkedMapOf<UUID, MutableMap<Int, ViewerAnimationState>>()
    private val dirtyPlayers = linkedSetOf<UUID>()

    private var currentTick = 0L
    private var runtimeTask: BukkitTask? = Bukkit.getScheduler().runTaskTimer(
        SprayZ.instance,
        Runnable { tickRuntime() },
        1L,
        1L,
    )

    fun markDirty(player: Player) {
        dirtyPlayers.add(player.uniqueId)
    }

    fun startAnimationIfNeeded(player: Player, item: org.bukkit.inventory.ItemStack?) {
        resolveAnimatedMapId(item, player)?.let { mapId ->
            startAnimation(player, mapId)
        }
    }

    fun stopAnimationIfNeeded(player: Player) {
        stopAnimations(player, resetFrames = true)
    }

    fun shutdown() {
        runtimeTask?.cancel()
        Bukkit.getOnlinePlayers().forEach(::stopAnimationIfNeeded)
        activeAnimations.clear()
        dirtyPlayers.clear()
    }

    private fun tickRuntime() {
        currentTick++

        if (currentTick % VISIBILITY_RESCAN_INTERVAL_TICKS == 0L) {
            Bukkit.getOnlinePlayers().forEach(::markDirty)
        }

        flushDirtyPlayers()
        advanceViewerAnimations()
    }

    private fun flushDirtyPlayers() {
        val pendingPlayers = dirtyPlayers.toList()
        dirtyPlayers.clear()

        pendingPlayers.forEach { playerId ->
            val player = Bukkit.getPlayer(playerId)
            if (player == null || !player.isOnline) {
                activeAnimations.remove(playerId)
            } else {
                syncPlayerAnimations(player)
            }
        }
    }

    private fun syncPlayerAnimations(player: Player) {
        if (!player.isOnline) {
            stopAnimations(player, resetFrames = false)
            return
        }

        store.collectCandidateItems(player).forEach { store.ensureRestored(it, player) }

        val desiredMapIds = collectVisibleAnimatedMapIds(player)
        val playerAnimations = activeAnimations.getOrPut(player.uniqueId) { linkedMapOf() }

        (playerAnimations.keys - desiredMapIds).toList().forEach { mapId ->
            stopAnimation(player, mapId)
        }

        desiredMapIds.forEach { mapId ->
            if (playerAnimations.containsKey(mapId)) return@forEach
            startAnimation(player, mapId)
        }

        if (playerAnimations.isEmpty()) {
            activeAnimations.remove(player.uniqueId)
        }
    }

    private fun collectVisibleAnimatedMapIds(player: Player): Set<Int> {
        return buildSet {
            resolveAnimatedMapId(player.inventory.itemInMainHand, player)?.let(::add)
            resolveAnimatedMapId(player.inventory.itemInOffHand, player)?.let(::add)
            addAll(collectAnimatedFrameMaps(player))
        }
    }

    private fun collectAnimatedFrameMaps(player: Player): Set<Int> {
        val nearbyRadius = ((Bukkit.getViewDistance() * 16) + 16).toDouble()

        return player.getNearbyEntities(nearbyRadius, nearbyRadius, nearbyRadius)
            .asSequence()
            .filterIsInstance<ItemFrame>()
            .mapNotNull { frame ->
                store.ensureRestored(frame, player)?.asset?.takeIf(StoredMapAsset::animated)?.mapId
            }
            .toSet()
    }

    private fun resolveAnimatedMapId(item: org.bukkit.inventory.ItemStack?, player: Player): Int? {
        val restored = store.ensureRestored(item, player) ?: return null
        return restored.asset.takeIf(StoredMapAsset::animated)?.mapId
    }

    private fun startAnimation(player: Player, mapId: Int) {
        val asset = store.loadAsset(mapId)?.takeIf(StoredMapAsset::animated) ?: return
        val playerAnimations = activeAnimations.getOrPut(player.uniqueId) { linkedMapOf() }
        if (playerAnimations.containsKey(mapId)) return

        playerAnimations[mapId] = ViewerAnimationState(
            mapId = mapId,
            asset = asset,
            nextFrameAtTick = currentTick,
        )
    }

    private fun advanceViewerAnimations() {
        val playerIterator = activeAnimations.entries.iterator()

        while (playerIterator.hasNext()) {
            val (playerId, playerAnimations) = playerIterator.next()
            val player = Bukkit.getPlayer(playerId)

            if (player == null || !player.isOnline) {
                playerIterator.remove()
                continue
            }

            val animationIterator = playerAnimations.values.iterator()
            while (animationIterator.hasNext()) {
                val animation = animationIterator.next()
                if (animation.nextFrameAtTick > currentTick) continue

                sendMapData(player, animation.mapId, animation.asset.byteFrames[animation.frameIndex])

                val currentFrame = animation.frameIndex
                val delayMs = animation.asset.delays.getOrElse(currentFrame) { MapItemStore.DEFAULT_FRAME_DELAY_MS }
                animation.frameIndex = (currentFrame + 1) % animation.asset.byteFrames.size
                animation.nextFrameAtTick = currentTick + maxOf(
                    MIN_ANIMATION_DELAY_TICKS,
                    ceil(delayMs / 50.0).toLong(),
                )
            }

            if (playerAnimations.isEmpty()) {
                playerIterator.remove()
            }
        }
    }

    private fun stopAnimation(player: Player, mapId: Int, resetFrame: Boolean = true) {
        val playerAnimations = activeAnimations[player.uniqueId] ?: return
        val state = playerAnimations.remove(mapId) ?: return

        if (resetFrame && player.isOnline) {
            sendMapData(player, mapId, state.asset.byteFrames.first())
        }

        if (playerAnimations.isEmpty()) {
            activeAnimations.remove(player.uniqueId)
        }
    }

    private fun stopAnimations(player: Player, resetFrames: Boolean) {
        activeAnimations.remove(player.uniqueId)?.values?.forEach { state ->
            if (resetFrames && player.isOnline) {
                sendMapData(player, state.mapId, state.asset.byteFrames.first())
            }
        }
    }
}
