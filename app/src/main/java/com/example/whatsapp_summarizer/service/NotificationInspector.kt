package com.example.whatsapp_summarizer.service

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.os.Parcelable

/**
 * Works out whether a WhatsApp notification belongs to a group chat, and pulls the
 * sender and message text out of it.
 *
 * The old implementation guessed from punctuation - "text contains a colon and does
 * not start with the title" - which made every personal message containing a URL
 * ("https://..."), a clock time ("10:30") or a colon in the body look like a group.
 * That is why personal conversations were being captured.
 *
 * WhatsApp posts these as MessagingStyle notifications, so the platform carries the
 * answer explicitly. We consult those signals in order of trustworthiness and
 * **default to "not a group"** whenever none of them is conclusive.
 */
object NotificationInspector {

    /** Keys inside each Notification.MessagingStyle.Message bundle. */
    private const val KEY_TEXT = "text"
    private const val KEY_SENDER = "sender"
    private const val KEY_SENDER_PERSON = "sender_person"
    private const val KEY_TIME = "time"

    /** A colon this far into the text is a sentence, not a "Sender: message" prefix. */
    private const val MAX_SENDER_NAME_LENGTH = 30

    enum class Signal {
        /** Notification.EXTRA_IS_GROUP_CONVERSATION was present - authoritative. */
        IS_GROUP_CONVERSATION_FLAG,

        /** A MessagingStyle conversation title, which Android documents as group-only. */
        CONVERSATION_TITLE,

        /** More than one distinct sender in the same thread. */
        MESSAGE_SENDERS,

        /**
         * Last resort when the platform told us nothing: a strict "Sender: message"
         * prefix in the notification text. Returns false - personal - when unsure.
         */
        TEXT_PREFIX
    }

    data class Result(
        val isGroup: Boolean,
        val signal: Signal,
        val chatName: String,
        val senderName: String,
        val messageContent: String,
        /**
         * WhatsApp's own send time for the message, which is what makes a message
         * identifiable. Falls back to capture time when the notification does not
         * carry one - see [resolveTimestamp].
         */
        val timestamp: Long,
        /** True when we had to fall back to capture time. */
        val timestampIsApproximate: Boolean,
        /** Human-readable dump of the raw signals, for the in-app debug log. */
        val diagnostics: String
    )

    fun inspect(
        title: String,
        text: String,
        bigText: String?,
        extras: Bundle
    ): Result {
        val messages = readMessages(extras)
        val conversationTitle =
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()

        // Only trust the flag when WhatsApp actually set it: getBoolean() cannot tell
        // "absent" from "false", so check for the key first.
        val hasGroupFlag = extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)
        val groupFlag = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

        val distinctSenders = messages.mapNotNull { it.sender }
            .filter { it.isNotBlank() }
            .distinct()

        val diagnostics = buildString {
            append("isGroupConversation=")
            append(if (hasGroupFlag) groupFlag.toString() else "absent")
            append(" | conversationTitle=")
            append(if (conversationTitle.isNullOrBlank()) "absent" else "'$conversationTitle'")
            append(" | messages=${messages.size}")
            append(" | senders=")
            append(if (distinctSenders.isEmpty()) "none" else distinctSenders.joinToString(","))
            append(" | time=")
            append(messages.lastOrNull()?.time?.toString() ?: "absent")
        }

        // The newest MessagingStyle message is the one this notification is about.
        val latest = messages.lastOrNull()

        // ---- Decide, most trustworthy signal first ----
        val (isGroup, signal) = when {
            hasGroupFlag -> groupFlag to Signal.IS_GROUP_CONVERSATION_FLAG

            // Android docs: conversation title "should only be used for group messaging".
            !conversationTitle.isNullOrBlank() -> true to Signal.CONVERSATION_TITLE

            // Several people posting into one thread means a group.
            distinctSenders.size > 1 -> true to Signal.MESSAGE_SENDERS

            // A SINGLE sender never proves group-ness by itself. A personal chat
            // can legitimately have a sender name that differs from the title -
            // contact saved as "Rachel" but a WhatsApp push name of "Rachel Cohen",
            // or a title that is a raw phone number. Treating that as a group is
            // the same class of bug we are fixing, so fall through to the strict
            // text test and let it default to "personal".
            else -> hasStrictSenderPrefix(text, title) to Signal.TEXT_PREFIX
        }

        // ---- Extract the fields ----
        val chatName = when {
            !conversationTitle.isNullOrBlank() -> conversationTitle
            else -> title
        }

        var senderName: String
        var messageContent: String

