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
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class EmoteManager {
    private var fileEmotes = LinkedHashMap<File, ImageRenderer>()
    val bttvEmotes: HashSet<Emote> = HashSet()

    fun getAllEmotes(): MutableMap<String, ImageRenderer> {
        val list = mutableMapOf<String, ImageRenderer>()
        bttvEmotes.forEach { emote -> list[emote.code] = emote.imageRenderer }
        fileEmotes.forEach { (file, renderer) -> list[file.nameWithoutExtension] = renderer }
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
                128, 128,
                BufferedImage.SCALE_SMOOTH
            )
            val renderer = ImageRenderer(image)
            fileEmotes[file] = renderer
        }
    }


    fun map(player: Player,name:String, emote: ImageRenderer): ItemStack {
        val mapView = Bukkit.createMap(player.world)
        for (renderer in mapView.renderers) mapView.removeRenderer(renderer)
        mapView.addRenderer(emote)
        val map = ItemStack(Material.FILLED_MAP)
        val meta = (map.itemMeta as MapMeta)
        meta.itemName(Component.text(name))
        meta.mapView = mapView
        map.setItemMeta(meta)
        return map
    }
}
