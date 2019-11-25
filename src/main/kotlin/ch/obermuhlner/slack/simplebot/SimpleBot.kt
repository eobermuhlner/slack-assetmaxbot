package ch.obermuhlner.slack.simplebot

import ch.obermuhlner.slack.simplebot.impl.PropertiesTranslationServiceImpl
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.ullink.slack.simpleslackapi.SlackAttachment
import com.ullink.slack.simpleslackapi.SlackChannel
import com.ullink.slack.simpleslackapi.SlackSession
import com.ullink.slack.simpleslackapi.SlackUser
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import com.ullink.slack.simpleslackapi.impl.ChannelHistoryModuleFactory
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils
import java.io.BufferedReader
import java.io.FileReader
import java.io.PrintWriter
import java.io.StringWriter
import java.time.*
import java.time.format.DateTimeParseException
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.List
import kotlin.collections.MutableMap
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mutableMapOf
import kotlin.collections.set
import kotlin.collections.sorted

class SimpleBot(
		private val propertiesTranslations: PropertiesTranslationService = PropertiesTranslationServiceImpl()) {

	private lateinit var session: SlackSession
	private lateinit var user: SlackUser
	private lateinit var languages: List<String>
	private var adminUser: SlackUser? = null

	private val observedChannelIds = HashSet<String>()

	private val startMilliseconds = System.currentTimeMillis()
	private val explicitCommandCount : MutableMap<String, Int> = mutableMapOf()
	private val heuristicCommandCount : MutableMap<String, Int> = mutableMapOf()
	private val userCommandCount : MutableMap<String, Int> = mutableMapOf()

	private val commandHandlers: List<CommandHandler> = listOf(
			CommandHandler("help") { event, _, heuristic ->
				if (!heuristic) {
					respondHelp(event)
					true
				} else {
					false
				}
			}, CommandHandler("refresh") { event, _, heuristic ->
				if (!heuristic) {
					respond(event, "Refreshing information...")
					loadData()
					respondStatus(event)
					true
				} else {
					false
				}
			}, CommandHandler("status") { event, _, heuristic ->
				if (!heuristic) {
					respondStatus(event)
					true
				} else {
					false
				}
			}, CommandHandler("statistics") { event, _, heuristic ->
				if (!heuristic) {
					respondStatistics(event)
					true
				} else {
					false
				}
			}, CommandHandler("delete-all-messages") { event, _, heuristic ->
				if (!heuristic) {
					deleteAllMessages(event.channel)
					true
				} else {
					false
				}
			}, SingleArgumentCommandHandler("dec") { event, arg, heuristic ->
				if (!heuristic) {
					respondNumberConversion(event, arg.removeSuffix("L"), 10, introMessage = false)
				} else {
					false
				}
			}, SingleArgumentCommandHandler("hex") { event, arg, heuristic ->
				if (!heuristic) {
					if (arg.startsWith("-0x")) {
						respondNumberConversion(event, "-" + arg.removePrefix("-0x").removeSuffix("L"), 16, introMessage = false)
					} else {
						respondNumberConversion(event, arg.removePrefix("0x").removeSuffix("L"), 16, introMessage = false)
					}
				} else {
					false
				}
			}, SingleArgumentCommandHandler("bin") { event, arg, heuristic ->
				if (!heuristic) {
					if (arg.startsWith("-0b")) {
						respondNumberConversion(event, "-" + arg.removePrefix("-0b").removeSuffix("L"), 2, introMessage = false)
					} else {
						respondNumberConversion(event, arg.removePrefix("0b").removeSuffix("L"), 2, introMessage = false)
					}
				} else {
					false
				}
			}, SingleArgumentCommandHandler("number") { event, arg, heuristic ->
				if (arg.startsWith("0x")) {
					respondNumberConversion(event, arg.removePrefix("0x").removeSuffix("L"), 16, failMessage = !heuristic, introMessage = false)
				} else if (arg.startsWith("-0x")) {
					respondNumberConversion(event, "-" + arg.removePrefix("-0x").removeSuffix("L"), 16, failMessage = !heuristic, introMessage = false)
				} else if (arg.startsWith("0b")) {
					respondNumberConversion(event, arg.removePrefix("0b").removeSuffix("L"), 2, failMessage = !heuristic, introMessage = false)
				} else if (arg.startsWith("-0b")) {
					respondNumberConversion(event, "-" + arg.removePrefix("-0b").removeSuffix("L"), 2, failMessage = !heuristic, introMessage = false)
				} else {
					var success = false
					success = success or respondNumberConversion(event, arg.removeSuffix("L"), 10, failMessage = !heuristic)
					success = success or respondNumberConversion(event, arg.removeSuffix("L"), 16, failMessage = !heuristic)
					success = success or respondNumberConversion(event, arg.removeSuffix("L"), 2, failMessage = !heuristic)
					success
				}
            }, CommandHandler("millis") { event, args, heuristic ->
                if (!heuristic) {
                    var success = false
                    if (!success) {
                        if (args.isEmpty()) {
                            val dateTime = LocalDateTime.now()
                            val dateTimeAsMillis = dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
                            respond(event, "The current date time is `$dateTime` (in ${ZoneOffset.systemDefault()}) which corresponds to `$dateTimeAsMillis` milliseconds since epoch (1970-01-01).")
                            success = true
                        }
                    }
                    if (!success) {
                        val millis = args[0].toLongOrNull()
                        if (millis != null) {
                            val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.of("UTC"))
                            respond(event, "The decimal value `$millis` interpreted as milliseconds since epoch (1970-01-01) corresponds to `$dateTime` in UTC.")
                            success = true
                        }
                    }
                    if (!success) {
                        try {
                            val dateTime = LocalDateTime.parse(args[0])
                            val dateTimeAsMillis = dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
                            respond(event, "The UTC date time `$dateTime` corresponds to `$dateTimeAsMillis` milliseconds since epoch (1970-01-01).")
                            success = true
                        } catch (ex: DateTimeParseException) {
                            // ignore
                        }
                    }
                    if (!success) {
                        try {
                            val date = LocalDate.parse(args[0])
                            val dateAsMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                            respond(event, "The UTC date `$date` corresponds to `$dateAsMillis` milliseconds since epoch (1970-01-01).")
                            success = true
                        } catch (ex: DateTimeParseException) {
                            // ignore
                        }
                    }
                    if (!success) {
                        respond(event, """
                                    |`${args[0]}` is not a valid value for milliseconds/date time conversion.
                                    |It must be either a milliseconds value (like `${System.currentTimeMillis()}`)
                                    |or a date like `${LocalDate.now()}`
                                    |or a date time like `${LocalDateTime.now()}`.""".trimMargin())
                        success = true
                    }
                    success
                } else {
                    false
                }
            }, SingleArgumentCommandHandler("pd") { event, arg, heuristic ->
                if (!heuristic) {
                    respond(event, "Profidata $arg", imageUrl = "http://pdintra/php/mafotos_virt/$arg.jpg")
					true
                } else {
					false
				}
			}, SingleArgumentCommandHandler("image") { event, arg, heuristic ->
				if (!heuristic) {
					respond(event, "Image $arg", imageUrl = arg)
					true
				} else {
					false
				}
            }, SingleJoinedArgumentCommandHandler("translate") { event, arg, _ ->
               respondSearchTranslations(event, arg)
			}
		)

	private fun deleteAllMessages(channel: SlackChannel) {
		val channelHistory = ChannelHistoryModuleFactory.createChannelHistoryModule(session)
		val messages = channelHistory.fetchHistoryOfChannel(channel.name)
		println("Found ${messages.size} messages in the history - deleting them")
		for (message in messages) {
			session.deleteMessage(message.timestamp, channel)
		}
		println("Finished deleting")
	}

	fun start () {
		loadData()

		session.addMessagePostedListener({ event, _ ->
			handleMessagePosted(event)
		})

		println("Ready")
	}

	private fun loadData() {
		val properties = loadProperties("simplebot.properties")

		val apiKey = properties.getProperty("api.key")
		session = connected(SlackSessionFactory.createWebSocketSlackSession(apiKey))

		user = session.user()
		adminUser = findUser(properties.getProperty("admin.user"))

		languages = properties.getProperty("translation.languages").split(",").map {it.trim()}

		loadPropertiesTranslations(properties, languages)
	}

	private fun findUser(user: String?): SlackUser? {
		if (user == null) {
			return null
		}

		val userById = session.findUserById(user)
		if (userById != null) {
			return userById
		}

		return session.findUserByUserName(user)
	}

	private fun handleMessagePosted(event: SlackMessagePosted) {
		try {
			if (event.sender.id != user.id) {
				val message = event.messageContent
				val directMessage = parseCommand(user.tag(), message)
				if (directMessage != null) {
					respondToMessage(event, directMessage)
				} else if (event.channel.isDirect || observedChannelIds.contains(event.channel.id)) {
					respondToMessage(event, event.messageContent)
				}
			}
		} catch (ex: Exception) {
			handleException("""
					|*Failed to handle message:*
					|from: ${event.sender.realName}
					|channel: ${event.channel.name}
					|content: ${event.messageContent}
					""".trimMargin(), ex)
		}
	}

	private fun handleException(message: String, ex: Exception) {
		ex.printStackTrace()

		if (adminUser != null) {
			val stringWriter = StringWriter()
			ex.printStackTrace(PrintWriter(stringWriter))

			session.sendMessageToUser(adminUser, message, null)
			session.sendFileToUser(adminUser, stringWriter.toString().toByteArray(), "Stacktrace.txt")
		}
	}

	private fun loadPropertiesTranslations(properties: Properties, languages: List<String>) {
		propertiesTranslations.clear()

		var translationIndex = 0

		var success = true
		do {
			translationIndex++
			for (language in languages) {
				val file = properties.getProperty("translation.${translationIndex}.${language}.properties")

				if (file != null) {
					propertiesTranslations.parse(language, loadProperties(file))
				} else {
					success = false
				}
			}
			propertiesTranslations.buildIndex()
		} while (success)
	}

	private fun respondToMessage(event: SlackMessagePosted , messageContent: String) {
		println(messageContent)

		val args = messageContent.split(Pattern.compile("\\s+"))

		try {
			for (commandHandler in commandHandlers) {
				val done = commandHandler.execute(event, args)
				if (done) {
					incrementCommandCount(commandHandler, false)
					return
				}
			}

			for (commandHandler in commandHandlers) {
				val done = commandHandler.execute(event, args, true)
				if (done) {
					incrementCommandCount(commandHandler, true)
				}
			}
		} finally {
			incrementUserCommandCount(event.user.realName)
		}
	}

	private fun incrementUserCommandCount(userName : String) {
		userCommandCount[userName] = userCommandCount.getOrDefault(userName, 0) + 1
	}

	private fun incrementCommandCount(commandHandler: CommandHandler, heuristic: Boolean) {
		if (heuristic) {
			heuristicCommandCount[commandHandler.name] = heuristicCommandCount.getOrDefault(commandHandler.name, 0) + 1
		} else {
			explicitCommandCount[commandHandler.name] = explicitCommandCount.getOrDefault(commandHandler.name, 0) + 1
		}
	}

	private fun parseCommand(command: String, line: String): String? {
		if (line.startsWith(command)) {
			return line.substring(command.length).trim()
		}
		return null
	}

	private fun respond(event: SlackMessagePosted, message: String, imageUrl: String? = null) {
        if (imageUrl != null) {
            val attachment = SlackAttachment()
            attachment.title = "Employee"
            attachment.pretext = "This is the pretext."
            attachment.text = "Just testing."
            attachment.imageUrl = imageUrl
            attachment.thumbUrl = imageUrl
            attachment.color = "good"
            session.sendMessage(event.channel, message, attachment)
        } else {
            session.sendMessage(event.channel, message)
        }
	}

	private fun respondHelp(event: SlackMessagePosted) {
		val bot = "@" + user.userName
		session.sendMessage(event.channel, """
				|You can ask me questions by giving me a command with an appropriate argument.
				|Try it out by asking one of the following lines (just copy and paste into a new message):
				|$bot help
				|$bot millis -11676096000000
				|$bot millis 1600-01-01
				|$bot hex c0defeed
				|$bot dec 1234567890
				|$bot translate interest
				|
				|If you talk with me without specifying a command, I will try to answer as best as I can (maybe giving multiple answers).
				|Please try one of the following:
				|$bot interest
				|
				|If you talk with me in a direct chat you do not need to prefix the messages with my name $bot.
				|Please try one of the following:
				|millis 1600-01-01
				|interest
				""".trimMargin())
	}

	private fun respondStatus(event: SlackMessagePosted) {
			session.sendMessage(event.channel, """
					|${propertiesTranslations.translations.size} properties translations
					""".trimMargin())
	}

	private val millisecondsPerSecond = 1000
	private val millisecondsPerMinute = 60 * millisecondsPerSecond
	private val millisecondsPerHour = 60 * millisecondsPerMinute
	private val millisecondsPerDay = 24 * millisecondsPerHour

	private fun respondStatistics(event: SlackMessagePosted) {
		val daysHoursMinutesSecondsMilliseconds = convertMillisecondsToDaysHoursMinutesSecondsMilliseconds(System.currentTimeMillis() - startMilliseconds)
		val runningDays = daysHoursMinutesSecondsMilliseconds[0]
		val runningHours = daysHoursMinutesSecondsMilliseconds[1]
		val runningMinutes = daysHoursMinutesSecondsMilliseconds[2]
		val runningSeconds = daysHoursMinutesSecondsMilliseconds[3]

		var message = "This bot is running since $runningDays days $runningHours hours $runningMinutes minutes $runningSeconds seconds.\n"

		message += "Explicit commands:\n"
		for (command in explicitCommandCount.keys.sorted()) {
			message += "    $command : ${explicitCommandCount[command]}\n"
		}

		message += "Heuristic commands:\n"
		for (command in heuristicCommandCount.keys.sorted()) {
			message += "    $command : ${heuristicCommandCount[command]}\n"
		}

		message += "User commands:\n"
		for (userName in userCommandCount.keys.sorted()) {
			message += "    $userName : ${userCommandCount[userName]}\n"
		}

		session.sendMessage(event.channel, message)
	}

	private fun convertMillisecondsToDaysHoursMinutesSecondsMilliseconds(milliseconds: Long): List<Long> {
		var remainingMilliseconds = milliseconds
		val days = remainingMilliseconds / millisecondsPerDay

		remainingMilliseconds -= days * millisecondsPerDay
		val hours = remainingMilliseconds / millisecondsPerHour

		remainingMilliseconds -= hours * millisecondsPerHour
		val minutes = remainingMilliseconds / millisecondsPerMinute

		remainingMilliseconds -= minutes * millisecondsPerMinute
		val seconds = remainingMilliseconds / millisecondsPerSecond

		remainingMilliseconds -= seconds * millisecondsPerSecond

		return listOf(days, hours, minutes, seconds, remainingMilliseconds)
	}

	private fun respondNumberConversion(event: SlackMessagePosted, text: String, base: Int, failMessage: Boolean=true, introMessage: Boolean=true): Boolean {
		val value = text.toLongOrNull(base)

		if (value == null) {
			if (failMessage) {
				session.sendMessage(event.channel, "`$text` is not a valid number for base `$base`.")
			}
			return false
		}

		if (introMessage) {
			session.sendMessage(event.channel, "Interpreting `$text` as number with base `$base`:")
		}

		if (value < 0) {
			session.sendMessage(event.channel, """
				|Dec (unsigned): `${java.lang.Long.toUnsignedString(value, 10)}`
				|Hex (unsigned): `${java.lang.Long.toUnsignedString(value, 16)}`
				|Bin (unsigned): `${java.lang.Long.toUnsignedString(value, 2)}`
				|Dec (signed): `${value.toString(10)}`
				|Hex (signed): `${value.toString(16)}`
				|Bin (signed): `${value.toString(2)}`
				""".trimMargin())
		} else {
			session.sendMessage(event.channel, """
				|Dec: `${java.lang.Long.toUnsignedString(value, 10)}`
				|Hex: `${java.lang.Long.toUnsignedString(value, 16)}`
				|Bin: `${java.lang.Long.toUnsignedString(value, 2)}`
				""".trimMargin())
		}
		return true
	}

	private fun respondSearchTranslations(event: SlackMessagePosted, text: String, failMessage: Boolean=true): Boolean {
		if (text == "") {
			if (failMessage) {
				session.sendMessage(event.channel, "Nothing to translate.")
			}
			return false
		}

		if (true) {
			val partialTranslations = propertiesTranslations.findPartial(text.toLowerCase())
			Collections.sort(partialTranslations) { o1, o2 ->
				val v1 = o1["en"]?.stream()?.mapToInt{it.length}?.sum() ?: Int.MAX_VALUE
				val v2 = o2["en"]?.stream()?.mapToInt{it.length}?.sum() ?: Int.MAX_VALUE
				Integer.compare(v1, v2)
			}

			val translationsText = plural(partialTranslations.size, "translation", "translations")
			var message = "Found ${partialTranslations.size} $translationsText that partially matched this term:\n"
			limitedForLoop(5, 0, partialTranslations, { translation ->
				for (translationEntry in translation.entries) {
					val language = translationEntry.key
					val texts = translationEntry.value
					message += "*${language}*:"
					for (text in texts) {
						message += "\t_${text}_\n"
					}
				}
				message += "\n"
			}, { _ ->
				message += "...\n"
			})
			session.sendMessage(event.channel, message)
		}

		if (true) {
			val translation = propertiesTranslations.find(text.toLowerCase())
			if (translation != null) {
				var message = "Found translations for exactly this term:\n"
				for (translationEntry in translation.entries) {
					val language = translationEntry.key
					val texts = translationEntry.value
					message += "*${language}*:"
					for (text in texts) {
						message += "\t_${text}_\n"
					}
				}
				session.sendMessage(event.channel, message)
			} else {
				var message = "No exact translations found."
				if (!failMessage) {
					return false
				}
				session.sendMessage(event.channel, message)
			}
		}

		if (true) {
			var message = "Yandex translation:\n"
			for (language in languages) {
				val translated = webTranslate(text, language)
				if (translated != null) {
					message += "    *${language}*: _${translated}_\n"
				}
			}
			session.sendMessage(event.channel, message)
		}

		return true
	}

	private fun webTranslate(sourceText: String, targetLanguage: String): String? {
		val client = HttpClientBuilder.create().build()

		val url = "https://translate.yandex.net/api/v1.5/tr/translate"
		val key = "trnsl.1.1.20191123T134447Z.ae9e3c46eb330538.20ae32810f26128eda4371a0d32cbb96e46ecde3"
		val lang = "$targetLanguage"
		val format = "plain"
		val text = "$sourceText"

		val request = HttpPost("$url?key=$key&lang=$lang&format=$format")

		request.entity = StringEntity("text=$text")

		request.addHeader("Content-Type", "application/x-www-form-urlencoded")

		val response = client.execute(request)
		val responseText = EntityUtils.toString(response.entity)
		println("Response: $responseText")

		client.close()

		val pattern = ".*<text>(.*)</text>".toRegex()
		val match = pattern.find(responseText)

		if (match != null) {
			return match.groups[1]?.value
		}

		return null
	}

	private fun connected(s: SlackSession): SlackSession {
		s.connect()
		return s
	}
}

fun loadProperties(name: String): Properties {
	val properties = Properties()

    //val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("name")
	BufferedReader(FileReader(name)).use {
		properties.load(it)
	}

	return properties
}

fun plural(count: Int, singular: String, plural: String): String {
	if (count == 1) {
		return singular
	} else {
		return plural
	}
}

fun String.isUpperCase(): Boolean {
	for (c in this) {
		if (!c.isUpperCase()) {
			return false
		}
	}
	return true
}

fun SlackUser.tag() = "<@" + this.id + ">"

fun SlackSession.user(): SlackUser {
	val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()

    val params = HashMap<String, String>()
	val replyHandle = this.postGenericSlackCommand(params, "auth.test")
	val reply = replyHandle.reply.plainAnswer

	val response = gson.fromJson(reply, AuthTestResponse::class.java)
	if (!response.ok) {
		throw SlackException(response.error)
	}

	return this.findUserById(response.userId)
}

private class AuthTestResponse {
	var ok: Boolean = false
	var error: String = ""
	var warning: String = ""
	var userId: String = ""
	var user: String = ""
	var teamId: String = ""
	var team: String = ""
}

fun main(args: Array<String>) {
	val bot = SimpleBot()
	bot.start()
}
