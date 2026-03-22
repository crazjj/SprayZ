package de.crazj.sprayz.spray

import org.bukkit.entity.Player
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import java.awt.Image

class ImageRenderer(private var image: Image) : MapRenderer() {

    override fun render(view: MapView, canvas: MapCanvas, player: Player) {
        canvas.drawImage(0, 0, image)
        view.isTrackingPosition = false
        view.scale = MapView.Scale.FARTHEST
    }
}
