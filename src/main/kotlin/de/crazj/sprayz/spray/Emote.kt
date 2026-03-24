package de.crazj.sprayz.spray

import de.crazj.sprayz.Util
import java.awt.Image
import java.awt.image.BufferedImage

abstract class Emote(open val name: String, val animated: Boolean) {
    abstract fun anyFrame(): Image
    fun toMapBytes(img: BufferedImage) = Util.ImageUtil.toMapBytes(img)
}

data class StaticEmote(override val name: String, val image: BufferedImage) : Emote(name, false) {
    override fun anyFrame(): Image = image
}

class GifEmote(
    override val name: String,
    val frames: List<BufferedImage>,
    val delays: List<Int>
) : Emote(name, true) {
    val byteFrames = frames.map {toMapBytes(it) }
    override fun anyFrame(): Image = frames.first()
}