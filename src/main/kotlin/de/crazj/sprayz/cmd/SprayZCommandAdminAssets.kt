package de.crazj.sprayz.cmd

import com.mojang.brigadier.arguments.StringArgumentType
import de.crazj.sprayz.SprayZ
import de.crazj.sprayz.Util.SearchUtil.resolveFuzzyCandidate
import de.crazj.sprayz.bttv.TwitchChannelResolverService
import de.crazj.sprayz.cmd.CommandFeedback.detail
import de.crazj.sprayz.cmd.CommandFeedback.error
import de.crazj.sprayz.cmd.CommandFeedback.info
import de.crazj.sprayz.cmd.CommandFeedback.success
import de.crazj.sprayz.spray.loadEmoteFromFile
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*

private val fileEmoteNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
private val supportedImageExtensions = setOf(".gif", ".png", ".jpg", ".jpeg")

internal fun SprayZAdminCommands.buildSprayZChannelsCommand() =
    adminListCommand("channels", "/sprayz channels", SprayZAdminCommands::sendChannelList)
        .then(
            Commands.literal("add")
                .then(
                    Commands.argument("channel", StringArgumentType.greedyString())
                        .executes { ctx ->
                            logAdminCommand(ctx.source.sender, "/sprayz channels add ${StringArgumentType.getString(ctx, "channel")}")
                            addChannel(ctx.source.sender, StringArgumentType.getString(ctx, "channel"))
                        }
                )
        )
        .then(
            Commands.literal("remove")
                .then(
                    Commands.argument("channel", StringArgumentType.greedyString())
                        .suggests { _, builder ->
                            suggestMatching(getConfiguredChannels(), builder.remaining, builder::suggest)
                            builder.buildFuture()
                        }
                        .executes { ctx ->
                            logAdminCommand(ctx.source.sender, "/sprayz channels remove ${StringArgumentType.getString(ctx, "channel")}")
                            removeChannel(ctx.source.sender, StringArgumentType.getString(ctx, "channel"))
                        }
                )
        )

internal fun SprayZAdminCommands.buildSprayZFileEmotesCommand() =
    adminListCommand("fileEmotes", "/sprayz fileEmotes", SprayZAdminCommands::sendFileEmoteList)
        .then(
            Commands.literal("add")
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .then(
                            Commands.argument("url", StringArgumentType.greedyString())
                                .executes { ctx ->
                                    val name = StringArgumentType.getString(ctx, "name")
                                    val url = StringArgumentType.getString(ctx, "url")
                                    logAdminCommand(ctx.source.sender, "/sprayz fileEmotes add $name $url")
                                    addFileEmote(ctx.source.sender, name, url)
                                }
                        )
                )
        )
        .then(
            Commands.literal("remove")
                .then(
                    Commands.argument("name", StringArgumentType.greedyString())
                        .suggests { _, builder ->
                            suggestFuzzy(localFileEmoteNames(), builder.remaining, builder::suggest)
                            builder.buildFuture()
                        }
                        .executes { ctx ->
                            val name = StringArgumentType.getString(ctx, "name")
                            logAdminCommand(ctx.source.sender, "/sprayz fileEmotes remove $name")
                            removeFileEmote(ctx.source.sender, name)
                        }
                )
        )

private fun SprayZAdminCommands.addChannel(sender: CommandSender, rawChannel: String): Int {
    val normalizedChannel = TwitchChannelResolverService.normalizeConfigChannelEntry(rawChannel)
        ?: return sender.send(error("Invalid channel. Allowed values are a Twitch login, Twitch URL, or numeric user ID."))

    val currentChannels = getConfiguredChannels().toMutableList()
    if (currentChannels.any { TwitchChannelResolverService.normalizeConfigChannelEntry(it) == normalizedChannel }) {
        return sender.send(info("BTTV channel '$normalizedChannel' is already configured."))
    }

    currentChannels.add(normalizedChannel)
    return persistChannelChange(sender, currentChannels, "Added BTTV channel '$normalizedChannel'.")
}

private fun SprayZAdminCommands.removeChannel(sender: CommandSender, rawChannel: String): Int {
    val normalizedChannel = TwitchChannelResolverService.normalizeConfigChannelEntry(rawChannel)
        ?: return sender.send(error("Invalid channel. Allowed values are a Twitch login, Twitch URL, or numeric user ID."))

    val currentChannels = getConfiguredChannels()
    val updatedChannels = currentChannels.filterNot { channel ->
        TwitchChannelResolverService.normalizeConfigChannelEntry(channel) == normalizedChannel
    }

    if (updatedChannels.size == currentChannels.size) {
        return sender.send(info("BTTV channel '$normalizedChannel' is not configured."))
    }

    return persistChannelChange(sender, updatedChannels, "Removed BTTV channel '$normalizedChannel'.")
}

private fun SprayZAdminCommands.addFileEmote(sender: CommandSender, name: String, rawUrl: String): Int {
    if (!fileEmoteNamePattern.matches(name)) {
        return sender.send(
            error("Invalid file emote name '$name'. Use 1-64 characters: letters, numbers, dot, underscore, or dash.")
        )
    }

    val url = try {
        parseSupportedUrl(rawUrl)
    } catch (ex: IllegalArgumentException) {
        return sender.send(error(ex.message ?: "Invalid download URL."))
    }

    sender.send(info("Downloading file emote '$name' from $url ..."))
    Bukkit.getScheduler().runTaskAsynchronously(SprayZ.instance, Runnable {
        val result = runCatching { downloadAndStoreFileEmote(name, url) }
        Bukkit.getScheduler().runTask(SprayZ.instance, Runnable {
            result.fold(
                onSuccess = { storedFile ->
                    SprayZ.instance.emoteManager.refresh()
                    sender.sendMessages(
                        listOf(
                            success("Saved file emote '$name' as ${storedFile.name}."),
                            info("Emote refresh started so the new file emote is loaded."),
                        )
                    )
                },
                onFailure = { ex ->
                    sender.send(error("Could not add file emote '$name': ${ex.message ?: "unknown error"}"))
                }
            )
        })
    })
    return 1
}

