package de.crazj.sprayz.cmd

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import de.crazj.sprayz.Permission
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.spray.ImageRenderer
import de.crazj.sprayz.spray.PacketUtil
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.datacomponent.item.MapId.mapId
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import kotlin.random.Random

class SprayZCommand {

    val commandNode: LiteralCommandNode<CommandSourceStack> = Commands.literal("sprayz").then(
        Commands.literal("map").then(
            Commands.argument(
                "emote", StringArgumentType.greedyString()
            ).requires { it.sender.hasPermission(Permission.SPRAYZ_COMMAND_MAP.full) }.suggests { _, builder ->
                SprayZ.instance.emoteManager.getAllEmotes().keys.forEach { emoteName ->
                    if (emoteName.lowercase().startsWith(builder.remaining.lowercase(), ignoreCase = true)) {
                        builder.suggest(emoteName)
                    }
                }
                builder.buildFuture()
            }.executes { ctx ->
                val sender = ctx.source.sender
                if (sender !is Player) {
                    SprayZ.instance.log(
                        ChatColor.RED.toString() + "Um dir eine Emote Map zu geben musst du ein Spieler " + "sein"
                    )
                    return@executes 1
                }
                val emoteName = StringArgumentType.getString(ctx, "emote")
                val emotes = SprayZ.instance.emoteManager.getAllEmotes()

                val img = emotes[emoteName]
                if (img == null) {
                    sender.sendMessage(
                        ChatColor.RED.toString() + "[SprayZ] Der Emote " + emoteName + " konnte nicht gefunden " + "werden"
                    )
                    return@executes 1
                }
                val em = SprayZ.instance.emoteManager
                val item = em.map(emoteName)
                em.setMapContent(sender, item, ImageRenderer(img))
                sender.inventory.addItem(item)
                return@executes 1
            })
    ).then(Commands.literal("test").executes { ctx ->
        val sender = ctx.source.sender
        if (sender !is Player) {
            SprayZ.instance.log(
                ChatColor.RED.toString() + "Um den Test Command zu benutzen musst du ein Spieler " + "sein"
            )
            return@executes 1
        }

        sender.sendMessage(ChatColor.GREEN.toString() + "Der Test Command wird ausgeführt!")

        val emote = SprayZ.instance.emoteManager.getAllEmotes().entries.random()
        val img = emote.value
        var mapId: Int
        do {
            mapId = Random.nextInt() + 100_000 // 99.9% safe like this
        } while (mapId == Int.MAX_VALUE || Bukkit.getMap(mapId) != null)

        PacketUtil.sendMapItemFrame(sender, img, mapId)
        return@executes 1
    }).build()


}
