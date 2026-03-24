package de.crazj.sprayz.spray

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.color.AlphaColor
import com.github.retrooper.packetevents.protocol.particle.Particle
import com.github.retrooper.packetevents.protocol.particle.data.ParticleColorData
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.Permission
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.Util
import de.crazj.sprayz.Util.ImageUtil
import de.crazj.sprayz.Util.PacketUtil.sendMapData
import de.crazj.sprayz.Util.toPacketItemFrameOrientation
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
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.*
import kotlin.random.Random


class SprayManager {
    var sprays = ArrayList<WrapperEntity>()
    var sprayMaps = HashMap<WrapperEntity, Triple<Int, String, Emote>>()
    val cooldowns = ArrayList<UUID>()
    val hideFrom = ArrayList<UUID>()


    var listener = object : Listener {
//        @EventHandler
//        fun onInteract(e: PlayerInteractEvent) {
//            if (e.action != Action.RIGHT_CLICK_BLOCK) return
//            if (!e.player.isSneaking) return
//            if (!e.player.hasPermission(Permission.SPRAYZ_USE.full)) return
//            if (e.isBlockInHand) return
//            spray(e.player, e.clickedBlock!!, e.blockFace)
//        }

        @EventHandler
        fun onPlayer(e: PlayerJoinEvent) {
            refreshAllToPlayer(e.player)
        }

        @EventHandler
        fun onPlayer(e: PlayerQuitEvent) {
            refreshAllToPlayer(e.player)
        }

        @EventHandler
        fun onPlayer(e: PlayerTeleportEvent) {
            refreshAllToPlayer(e.player)
        }

        @EventHandler
        fun onPlayer(e: PlayerChunkLoadEvent) {
            task(false) {
                sprays.forEach {
                    if (SpigotConversionUtil.toBukkitLocation(e.world, it.location).chunk != e.chunk) return@forEach
                    hideFrom(e.player, it)
                    if (!hideFrom.contains(e.player.uniqueId)) showToPlayer(e.player, it, sprayMaps[it]!!)
                }
            }
        }
    }

    private fun spray(player: Player, clickedBlock: Block?, clickedFace: BlockFace?) {
        if (clickedBlock == null || clickedFace == null) return
        if (!clickedBlock.type.isSolid) return
        if (!clickedBlock.getRelative(clickedFace).isEmpty) return
        if (sprays.any {
                SpigotConversionUtil.toBukkitLocation(player.world, it.location).block == clickedBlock.getRelative(
                    clickedFace
                ) && (it.entityMeta as ItemFrameMeta).orientation == clickedFace.toPacketItemFrameOrientation()
            }) return
        if (hideFrom.contains(player.uniqueId)) return
        if (cooldowns.contains(player.uniqueId)) return
        cooldowns.add(player.uniqueId)
        taskRunLater(SprayZ.instance.config.getLong(ConfPath.SPRAY_COOLDOWN.path) * 20, false) {
            cooldowns.remove(player.uniqueId)
        }

        val emotes = SprayZ.instance.emoteManager.getAllEmotes()
        if (emotes.isEmpty()) return
        val entry = emotes.entries.random()

        val mapId = Util.relativelySafeMapID()
        val itemFrame = Util.PacketUtil.sendMapItemFrame(
            player, clickedBlock.getRelative(clickedFace).location, entry.key, mapId, clickedFace
        )
        itemFrame.addViewerRule { !hideFrom.contains(it.uuid) }

        sprays.add(itemFrame)
        sprayMaps[itemFrame] = Triple(mapId, entry.key, entry.value)

        Bukkit.getOnlinePlayers().forEach {
            if (!hideFrom.contains(it.uniqueId)) showToPlayer(it, itemFrame, sprayMaps[itemFrame]!!)
        }
        val disappear = SprayZ.instance.config.getLong(ConfPath.DISAPPEAR_AFTER.path)
        if (disappear > 0) taskRunLater(disappear * 20, false) {
            sprays.remove(itemFrame)
            sprayMaps.remove(itemFrame)
            itemFrame.despawn()
        }
//        Bukkit.getOnlinePlayers().forEach { player ->
//            PacketEvents.getAPI().playerManager.sendPacket(
//                player,
//                WrapperPlayServerParticle(
//                    Particle(
//                        ParticleTypes.ENTITY_EFFECT,
//                        ParticleColorData(AlphaColor(255, 0, 255, 0)) // A,R,G,B = voll sichtbar grün
//                    ),
//                    true,
//                    Vector3d(player.x, player.y + 1.2, player.z),
//                    Vector3f(0.25f, 0.25f, 0.25f),
//                    1.0f,
//                    20
//                )
//            )
//        }
        spawnGreenParticlesOnFace(player.world, clickedBlock, clickedFace)
    }