private fun SprayZAdminCommands.removeFileEmote(sender: CommandSender, query: String): Int {
    val resolvedName = resolveFuzzyCandidate(localFileEmoteNames(), query)
        ?: return sender.send(error("No local file emote matched '$query'."))

    val matchingFiles = localFileEmoteFiles().filter { it.nameWithoutExtension.equals(resolvedName, ignoreCase = true) }
    if (matchingFiles.isEmpty()) {
        return sender.send(error("Local file emote '$resolvedName' could not be found."))
    }

    return runCatching {
        matchingFiles.forEach { file ->
            if (file.exists() && !file.delete()) {
                error("Could not delete ${file.name}")
            }
        }
        SprayZ.instance.emoteManager.refresh()
    }.fold(
        onSuccess = {
            sender.sendMessages(
                listOf(
                    success("Removed local file emote '$resolvedName'."),
                    info("Emote refresh started so the removed file emote disappears."),
                )
            )
            1
        },
        onFailure = { ex ->
            sender.send(error("Could not remove local file emote '$resolvedName': ${ex.message ?: "unknown error"}"))
        }
    )
}

private fun SprayZAdminCommands.sendChannelList(sender: CommandSender) {
    val channels = getConfiguredChannels()
    if (channels.isEmpty()) {
        sender.send(info("No BTTV channels are currently configured."))
        return
    }

    sender.send(info("Configured BTTV channels (${channels.size}):"))
    sender.sendBulletEntries(channels.map { channel -> Component.text(channel, NamedTextColor.AQUA) })
    sender.send(detail("Add channels with /sprayz channels add <channel> and remove them with /sprayz channels remove <channel>."))
}

private fun SprayZAdminCommands.sendFileEmoteList(sender: CommandSender) {
    val emoteFiles = localFileEmoteFiles()
    if (emoteFiles.isEmpty()) {
        sender.send(info("No local file emotes are currently stored."))
        return
    }

    sender.send(info("Local file emotes (${emoteFiles.size} file(s), ${localFileEmoteNames().size} unique names):"))
    sender.sendBulletEntries(
        emoteFiles.sortedBy { it.name.lowercase(Locale.ROOT) }.map { file ->
            Component.text(file.nameWithoutExtension, NamedTextColor.AQUA)
                .append(Component.text(" (${file.name})", NamedTextColor.GRAY))
        }
    )
    sender.send(detail("Add file emotes with /sprayz fileEmotes add <name> <url> and remove them with /sprayz fileEmotes remove <name>."))
}

private fun SprayZAdminCommands.localFileEmoteFiles(): List<File> =
    sprayFolder().listFiles().orEmpty().filter(File::isFile).sortedBy { it.name }

private fun SprayZAdminCommands.localFileEmoteNames(): List<String> =
    localFileEmoteFiles().map { it.nameWithoutExtension }.distinct()

private fun sprayFolder(): File = File(SprayZ.instance.dataFolder, "sprays").apply { mkdirs() }

private fun parseSupportedUrl(rawUrl: String): String {
    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
        ?: throw IllegalArgumentException("Invalid URL '$rawUrl'.")
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme !in setOf("http", "https")) {
        throw IllegalArgumentException("Only http and https URLs are supported.")
    }
    return uri.toString()
}

private fun SprayZAdminCommands.downloadAndStoreFileEmote(name: String, url: String): File {
    val sprayFolder = sprayFolder()
    val uri = URI(url)
    val connection = (uri.toURL().openConnection() as? HttpURLConnection)
        ?: throw IllegalStateException("Could not open an HTTP connection for '$url'.")

    val temporaryFile = File.createTempFile("$name-", ".download", sprayFolder)
    try {
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", "SprayZ/1.0")

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IllegalStateException("Download failed with HTTP $responseCode.")
        }

        connection.inputStream.use { input ->
            temporaryFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        loadEmoteFromFile(name, temporaryFile)

        val extension = determineImageExtension(connection.contentType, uri.path)
            ?: throw IllegalStateException("The downloaded file must be a supported image or GIF.")

        val targetFile = File(sprayFolder, "$name$extension")
        localFileEmoteFiles()
            .filter { it.nameWithoutExtension.equals(name, ignoreCase = true) && it != targetFile }
            .forEach { existingFile ->
                if (existingFile.exists() && !existingFile.delete()) {
                    throw IllegalStateException("Could not replace existing file emote '${existingFile.name}'.")
                }
            }

        Files.move(
            temporaryFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )

        return targetFile
    } finally {
        connection.disconnect()
        if (temporaryFile.exists()) {
            temporaryFile.delete()
        }
    }
}

private fun determineImageExtension(contentType: String?, path: String?): String? {
    val normalizedContentType = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)

    val extensionFromType = when (normalizedContentType) {
        "image/gif" -> ".gif"
        "image/png" -> ".png"
        "image/jpeg", "image/jpg" -> ".jpg"
        else -> null
    }
    if (extensionFromType != null) return extensionFromType

    val lowerPath = path?.lowercase(Locale.ROOT).orEmpty()
    return supportedImageExtensions.firstOrNull(lowerPath::endsWith)
}
