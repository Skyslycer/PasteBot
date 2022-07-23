package de.skyslycer.paste

import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.DiscordPartialEmoji
import dev.kord.common.entity.PresenceStatus
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.Intents
import dev.kord.gateway.PrivilegedIntent
import dev.kord.rest.builder.message.create.actionRow
import io.github.cdimascio.dotenv.dotenv
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.component1
import kotlin.collections.component2

private val dotenv = dotenv()
private val logger = KotlinLogging.logger {}

const val SENTRY_DSN = "SENTRY_DSN"
const val ENVIRONMENT = "ENVIRONMENT"
const val BOT_TOKEN = "BOT_TOKEN"

const val POST_URL = "https://pasteapi.skyslycer.de/post"
const val CODE_URL = "https://paste.skyslycer.de/"

val CODE_BLOCK_REGEX: Pattern =
    Pattern.compile("(?<block>```(?<lang>[a-zA-Z]+)?\\n?(?<content>(?:\\n|.)*?)```)")

suspend fun main() {
    logger.info { "Starting PasteBot..." }
    logger.info { "Initiating Sentry..." }
    Sentry.init {
        it.environment = getEnv(ENVIRONMENT)
        it.dsn = getEnv(SENTRY_DSN)
        it.tracesSampleRate = 0.5
    }
    logger.info { "Sentry initiated!" }
    PasteBot().main()
}

@OptIn(PrivilegedIntent::class)
class PasteBot {

    suspend fun main() {
        logger.info { "Initiating Discord Bot..." }
        val kord = Kord(getEnv(BOT_TOKEN))

        kord.on<MessageCreateEvent> {
            if (message.author == null || message.author?.isBot == true) return@on
            val codeBlocks = computeCodeblocks(message)
            val attachments = computeAttachments(message)
            if (codeBlocks.isNotEmpty()) {
                var count = 0
                val newContent =
                    "**I converted your codeblock(s):** \n" + codeBlocks.joinToString("\n") { url ->
                        "`${++count}`: $url"
                    }
                sendDeletableMessage(message.channel, newContent, message.author!!)
            }
            if (attachments.isNotEmpty()) {
                val files =
                    "**I converted your attachment(s):** \n" + attachments.joinToString("\n") {
                        "`${it.name}`: ${it.url}"
                    }
                sendDeletableMessage(message.channel, files, message.author!!)
            }
        }

        kord.on<ButtonInteractionCreateEvent> {
            if (interaction.component.customId!! == "delete:${interaction.user.id}") {
                interaction.message.channel.deleteMessage(interaction.message.id, "Button deletion")
            } else if (interaction.component.customId!! == "name") {
                interaction.deferPublicMessageUpdate()
            } else if (interaction.component.customId!!.startsWith("delete:")) {
                interaction.respondEphemeral {
                    content = "❗**You can't delete this message!**❗"
                }
            }
        }

        logger.info { "Discord Bot initiated!" }
        statusChange(kord)
        kord.login {
            intents {
                +Intents.nonPrivileged
                +Intent.MessageContent
            }
        }
    }

    private fun computeCodeblocks(message: Message): MutableSet<String> {
        val codeblocks = mutableSetOf<String>()
        val matcher = CODE_BLOCK_REGEX.matcher(message.content)
        while (matcher.find()) {
            val match = CodeBlockMatch(
                matcher.group("lang") ?: "plain",
                matcher.group("content"),
                matcher.group("block")
            )
            if (match.content.split("\n").size >= 4 && match.content.length >= 200) {
                upload(match.content, match.language).ifPresent {
                    codeblocks.add(it)
                }
            }
        }
        return codeblocks
    }

    private fun computeAttachments(message: Message): MutableSet<Attachment> {
        val attachments = mutableSetOf<Attachment>()
        message.attachments.forEach {
            if (it.contentType?.startsWith("text/") == true) {
                var language = "plain"
                fileTypes.forEach { (extension, type) ->
                    if (it.filename.contains(extension)) {
                        language = type
                    }
                }
                upload(download(it.url), language).ifPresent { url ->
                    attachments.add(Attachment(it.filename, url))
                }
            }
        }
        return attachments
    }

    private suspend fun sendDeletableMessage(
        channel: MessageChannelBehavior,
        message: String,
        author: User
    ) {
        channel.createMessage {
            content = message
            actionRow {
                interactionButton(ButtonStyle.Primary, "name") {
                    label = "@" + author.username
                }
                interactionButton(ButtonStyle.Danger, "delete:${author.id}") {
                    emoji = DiscordPartialEmoji(name = "🗑️")
                }
            }
        }
    }

    private fun upload(text: String, language: String): Optional<String> {
        val client = java.net.http.HttpClient.newHttpClient()
        val request = client.send(
            HttpRequest.newBuilder().headers(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                "Content-Type",
                "text/$language"
            ).uri(URI.create(POST_URL)).POST(HttpRequest.BodyPublishers.ofString(text)).build(),
            HttpResponse.BodyHandlers.ofString()
        )
        var key: String = request.body()
        if (key.contains("Request Entity Too Large")) return  Optional.empty()
        key = key.replace("{\"key\":\"", "")
        key = key.replace("\"}", "")
        if (key == "Missing content") return Optional.empty()
        return Optional.of("<$CODE_URL$key>")
    }

    private fun download(url: String): String {
        val client = java.net.http.HttpClient.newHttpClient()
        val request = client.send(
            HttpRequest.newBuilder().header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0"
            ).uri(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        return request.body()
    }

    private fun statusChange(kord: Kord) {
        CoroutineScope(Dispatchers.Default).launch {
            var cycle = false

            while (true) {
                var guilds = 0
                kord.guilds.collect { guilds++ }

                kord.editPresence {
                    status = PresenceStatus.DoNotDisturb
                    when (cycle) {
                        false -> listening("$guilds guilds")
                        true -> listening("files and codeblocks!")
                    }
                }

                cycle = !cycle
                delay(1000L * 60L)
            }
        }
    }

}

data class CodeBlockMatch(
    val language: String,
    val content: String,
    val block: String
)

fun getEnv(key: String): String {
    return dotenv[key] ?: System.getenv(key)
    ?: error("A required environment variable wasn't found: $key \nPlease add this and restart the bot.")
}

val fileTypes = mapOf(
    ".yaml" to "yaml",
    ".yml" to "yaml",
    ".xml" to "xml",
    ".ini" to "ini",
    ".java" to "java",
    ".js" to "javascript",
    ".ts" to "typescript",
    ".py" to "python",
    ".kt" to "kotlin",
    ".cpp" to "cpp",
    ".cs" to "csharp",
    ".sh" to "shell",
    ".rb" to "ruby",
    ".rs" to "rust",
    ".sql" to "sql",
    ".go" to "go",
    ".html" to "html",
    ".css" to "css",
    ".php" to "php",
    "Dockerfile" to "dockerfile",
    ".md" to "markdown"
)

data class Attachment(
    val name: String,
    val url: String
)