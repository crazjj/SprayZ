package de.crazj.sprayz.bttv

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import de.crazj.sprayz.SprayZ
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.bukkit.ChatColor
import org.bukkit.map.MapPalette
import java.awt.Image
import java.io.IOException
import java.net.MalformedURLException
import java.net.URI
import java.net.URL
import javax.imageio.ImageIO

object BTTVAPI {


    private const val GLOBAL = "https://api.betterttv.net/3/cached/emotes/global"
    private const val CHANNEL_EMOTES = "https://api.betterttv.net/3/cached/users/twitch/<TWITCH_USER_ID>"
    private const val EMOTE_3X = "https://cdn.betterttv.net/emote/<EMOTE_ID>/3x"

    suspend fun getChannelEmotes(userID: String): HashSet<Emote> {
        val url = URI(CHANNEL_EMOTES.replace("<TWITCH_USER_ID>", userID)).toURL()
        val jsonObject = getRequest(url).getAsJsonObject()
        val channelJsonArray = jsonObject.getAsJsonArray("channelEmotes")
        val sharedJsonArray = jsonObject.getAsJsonArray("sharedEmotes")
        val emotes = HashSet<Emote>()


        for (element in channelJsonArray) {
            val elementJsonObj = element.getAsJsonObject()
            emotes.add(
                Emote(
                    elementJsonObj.get("id").asString,
                    elementJsonObj.get("code").asString,
                    elementJsonObj.get("imageType").asString == "gif",
                    elementJsonObj.get("userId").asString
                )
            )
        }
        for (element in sharedJsonArray) {
            val elementJsonObj = element.getAsJsonObject()
            emotes.add(
                Emote(
                    elementJsonObj.get("id").asString,
                    elementJsonObj.get("code").asString,
                    elementJsonObj.get("imageType").asString == "gif",
                    elementJsonObj.getAsJsonObject("user").get("id").asString
                )
            )
        }

        return emotes
    }


    suspend fun getGlobalEmotes(): HashSet<Emote> {
        val jsonElement = getRequest(URL(GLOBAL))
        val emotes = HashSet<Emote>()
        for (element in jsonElement.getAsJsonArray()) {
            val jsonObject = element.getAsJsonObject()
            emotes.add(
                Emote(
                    jsonObject.get("id").asString,
                    jsonObject.get("code").asString,
                    jsonObject.get("imageType").asString == "gif",
                    jsonObject.get("userId").asString
                )
            )
        }
        return emotes
    }


    suspend fun getRequest(url: URL): JsonElement {
        val response = HttpClient(CIO).get(url)
        if (response.status.value != 200) {
            SprayZ.instance.log(
                ChatColor.RED.toString() + "ERROR while getting emotes from url: " + url.toString()
                        + " Response code: " + response.status.value
            )
        }
        val text = response.bodyAsText()
        return JsonParser.parseString(text)
    }


    class Emote(val id: String, val code: String, val animated: Boolean, val userID: String?) {

        val image: Image?

        init {
            var image: Image? = null
            try {
                image = ImageIO.read(URL(EMOTE_3X.replace("<EMOTE_ID>", id)))
                image = MapPalette.resizeImage(image)
            } catch (_: MalformedURLException) {
            } catch (e: IOException) {
                SprayZ.instance.log(
                    (ChatColor.RED.toString() + "ERROR while getting emote "
                            + "image of id: " + id)
                )
                e.printStackTrace()
            } finally {
                this.image = image!!
            }
        }
    }

}


