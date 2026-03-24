package de.crazj.sprayz.spray

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.Util
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import jdk.internal.org.commonmark.internal.Bracket.image
import org.bukkit.ChatColor
import org.bukkit.map.MapPalette
import sun.awt.www.content.image.gif
import java.awt.Image
import java.lang.classfile.Attributes.code
import java.net.URI
import java.net.URL
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

object BTTVAPI {


    private const val GLOBAL = "https://api.betterttv.net/3/cached/emotes/global"
    private const val CHANNEL_EMOTES = "https://api.betterttv.net/3/cached/users/twitch/<TWITCH_USER_ID>"
    private const val EMOTE_3X = "https://cdn.betterttv.net/emote/<EMOTE_ID>/3x"

    suspend fun getRequest(url: URL): JsonElement {
        val response = HttpClient(CIO).get(url)
        if (response.status.value != 200) {
            SprayZ.Companion.instance.log(
                ChatColor.RED.toString() + "ERROR while getting emotes from url: " + url.toString() + " Response code: " + response.status.value
            )
        }
        val text = response.bodyAsText()
        return JsonParser.parseString(text)
    }

    suspend fun getChannelEmotes(userID: String): HashSet<Emote> {
        val url = URI(CHANNEL_EMOTES.replace("<TWITCH_USER_ID>", userID)).toURL()
        val jsonObject = getRequest(url).getAsJsonObject()
        val channelJsonArray = jsonObject.getAsJsonArray("channelEmotes")
        val sharedJsonArray = jsonObject.getAsJsonArray("sharedEmotes")
        val emotes = HashSet<Emote>()

        for (emotesJsonArray in listOf(channelJsonArray, sharedJsonArray)) {
            for (emoteJson in emotesJsonArray) {
                val emoteJsonObj = emoteJson.getAsJsonObject()
                emotes.add(
                    emoteOfJsonObj(
                        emoteJsonObj
                    )
                )
            }
        }

        return emotes
    }

    suspend fun getGlobalEmotes(): HashSet<Emote> {
        val jsonElement = getRequest(URL(GLOBAL))
        val emotes = HashSet<Emote>()
        for (element in jsonElement.getAsJsonArray()) {
            val emoteJsonObj = element.getAsJsonObject()
            emotes.add(
                emoteOfJsonObj(
                    emoteJsonObj
                )
            )
        }
        return emotes
    }


    fun emoteOfJsonObj(jsonObj: JsonObject): Emote {
        return emoteOf(
            jsonObj.get("id").asString,
            jsonObj.get("code").asString,
            jsonObj.get("imageType").asString == "gif",
        )
    }

    fun emoteOf(
        id: String, code: String, animated: Boolean
    ): Emote {
        val image: Image?
        val frames: List<Image>
        val frameDelays: List<Int>
        if (animated) {
            val gif =
              Util.GIFUtil.readGifFrames(
                    code,
                    URI(
                        EMOTE_3X
                            .replace("<EMOTE_ID>", id)
                    ).toURL()
                )

            frames = gif.frames.map { MapPalette.resizeImage(it) }
            frameDelays = gif.delays
            return GifEmote(code, frames, frameDelays)
        } else {
            val staticImage = ImageIO.read(
                URL(
                    EMOTE_3X.replace("<EMOTE_ID>", id)
                )
            )
            image = MapPalette.resizeImage(staticImage)
            return StaticEmote(code, image)
        }
    }

}