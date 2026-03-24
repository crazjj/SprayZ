package de.crazj.sprayz.cmd

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.Permission
import de.crazj.sprayz.SprayZ
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import java.awt.Image

class SprayZCommand {

    val commandNode: LiteralCommandNode<CommandSourceStack> =
        Commands.literal("sprayz").then(
            Commands.literal("spray").then(
                Commands.argument(
                    "emote", StringArgumentType.greedyString()
                ).requires { it.sender.hasPermission(Permission.SPRAYZ_USE.full) }.suggests { _, builder ->
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
                            ChatColor.RED.toString() + "Um ein Spray einzusetzen musst du Spieler sein"
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


                    SprayZ.instance.sprayManager.rayTraceSpray(
                        sender,
                        SprayZ.instance.config.getDouble(ConfPath.SPRAY_RANGE.path)
                    )
                    return@executes 1
                })
        )
            .then(
                Commands.literal("refresh").requires { it.sender.hasPermission(Permission.REFRESH.full) }.executes {
                    SprayZ.instance.emoteManager.refresh()
                    it.source.sender.sendMessage(NamedTextColor.GREEN.toString() + "Alle Sprays wurden aktualisiert")
                    1
                }
            ).then(Commands.literal("toggle").executes { ctx ->
                if (ctx.source.sender !is Player) {
                    ctx.source.sender.sendMessage(
                        ChatColor.RED.toString() + "Um den SprayZ Modus zu toggeln musst du ein Spieler " + "sein"
                    )
                    return@executes 1
                }
                val p = ctx.source.sender as Player
                val sm = SprayZ.instance.sprayManager
                if (sm.hideFrom.contains(p.uniqueId)) {
                    sm.hideFrom.remove(p.uniqueId)
                    ctx.source.sender.sendMessage(ChatColor.GREEN.toString() + "Sprays werden dir wieder angezeigt")
                } else {
                    sm.hideFrom.add(p.uniqueId)
                    ctx.source.sender.sendMessage(ChatColor.RED.toString() + "Sprays werden nicht mehr angezeigt")
                }
                sm.refreshAllToPlayer(p)
                return@executes Command.SINGLE_SUCCESS
            })
            .then(
                Commands.literal("mapItem").then(
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
                        val item = SprayZ.instance.emoteManager.mapItem(emoteName)
                        SprayZ.instance.emoteManager.setMapContent(sender, item, ImageRenderer(emote.anyFrame()))
                        sender.inventory.addItem(item)
                        return@executes 1
                    })
            ).build()

}

class ImageRenderer(private var image: Image) : MapRenderer() {

    override fun render(view: MapView, canvas: MapCanvas, player: Player) {
        canvas.drawImage(0, 0, image)
        view.isTrackingPosition = false
        view.scale = MapView.Scale.FARTHEST
    }
}