        if (latest != null) {
            // MessagingStyle gave us the sender and body directly - no string surgery.
            senderName = latest.sender?.takeIf { it.isNotBlank() } ?: "Unknown"
            messageContent = latest.text?.takeIf { it.isNotBlank() } ?: text
        } else if (isGroup) {
            val split = splitSenderPrefix(text) ?: bigText?.let { splitSenderPrefix(it) }
            senderName = split?.first ?: "Unknown"
            messageContent = split?.second ?: text
        } else {
            senderName = title
            messageContent = text
        }

        if (!isGroup) {
            // In a personal chat the sender is the contact the thread is named after.
            senderName = title
        }

        messageContent = messageContent.replace("\\n", "\n").trim()

        val (timestamp, approximate) = resolveTimestamp(latest?.time ?: 0L)

        return Result(
            isGroup = isGroup,
            signal = signal,
            chatName = chatName.trim(),
            senderName = senderName.trim(),
            messageContent = messageContent,
            timestamp = timestamp,
            timestampIsApproximate = approximate,
            diagnostics = diagnostics
        )
    }

    /**
     * WhatsApp's send time identifies a message, which is what lets the database
     * reject the same message when the notification is re-posted.
     *
     * If it is missing we fall back to capture time. That is deliberately the
     * *unsafe-for-dedup* direction: a unique capture time means the row can never
     * collide with a genuinely different message, so a missing send time costs us
     * a possible duplicate rather than silently discarding real data.
     */
    private fun resolveTimestamp(styleTime: Long): Pair<Long, Boolean> {
        if (styleTime > 0L) return styleTime to false
        android.util.Log.w(
            "NotificationInspector",
            "MessagingStyle carried no send time; falling back to capture time"
        )
        return System.currentTimeMillis() to true
    }

    private data class StyleMessage(val sender: String?, val text: String?, val time: Long)

    /** Reads Notification.EXTRA_MESSAGES ("android.messages") if WhatsApp supplied it. */
    private fun readMessages(extras: Bundle): List<StyleMessage> {
        val raw = try {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        return raw.mapNotNull { parcelable: Parcelable? ->
            val bundle = parcelable as? Bundle ?: return@mapNotNull null
            StyleMessage(
                sender = readSender(bundle),
                text = bundle.getCharSequence(KEY_TEXT)?.toString()?.trim(),
                time = bundle.getLong(KEY_TIME, 0L)
            )
        }
    }

    private fun readSender(bundle: Bundle): String? {
        // Notification.MessagingStyle.Message writes the Person's name into the legacy
        // "sender" key as well, so that covers us below API 28 too.
        bundle.getCharSequence(KEY_SENDER)?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val person: android.app.Person? = try {
                @Suppress("DEPRECATION")
                bundle.getParcelable<android.app.Person>(KEY_SENDER_PERSON)
            } catch (e: Exception) {
                null
            }
            person?.name?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * True only for text that really looks like "Sender: message".
     *
     * Deliberately strict, because this runs when the platform told us nothing and a
     * wrong "yes" here is exactly the bug we are fixing.
     */
    private fun hasStrictSenderPrefix(text: String, title: String): Boolean {
        val split = splitSenderPrefix(text) ?: return false
        // "Dad: see you at 6" in a chat titled "Dad" is still a personal chat.
        return !sameName(split.first, title)
    }

    private fun splitSenderPrefix(text: String): Pair<String, String>? {
        val colon = text.indexOf(':')
        if (colon <= 0 || colon > MAX_SENDER_NAME_LENGTH) return null

        val candidate = text.substring(0, colon).trim()
        val rest = text.substring(colon + 1).trim()
        if (candidate.isBlank() || rest.isBlank()) return null

        // A URL scheme, a clock time, or a sentence fragment is not a name.
        if (candidate.equals("http", true) || candidate.equals("https", true)) return null
        if (candidate.any { it.isDigit() }) return null
        if (candidate.any { it in ".,;!?()[]{}\"/\\|<>=*+" }) return null
        // Names are short; "Sounds good, talk later: here is the link" is not one.
        if (candidate.split(Regex("\\s+")).size > 4) return null

        return candidate to rest
    }

    /** Compares names ignoring case and the invisible characters WhatsApp injects. */
    private fun sameName(a: String, b: String): Boolean {
        val na = com.example.whatsapp_summarizer.util.ChatNameNormalizer.normalize(a)
        val nb = com.example.whatsapp_summarizer.util.ChatNameNormalizer.normalize(b)
        return na.equals(nb, ignoreCase = true)
    }
}
