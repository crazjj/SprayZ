package de.crazj.sprayz.cmd

import com.mojang.brigadier.arguments.StringArgumentType
import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.cmd.CommandFeedback.detail
import de.crazj.sprayz.cmd.CommandFeedback.error
import de.crazj.sprayz.cmd.CommandFeedback.info
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender

internal fun SprayZAdminCommands.buildSprayZConfigCommand() =
    adminListCommand("config", "/sprayz config", SprayZAdminCommands::sendConfigList)
        .then(
            Commands.literal("get")
                .then(
                    Commands.argument("key", StringArgumentType.word())
                        .suggests { _, builder ->
                            suggestFuzzy(editableSettings.map(ConfPath::path), builder.remaining, builder::suggest)
                            builder.buildFuture()
                        }
                        .executes { ctx ->
                            val sender = ctx.source.sender
                            val key = StringArgumentType.getString(ctx, "key")
                            logAdminCommand(sender, "/sprayz config get $key")
                            val setting = editableSetting(key)
                                ?: return@executes sender.send(error("Unknown config key '$key'."))

                            sender.sendMessages(
                                listOf(
                                    info("${setting.path} = ${formatConfigValue(setting.get())}"),
                                    detail(setting.description),
                                ) + listOfNotNull(
                                    detail("Changing this value automatically starts a BTTV refresh.")
                                        .takeIf { setting.requiresBTTVRefresh }
                                )
                            )
                            1
                        }
                )
        )
        .then(
            Commands.literal("set")
                .then(
                    Commands.argument("key", StringArgumentType.word())
                        .suggests { _, builder ->
                            suggestFuzzy(editableSettings.map(ConfPath::path), builder.remaining, builder::suggest)
                            builder.buildFuture()
                        }
                        .then(
                            Commands.argument("value", StringArgumentType.word())
                                .suggests { ctx, builder ->
                                    editableSetting(StringArgumentType.getString(ctx, "key"))?.let { setting ->
                                        suggestMatching(
                                            setting.suggestions + formatConfigValue(setting.get()),
                                            builder.remaining,
                                            builder::suggest,
                                        )
                                    }
                                    builder.buildFuture()
                                }
                                .executes { ctx ->
                                    logAdminCommand(
                                        ctx.source.sender,
                                        "/sprayz config set ${StringArgumentType.getString(ctx, "key")} ${StringArgumentType.getString(ctx, "value")}"
                                    )
                                    updateConfigValue(
                                        ctx.source.sender,
                                        StringArgumentType.getString(ctx, "key"),
                                        StringArgumentType.getString(ctx, "value"),
                                    )
                                }
                        )
                )
        )

private fun SprayZAdminCommands.updateConfigValue(sender: CommandSender, key: String, rawValue: String): Int {
    val setting = editableSetting(key) ?: return sender.send(error("Unknown config key '$key'."))
    val parsedValue = try {
        setting.parse(rawValue)
    } catch (ex: IllegalArgumentException) {
        return sender.send(error(ex.message ?: "Invalid value for '$key'."))
    }

    return persistConfigChange(
        sender = sender,
        confPath = setting,
        newValue = parsedValue,
        successText = "Set ${setting.path} from ${formatConfigValue(setting.get())} to ${formatConfigValue(parsedValue)}.",
    )
}

private fun SprayZAdminCommands.sendConfigList(sender: CommandSender) {
    sender.send(info("Editable config values (${editableSettings.size}):"))
    sender.sendBulletEntries(
        editableSettings.map { setting ->
            Component.text(setting.path, NamedTextColor.AQUA)
                .append(Component.text(" = ", NamedTextColor.DARK_GRAY))
                .append(Component.text(formatConfigValue(setting.get()), NamedTextColor.WHITE))
                .append(Component.text(" (${setting.description})", NamedTextColor.GRAY))
        }
    )
    sender.sendMessages(
        listOf(
            info("Manage BTTV channels with /sprayz channels list|add|remove."),
            detail("Read a single value with /sprayz config get <key> and change it with /sprayz config set <key> <value>."),
        )
    )
}
