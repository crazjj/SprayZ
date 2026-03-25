package de.crazj.sprayz.cmd

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import de.crazj.sprayz.ConfPath
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.cmd.CommandFeedback.detail
import de.crazj.sprayz.cmd.CommandFeedback.error
import de.crazj.sprayz.cmd.CommandFeedback.info
import de.crazj.sprayz.cmd.CommandFeedback.success
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender

internal class SprayZAdminCommands {
    internal val editableSettings = ConfPath.editableEntries

    fun buildConfigCommand() = buildSprayZConfigCommand()

    fun buildChannelsCommand() = buildSprayZChannelsCommand()

    fun buildFileEmotesCommand() = buildSprayZFileEmotesCommand()

    internal fun persistConfigChange(
        sender: CommandSender,
        confPath: ConfPath,
        newValue: Any?,
        successText: String,
    ): Int {
        val previousValue = confPath.get()
        return persistChange(
            sender = sender,
            successText = successText,
            failureText = "Could not save config value",
            rollback = { confPath.set(previousValue) },
            apply = { confPath.set(newValue) },
            requiresBTTVRefresh = confPath.requiresBTTVRefresh,
            refreshText = "BTTV refresh started so the change takes effect immediately."
                .takeIf { confPath.requiresBTTVRefresh },
        )
    }

    internal fun persistChannelChange(sender: CommandSender, channels: List<String>, successText: String): Int {
        val previousChannels = getConfiguredChannels()
        return persistChange(
            sender = sender,
            successText = successText,
            failureText = "Could not save channel list",
            rollback = { ConfPath.BTTV_CHANNELS.set(previousChannels) },
            apply = { ConfPath.BTTV_CHANNELS.set(channels) },
            requiresBTTVRefresh = true,
            extraSuccessMessages = listOf(detail("Currently configured channels: ${channels.size}")),
            refreshText = "BTTV refresh started so the channel list is reloaded.",
        )
    }

    private fun persistChange(
        sender: CommandSender,
        successText: String,
        failureText: String,
        rollback: () -> Unit,
        apply: () -> Unit,
        requiresBTTVRefresh: Boolean = false,
        extraSuccessMessages: List<Component> = emptyList(),
        refreshText: String? = null,
    ): Int {
        return runCatching {
            apply()
            SprayZ.instance.saveConfig()
            if (requiresBTTVRefresh) {
                SprayZ.instance.emoteManager.refresh()
            }
        }.fold(
            onSuccess = {
                sender.sendMessages(
                    listOf(success(successText)) +
                        extraSuccessMessages +
                        listOfNotNull(refreshText?.let(::info))
                )
                1
            },
            onFailure = { ex ->
                rollback()
                sender.send(error("$failureText: ${ex.message ?: "unknown error"}"))
            }
        )
    }
}

internal fun SprayZAdminCommands.adminListCommand(
    literal: String,
    baseCommand: String,
    sendList: SprayZAdminCommands.(CommandSender) -> Unit,
): LiteralArgumentBuilder<CommandSourceStack> {
    return Commands.literal(literal)
        .requires { it.sender.hasPermission(de.crazj.sprayz.Permission.CONFIG.full) }
        .executes { ctx -> sendAdminList(ctx.source.sender, baseCommand, sendList) }
        .then(
            Commands.literal("list")
                .executes { ctx -> sendAdminList(ctx.source.sender, "$baseCommand list", sendList) }
        )
}

private fun SprayZAdminCommands.sendAdminList(
    sender: CommandSender,
    command: String,
    sendList: SprayZAdminCommands.(CommandSender) -> Unit,
): Int = 1.also {
    logAdminCommand(sender, command)
    sendList(sender)
}
