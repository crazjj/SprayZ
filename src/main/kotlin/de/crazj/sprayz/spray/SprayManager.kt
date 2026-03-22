package de.crazj.sprayz.spray

import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.Permission
import de.crazj.sprayz.SprayZ
import de.tr7zw.changeme.nbtapi.NBT
import net.axay.kspigot.runnables.taskRunLater
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemFrame
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.map.MapView
import java.util.*
import java.util.concurrent.ThreadLocalRandom


class SprayManager {
    var sprays = ArrayList<ItemFrame>()
    val cooldowns = ArrayList<UUID>()


    var listener = object : Listener {
        @EventHandler
        fun onInteract(e: PlayerInteractEvent) {
            if (e.action != Action.RIGHT_CLICK_BLOCK) return
            if (!e.player.isSneaking) return
            if (!e.player.hasPermission(Permission.SPRAYZ_USE.full)) return
            if (!e.clickedBlock!!.type.isSolid) return
            if (!e.clickedBlock!!.getRelative(e.blockFace).isEmpty) return
            if (cooldowns.contains(e.player.uniqueId)) return
            cooldowns.add(e.player.uniqueId)
            taskRunLater(SprayZ.instance.config.getLong(ConfPath.SPRAY_COOLDOWN.path) * 20, true) {
                cooldowns.remove(e.player.uniqueId)
            }
            spray(e)
        }
    }

    private fun spray(event: PlayerInteractEvent) {
        // Create a map with an image
        val emotes = SprayZ.instance.emoteManager.getAllEmotes()
        val i = ThreadLocalRandom.current().nextInt(emotes.size)
        val mapView: MapView = Bukkit.createMap(event.player.world)
        mapView.addRenderer(emotes.values.elementAt(i))

        val map = ItemStack(Material.FILLED_MAP)
        val meta = (map.itemMeta as MapMeta)
        meta.itemName(Component.text(ChatColor.DARK_PURPLE.toString() + emotes.keys.elementAt(i)))
        meta.mapView = mapView
        map.setItemMeta(meta)

        val block = event.clickedBlock!!.getRelative(event.blockFace)
        val itemFrame: ItemFrame =
            block.location.world!!.spawn(block.location, ItemFrame::class.java).apply {
                if (this.attachedFace == BlockFace.UP || this.attachedFace == BlockFace.DOWN) {
                    // normalize yaw to a value between 0 and 360
                    var normalizedYaw = event.player.location.yaw % 360
                    if (normalizedYaw < 0) {
                        normalizedYaw += 360
                    }

                    NBT.modify(this) {
                        it.setInteger(
                            "ItemRotation", when {
                                135 < normalizedYaw && normalizedYaw <= 225 -> 0 // North
                                225 < normalizedYaw && normalizedYaw <= 315 -> 1 //East
                                315 < normalizedYaw || normalizedYaw <= 45 -> 2 // South
                                else   /* 45 < normalizedYaw && normalizedYaw <= 135 */ -> 3 // West
                            }
                        )
                    }
                    //   // rotation = ... is broken
                    /*      rotation = when {
                              135 < normalizedYaw && normalizedYaw <= 225 -> Rotation.NONE // North
                              225 < normalizedYaw && normalizedYaw <= 315 -> Rotation.CLOCKWISE //East
                              315 < normalizedYaw || normalizedYaw <= 45 -> Rotation.FLIPPED // South
                              else   *//* 45 < normalizedYaw && normalizedYaw <= 135 *//* -> Rotation.COUNTER_CLOCKWISE // West
                    }*/

                    // this works but ugly
//                          Bukkit.dispatchCommand(
//                              Bukkit.getConsoleSender(),
//                              "execute positioned ${this.location.x} ${this.location.y} ${this.location.z} run data merge entity @e[type=minecraft:item_frame,sort=nearest,limit=1] {ItemRotation: ${
//                                  when {
//                                      135 < normalizedYaw && normalizedYaw <= 225 -> 0 // North
//                                      225 < normalizedYaw && normalizedYaw <= 315 -> 1 //East
//                                      315 < normalizedYaw || normalizedYaw <= 45 -> 2 // South
//                                      else   /* 45 < normalizedYaw && normalizedYaw <= 135  */-> 3 // West
//                            }
//                        }}"
//                    )
                }
            }
        itemFrame.setFacingDirection(event.blockFace.oppositeFace)
        itemFrame.isVisible = false
        itemFrame.isInvulnerable = true
        itemFrame.setItem(map)
        itemFrame.isFixed = !(ConfPath.SPRAY_ROTATABLE.get() as Boolean)

        itemFrame.itemDropChance = 0f
        sprays.add(itemFrame)

        val debug = false
        if (debug) {
            SprayZ.instance.log("blockface ${event.blockFace}")
            SprayZ.instance.log("blockface opposite ${event.blockFace.oppositeFace}")
            SprayZ.instance.log("attached face ${itemFrame.attachedFace}")
            SprayZ.instance.log("rotation ${itemFrame.rotation}")
        }

        taskRunLater(SprayZ.instance.config.getLong(ConfPath.DISAPPEAR_AFTER.path) * 20, true) {
            sprays.remove(itemFrame)
            itemFrame.remove()
        }
    }

}


