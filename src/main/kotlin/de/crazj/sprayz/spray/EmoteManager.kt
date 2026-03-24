package de.crazj.sprayz.spray

import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.Util
import de.crazj.sprayz.cmd.ImageRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import java.io.File
import javax.imageio.ImageIO

class EmoteManager {
    private var fileEmotes = LinkedHashMap<File, Emote>()
    val bttvEmotes: HashSet<Emote> = HashSet()

    fun getAllEmotes(): MutableMap<String, Emote> {
        val list = mutableMapOf<String, Emote>()
        bttvEmotes.forEach { emote ->
            list[emote.name] = emote
        }
        fileEmotes.forEach { (file, emote) ->
            val name = file.nameWithoutExtension
            list[name] = emote
        }
        return list
    }

    init {
        loadAll()
    }

    fun refresh() {
        fileEmotes.clear()
        bttvEmotes.clear()
        SprayZ.instance.reloadConfig()
        loadAll()
    }

    internal fun loadAll() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if ((ConfPath.BTTV_GLOBAL.get() as Boolean)) {
                bttvEmotes.addAll(BTTVAPI.getGlobalEmotes())
            }

            val conf = SprayZ.instance.getConfig()
            if (conf.isSet(ConfPath.BTTV_CHANNELS.path) && conf.isList(ConfPath.BTTV_CHANNELS.path)) {
                val list = conf.getStringList(ConfPath.BTTV_CHANNELS.path)
                SprayZ.instance.log(
                    "Lade BTTV Emotes von folgenden Kanälen: " + list.joinToString(
                        ", "
                    )
                )
                for (channelID in list) {
                    bttvEmotes.addAll(BTTVAPI.getChannelEmotes(channelID))
                }
            }

            val folder = File(SprayZ.instance.dataFolder, "sprays")
            folder.mkdirs()
            for (file in folder.listFiles()!!) {
                val animated = Util.ImageUtil.isAnimated(file)
                val emote = if (animated) {
                    Util.GIFUtil.readGifFrames(
                        file.nameWithoutExtension, file
                    )
                } else {
                    StaticEmote(
                        file.nameWithoutExtension, ImageIO.read(file)
                    )
                }


                fileEmotes[file] = emote
            }
        }
    }


    /*
    * Map doesnt automatically have content
    */
    fun mapItem(name: String): ItemStack {
        val map = ItemStack(Material.FILLED_MAP)
        val meta = (map.itemMeta as MapMeta)
        meta.itemName(Component.text(name).color(NamedTextColor.DARK_PURPLE))
        map.setItemMeta(meta)
        return map
    }

    fun setMapContent(player: Player, item: ItemStack, imageRenderer: ImageRenderer) {
        item.editMeta(MapMeta::class.java) {
            val mapView = Bukkit.createMap(player.world)
            for (renderer in mapView.renderers) mapView.removeRenderer(renderer)
            mapView.addRenderer(imageRenderer)
            (it).mapView = mapView
        }

    }
}

