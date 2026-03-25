package de.crazj.sprayz.cmd

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.Permission
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.Util.SearchUtil.fuzzyCandidates
import de.crazj.sprayz.Util.SearchUtil.resolveFuzzyCandidate
import de.crazj.sprayz.cmd.CommandFeedback.detail
import de.crazj.sprayz.cmd.CommandFeedback.error
import de.crazj.sprayz.cmd.CommandFeedback.help
import de.crazj.sprayz.cmd.CommandFeedback.info
import de.crazj.sprayz.cmd.CommandFeedback.success
import de.crazj.sprayz.cmd.CommandFeedback.warning
import de.crazj.sprayz.spray.Emote
import de.crazj.sprayz.spray.SprayManager
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

class SprayZCommand {
    private val adminCommands = SprayZAdminCommands()

    val commandNode: LiteralCommandNode<CommandSourceStack> = Commands
        .literal("sprayz")
        .executes { ctx ->
            sendHelp(ctx.source.sender)
            1
        }
        .then(buildSprayCommand())
        .then(buildRefreshCommand())
        .then(buildToggleCommand())
        .then(buildMapItemCommand())
        .then(adminCommands.buildConfigCommand())
        .then(adminCommands.buildChannelsCommand())
        .then(adminCommands.buildFileEmotesCommand())
        .build()

    private fun buildSprayCommand() =
        Commands.literal("spray")
            .requires { it.sender.hasPermission(Permission.SPRAYZ_USE.full) }
            .then(
                emoteArgument().executes { ctx ->
                    val sender = ctx.source.sender
                    sender.withPlayerEmoteEntry(
                        query = StringArgumentType.getString(ctx, "emote"),
                        playerError = "You must be a player to place a spray.",
                    ) { player, emoteEntry ->
                        sender.send(
                            componentForSprayAttempt(
                                SprayZ.instance.sprayManager.rayTraceSpray(
                                    player,
                                    ConfPath.SPRAY_RANGE.getDouble(),
                                    emoteEntry,
                                )
                            )
                        )
                    }
                }
            )

    private fun buildRefreshCommand() =
        Commands.literal("refresh")
            .requires { it.sender.hasPermission(Permission.REFRESH.full) }
            .executes { ctx ->
                logAdminCommand(ctx.source.sender, "/sprayz refresh")
                SprayZ.instance.emoteManager.refresh()
                ctx.source.sender.send(success("Refresh started. BTTV caches will be reused."))
            }
            .then(
                Commands.literal("force")
                    .executes { ctx ->
                        logAdminCommand(ctx.source.sender, "/sprayz refresh force")
                        SprayZ.instance.emoteManager.refresh(forceBTTVRefresh = true)
                        ctx.source.sender.send(
                            success("Forced refresh started. Resolver, ID, and image caches will be ignored.")
                        )
                    }
            )

    private fun buildToggleCommand() =
        Commands.literal("toggle")
            .requires { it.sender.hasPermission(Permission.SPRAYZ_USE.full) }
            .executes { ctx ->
                val sender = ctx.source.sender
                sender.playerOnly("You must be a player to toggle spray visibility.") { player ->
                    toggleSpraysFor(sender, player, logAsAdmin = false)
                }
            }
            .then(
                Commands.argument("player", StringArgumentType.word())
                    .suggests { _, builder ->
                        suggestFuzzy(Bukkit.getOnlinePlayers().map { it.name }, builder.remaining, builder::suggest)
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        val query = StringArgumentType.getString(ctx, "player")
                        val target = findOnlinePlayer(query)
                            ?: return@executes sender.send(error("No online player matched '$query'."))

                        val senderPlayer = sender as? Player
                        val isSelfToggle = senderPlayer?.uniqueId == target.uniqueId
                        if (!isSelfToggle && !sender.hasPermission(Permission.SPRAYZ_TOGGLE_OTHERS.full)) {
                            return@executes sender.send(error("You do not have permission to toggle sprays for other players."))
                        }

                        toggleSpraysFor(sender, target, logAsAdmin = !isSelfToggle)
                    }
            )

