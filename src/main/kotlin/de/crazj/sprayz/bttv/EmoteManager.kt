package de.crazj.sprayz.bttv

import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.bttv.BTTVAPI.Companion.Emote
import de.crazj.sprayz.bttv.BTTVAPI.Companion.getChannelEmotes
import de.crazj.sprayz.bttv.BTTVAPI.Companion.getGlobalEmotes
import de.crazj.sprayz.spray.ImageRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class EmoteManager {
    private var fileEmotes = LinkedHashMap<File, Image>()
    val bttvEmotes: HashSet<Emote> = HashSet()

    fun getAllEmotes(): MutableMap<String, Image> {
        val list = mutableMapOf<String, Image>()
        bttvEmotes.forEach { emote ->
            list[emote.code] = emote.image!!
        }
        fileEmotes.forEach { (file, renderer) ->
            list[file.nameWithoutExtension] = renderer
        }
        return list
    }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if ((ConfPath.BTTV_GLOBAL.get() as Boolean)) {
                bttvEmotes.addAll(getGlobalEmotes())
            }

            for (channelID in SprayZ.instance.getConfig().getStringList(ConfPath.BTTV_CHANNELS.path)) {
                bttvEmotes.addAll(getChannelEmotes(channelID))
            }
        }

        val folder = File(SprayZ.instance.dataFolder, "sprays")
        folder.mkdirs()
        fileEmotes.clear()
        for (file in folder.listFiles()!!) {
            val image = ImageIO.read(file).getScaledInstance(
                128, 128, BufferedImage.SCALE_SMOOTH
            )
            fileEmotes[file] = image
        }
    }


    fun map(name: String): ItemStack {
        val map = ItemStack(Material.FILLED_MAP)
        val meta = (map.itemMeta as MapMeta)
        meta.itemName(Component.text(name))
        map.setItemMeta(meta)
        return map
    }

    fun setMapContent(player: Player, item: ItemStack, imageRenderer: ImageRenderer) {
        item.editMeta {
            val mapView = Bukkit.createMap(player.world)
            for (renderer in mapView.renderers) mapView.removeRenderer(renderer)
            mapView.addRenderer(imageRenderer)
            (it as MapMeta).mapView = mapView
        }

    }
}