    fun rayTraceSpray(player: Player, range: Double = 4.0) {
        val r = player.rayTraceBlocks(range, FluidCollisionMode.NEVER) ?: return
        spray(player, r.hitBlock, r.hitBlockFace)
    }

    fun refreshAllToPlayer(player: Player) {
        task(false) {
            sprays.forEach {
                hideFrom(player, it)
                if (!hideFrom.contains(player.uniqueId)) showToPlayer(player, it, sprayMaps[it]!!)
            }
        }
    }

    fun showToPlayer(
        player: Player,
        itemFrame: WrapperEntity,
        triple: Triple<Int, String, Emote>,
    ) {
        val emote = triple.third
        if (emote is StaticEmote) {
            sendMapData(player, triple.first, ImageUtil.toMapBytes(emote.image))
        } else if (emote is GifEmote) {
            playGifSpray(player, itemFrame, triple.first, emote)
        }
        itemFrame.addViewer(player.uniqueId)
    }

    fun hideFrom(player: Player, itemFrame: WrapperEntity) {
        itemFrame.removeViewer(player.uniqueId)
    }


    private fun playGifSpray(player: Player, itemFrame: WrapperEntity, mapId: Int, emote: GifEmote) {
        if (emote.frames.isEmpty()) return

        var frameIndex = 0
        val minDelayTicks = 2L // 10 FPS cap

        fun scheduleNextFrame() {
            if (!sprays.contains(itemFrame)) return
            if (hideFrom.contains(player.uniqueId)) return
            if (!player.isOnline) return

            val nextImage = emote.byteFrames[frameIndex]
            val gifDelayMs = emote.delays.getOrNull(frameIndex) ?: 100
            val gifDelayTicks = maxOf(1L, kotlin.math.ceil(gifDelayMs / 50.0).toLong())
            val effectiveDelayTicks = maxOf(gifDelayTicks, minDelayTicks)

            sendMapData(player, mapId, nextImage)

            frameIndex = (frameIndex + 1) % emote.frames.size

            taskRunLater(effectiveDelayTicks, false) {
                scheduleNextFrame()
            }
        }

        scheduleNextFrame()
    }

    fun spawnGreenParticlesOnFace(
        world: World, block: Block, face: BlockFace, amount: Int = 16, speed: Float = 0.1f
    ) {
        val bx = block.x.toDouble()
        val by = block.y.toDouble()
        val bz = block.z.toDouble()

        val min = 0.08
        val max = 0.92

        repeat(amount) {
            val rx = Random.nextDouble(min, max)
            val ry = Random.nextDouble(min, max)
            val rz = Random.nextDouble(min, max)

            val (x, y, z) = when (face) {
                BlockFace.UP -> Triple(bx + rx, by + 1.0 + 0.01, bz + rz)
                BlockFace.DOWN -> Triple(bx + rx, by - 0.01, bz + rz)

                BlockFace.NORTH -> Triple(bx + rx, by + ry, bz - 0.01)
                BlockFace.SOUTH -> Triple(bx + rx, by + ry, bz + 1.0 + 0.01)

                BlockFace.WEST -> Triple(bx - 0.01, by + ry, bz + rz)
                BlockFace.EAST -> Triple(bx + 1.0 + 0.01, by + ry, bz + rz)

                else -> return@repeat
            }

            val particle = Particle(
                ParticleTypes.ENTITY_EFFECT,
                ParticleColorData(AlphaColor(255, 0, 255, 0))
            )


            val packet = WrapperPlayServerParticle(
                particle, true, Vector3d(x, y, z), Vector3f(0f, 0f, 0f), speed, 1
            )

            for (player in world.players) {
                if (hideFrom.contains(player.uniqueId)) continue
                PacketEvents.getAPI().playerManager.sendPacket(player, packet)
            }
        }
    }
}