    private fun buildMapItemCommand() =
        Commands.literal("mapItem")
            .requires { it.sender.hasPermission(Permission.SPRAYZ_MAP_ITEM.full) }
            .then(
                emoteArgument().executes { ctx ->
                    val sender = ctx.source.sender
                    sender.withPlayerEmoteEntry(
                        query = StringArgumentType.getString(ctx, "emote"),
                        playerError = "You must be a player to receive an emote map.",
                    ) { player, (emoteName, emote) ->
                        if (player.inventory.firstEmpty() == -1) {
                            return@withPlayerEmoteEntry sender.send(warning("Your inventory is full. Free up a slot first."))
                        }

                        logAdminCommand(sender, "/sprayz mapItem $emoteName")
                        runCatching {
                            SprayZ.instance.mapItemService.createMapItem(emoteName, emote, player.world)
                        }.fold(
                            onSuccess = { item ->
                                val leftovers = player.inventory.addItem(item)
                                if (leftovers.isEmpty()) {
                                    sender.send(success("Added a persistent map item for '$emoteName'."))
                                } else {
                                    sender.send(error("The map item could not be added to your inventory."))
                                }
                            },
                            onFailure = { ex ->
                                SprayZ.instance.log("Could not create a persistent map item for '$emoteName': ${ex.message}")
                                sender.send(error("The map item could not be created: ${ex.message ?: "unknown error"}"))
                            }
                        )
                    }
                }
            )

    private fun sendHelp(sender: CommandSender) {
        sender.send(info("Available SprayZ commands:"))
        sendHelpEntries(
            sender,
            buildList {
                if (sender.hasPermission(Permission.SPRAYZ_USE.full)) {
                    add("/sprayz spray <emote>" to "Place the specified spray in front of you.")
                    add("/sprayz toggle" to "Hide or show all sprays for yourself.")
                    if (sender.hasPermission(Permission.SPRAYZ_TOGGLE_OTHERS.full)) {
                        add("/sprayz toggle <player>" to "Hide or show sprays for another online player.")
                    }
                }
                if (sender.hasPermission(Permission.SPRAYZ_MAP_ITEM.full)) {
                    add("/sprayz mapItem <emote>" to "Give yourself a map with the selected emote.")
                }
                if (sender.hasPermission(Permission.REFRESH.full)) {
                    add("/sprayz refresh [force]" to "Reload BTTV emotes, optionally without cache.")
                }
                if (sender.hasPermission(Permission.CONFIG.full)) {
                    add("/sprayz config list|get|set" to "View or change config values in-game.")
                    add("/sprayz channels list|add|remove" to "Manage BTTV channels in the config.")
                    add("/sprayz fileEmotes list|add|remove" to "Manage local file emotes from in-game.")
                }
            }
        )
        sender.send(detail("Currently loaded emotes: ${SprayZ.instance.emoteManager.getAllEmotes().size}"))
    }

    private fun toggleSpraysFor(sender: CommandSender, target: Player, logAsAdmin: Boolean): Int {
        val sprayManager = SprayZ.instance.sprayManager
        val hidden = target.uniqueId in sprayManager.hideFrom

        if (hidden) {
            sprayManager.hideFrom.remove(target.uniqueId)
        } else {
            sprayManager.hideFrom.add(target.uniqueId)
        }

        sprayManager.refreshAllToPlayer(target)

        if (sender is Player && sender.uniqueId == target.uniqueId) {
            return sender.send(
                if (hidden) {
                    success("Sprays are visible to you again.")
                } else {
                    info("Sprays are now hidden from you.")
                }
            )
        }

        if (logAsAdmin) {
            logAdminCommand(sender, "/sprayz toggle ${target.name}")
        }

        sender.send(
            if (hidden) {
                success("Sprays are visible to ${target.name} again.")
            } else {
                info("Sprays are now hidden from ${target.name}.")
            }
        )
        target.send(
            if (hidden) {
                success("Sprays were made visible to you again by ${sender.name}.")
            } else {
                warning("Sprays were hidden from you by ${sender.name}.")
            }
        )
        return Command.SINGLE_SUCCESS
    }
}

internal fun emoteArgument() =
    Commands.argument("emote", StringArgumentType.greedyString())
        .suggests { _, builder ->
            suggestFuzzy(SprayZ.instance.emoteManager.getAllEmotes().keys, builder.remaining, builder::suggest)
            builder.buildFuture()
        }

internal fun sendHelpEntries(sender: CommandSender, entries: Iterable<Pair<String, String>>) {
    sender.sendMessages(entries.map { (command, description) -> help(command, description) })
}

internal inline fun CommandSender.playerOnly(errorText: String, block: (Player) -> Int): Int {
    val player = this as? Player ?: return send(error(errorText))
    return block(player)
}

