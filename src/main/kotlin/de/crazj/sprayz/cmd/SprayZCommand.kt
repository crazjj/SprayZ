package de.crazj.sprayz.cmd

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import de.crazj.sprayz.Permission
import de.crazj.sprayz.SprayZ
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.ChatColor
import org.bukkit.entity.Player

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

                val emote = emotes[emoteName]
                if (emote == null) {
                    sender.sendMessage(
                        ChatColor.RED.toString() + "[SprayZ] Der Emote " + emoteName + " konnte nicht gefunden " + "werden"
                    )
                    return@executes 1
                }

                sender.inventory.addItem(SprayZ.instance.emoteManager.map(sender, emoteName, emote))
                return@executes 1
            })
    ).build()


}