internal inline fun CommandSender.withEmoteEntry(query: String, block: (Pair<String, Emote>) -> Int): Int {
    val emoteEntry = SprayZ.instance.emoteManager.findEmoteEntry(query)
        ?: return send(error("Emote '$query' was not found."))
    return block(emoteEntry)
}

internal inline fun CommandSender.withPlayerEmoteEntry(
    query: String,
    playerError: String,
    block: (Player, Pair<String, Emote>) -> Int,
): Int = playerOnly(playerError) { player ->
    withEmoteEntry(query) { emoteEntry -> block(player, emoteEntry) }
}

internal fun CommandSender.send(component: Component): Int = 1.also { sendMessage(component) }

internal fun CommandSender.sendMessages(components: Iterable<Component>) = components.forEach(::sendMessage)

internal fun CommandSender.sendBulletEntries(components: Iterable<Component>) =
    components.forEach { sendMessage(CommandFeedback.bullet(it)) }

internal fun componentForSprayAttempt(result: SprayManager.SprayAttemptResult): Component = when (result) {
    is SprayManager.SprayAttemptResult.Success -> success("Placed spray '${result.emoteName}'.")
    SprayManager.SprayAttemptResult.NoTarget -> error("You must look at a solid block within range.")
    SprayManager.SprayAttemptResult.InvalidTarget -> error("You cannot place a spray on that block.")
    SprayManager.SprayAttemptResult.OccupiedTarget -> error("There is no free space in front of that block face.")
    SprayManager.SprayAttemptResult.SprayAlreadyPresent -> warning("A spray is already placed on that block face.")
    SprayManager.SprayAttemptResult.HiddenModeEnabled -> warning("You currently have sprays hidden. Use /sprayz toggle to show them again.")
    SprayManager.SprayAttemptResult.CooldownActive -> warning("You are still on spray cooldown.")
    SprayManager.SprayAttemptResult.NoEmotesLoaded -> error("No emotes are currently loaded.")
}

internal fun editableSetting(key: String): ConfPath? =
    resolveFuzzyCandidate(ConfPath.editableEntries.map(ConfPath::path), key)?.let(ConfPath.editableByPath::get)

internal fun getConfiguredChannels(): List<String> =
    ConfPath.BTTV_CHANNELS.getStringList().map(String::trim).filter(String::isNotBlank)

internal fun formatConfigValue(value: Any?): String = when (value) {
    is Boolean -> value.toString().lowercase(Locale.ROOT)
    is Number -> value.toString()
    is List<*> -> value.joinToString(", ") { it.toString() }
    null -> "null"
    else -> value.toString()
}

internal fun suggestMatching(values: Collection<String>, remaining: String, consumer: (String) -> Unit) {
    val normalizedRemaining = remaining.lowercase(Locale.ROOT)
    values.asSequence()
        .distinct()
        .filter { it.lowercase(Locale.ROOT).startsWith(normalizedRemaining) }
        .forEach(consumer)
}

internal fun suggestFuzzy(values: Collection<String>, remaining: String, consumer: (String) -> Unit) =
    fuzzyCandidates(values, remaining, limit = 25).forEach(consumer)

internal fun findOnlinePlayer(query: String): Player? =
    resolveFuzzyCandidate(Bukkit.getOnlinePlayers().map { it.name }, query)?.let(Bukkit::getPlayerExact)

internal fun logAdminCommand(sender: CommandSender, command: String) =
    SprayZ.instance.log("Admin command by ${sender.name}: $command")

object CommandFeedback {
    fun error(text: String): Component = prefixed(text, NamedTextColor.RED)

    fun success(text: String): Component = prefixed(text, NamedTextColor.GREEN)

    fun info(text: String): Component = prefixed(text, NamedTextColor.GRAY)

    fun warning(text: String): Component = prefixed(text, NamedTextColor.YELLOW)

    fun bullet(content: Component): Component {
        return Component.text(" - ", NamedTextColor.DARK_GRAY).append(content)
    }

    fun detail(text: String): Component {
        return bullet(Component.text(text, NamedTextColor.GRAY))
    }

    fun help(command: String, description: String): Component {
        return bullet(
            Component.text(command, NamedTextColor.AQUA)
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text(description, NamedTextColor.GRAY))
        )
    }

    private fun prefixed(text: String, color: NamedTextColor): Component {
        return SprayZ.PREFIX.append(Component.text(text, color))
    }
}
