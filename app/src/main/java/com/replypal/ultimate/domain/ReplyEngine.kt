package com.replypal.ultimate.domain

import com.replypal.ultimate.data.ConversationDao
import com.replypal.ultimate.data.ConversationEntity
import com.replypal.ultimate.data.MessageDao
import com.replypal.ultimate.data.MessageDirection
import com.replypal.ultimate.data.MessageEntity
import com.replypal.ultimate.data.ReplyStyle
import com.replypal.ultimate.data.SuggestionDao
import com.replypal.ultimate.data.SuggestionEntity
import com.replypal.ultimate.data.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val jsonMedia = "application/json; charset=utf-8".toMediaType()

data class GenerationRequest(
    val contactName: String,
    val history: List<MessageEntity>,
    val latestMessage: String,
    val preferences: UserPreferences
)

data class ReplySuggestions(val suggestions: Map<ReplyStyle, List<String>>) {
    fun forStyle(style: ReplyStyle) = suggestions[style].orEmpty()
}

interface AIProvider {
    suspend fun generate(request: GenerationRequest): ReplySuggestions
}

/** Local, privacy-preserving fallback while a secure provider endpoint is not configured. */
class LocalReplyProvider : AIProvider {
    override suspend fun generate(request: GenerationRequest): ReplySuggestions {
        val message = request.latestMessage.trim().ifBlank { "that" }
        val short = request.preferences.responseLength != "Detailed"
        val emoji = when (request.preferences.emojiLevel) { "None" -> ""; "Low" -> ""; else -> " ✨" }
        fun ending(value: String) = if (short) value else "$value I’m curious what you think.$emoji"
        return ReplySuggestions(
            mapOf(
                ReplyStyle.FLIRTY to listOf(
                    ending("Okay, now you’re making this conversation more interesting 😉"),
                    ending("Careful, you’re making it hard not to smile over here."),
                    ending("I was hoping you’d say something like that 😉")
                ),
                ReplyStyle.FRIENDLY to listOf(
                    ending("Haha, that’s actually pretty interesting. Tell me more."),
                    ending("I get that. What happened next?"),
                    ending("That made me laugh — how did it turn out?")
                ),
                ReplyStyle.FUNNY to listOf(
                    ending("Okay, I definitely did not have that on today’s bingo card 😂"),
                    ending("Emergency conversation department is officially open."),
                    ending("Respectfully, I need the full story before I can react properly 😂")
                ),
                ReplyStyle.CARING to listOf(
                    ending("That sounds like a lot. Are you doing okay?"),
                    ending("I’m here if you want to talk it through."),
                    ending("Take your time — what’s on your mind?")
                ),
                ReplyStyle.CONFIDENT to listOf(
                    ending("I like your way of thinking. Keep going."),
                    ending("You’ve got my attention — what’s the plan?"),
                    ending("That’s a good point. I want to hear the rest.")
                )
            )
        )
    }
}

/** Calls a user-configured secure backend. No provider secret is stored in the APK. */
class SecureBackendProvider(private val baseUrl: String) : AIProvider {
    private val client = OkHttpClient.Builder().callTimeout(35, TimeUnit.SECONDS).build()

    override suspend fun generate(request: GenerationRequest): ReplySuggestions {
        require(baseUrl.startsWith("https://")) { "Use an HTTPS secure backend URL." }
        val payload = JSONObject().apply {
            put("contact_name", request.contactName)
            put("latest_message", request.latestMessage)
            put("language", request.preferences.language)
            put("tone", request.preferences.preferredTone)
            put("reply_length", request.preferences.responseLength)
            put("emoji_level", request.preferences.emojiLevel)
            put("history", JSONArray().apply {
                request.history.takeLast(20).forEach { message ->
                    put(JSONObject().apply {
                        put("body", message.body)
                        put("direction", message.direction.name.lowercase())
                        put("timestamp", message.timestamp)
                    })
                }
            })
        }
        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/replies")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(httpRequest).execute().use { response ->
            check(response.isSuccessful) { "The AI service could not generate replies right now." }
            val root = JSONObject(response.body?.string().orEmpty())
            val source = root.getJSONObject("suggestions")
            return ReplySuggestions(ReplyStyle.entries.associateWith { style ->
                val array = source.optJSONArray(style.name.lowercase()) ?: JSONArray()
                List(array.length()) { index -> array.getString(index) }.filter { it.isNotBlank() }
            })
        }
    }
}

class SuggestionGenerator(private val localProvider: AIProvider = LocalReplyProvider()) {
    suspend fun generate(request: GenerationRequest): ReplySuggestions {
        val secure = request.preferences.apiBaseUrl.takeIf { it.isNotBlank() }
        return if (secure == null) localProvider.generate(request) else SecureBackendProvider(secure).generate(request)
    }
}

class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val suggestionDao: SuggestionDao,
    private val generator: SuggestionGenerator
) {
    fun conversations(): Flow<List<ConversationEntity>> = conversationDao.observeActive()
    fun conversation(id: Long): Flow<ConversationEntity?> = conversationDao.observeById(id)
    fun messages(id: Long): Flow<List<MessageEntity>> = messageDao.observeForConversation(id)
    fun suggestions(id: Long): Flow<List<SuggestionEntity>> = suggestionDao.observeForConversation(id)

    suspend fun createConversation(name: String): Long = conversationDao.upsert(ConversationEntity(name = name.trim().ifBlank { "New conversation" }))

    suspend fun renameConversation(conversation: ConversationEntity, name: String) {
        conversationDao.upsert(conversation.copy(name = name.trim().ifBlank { conversation.name }, lastUpdated = System.currentTimeMillis()))
    }

    suspend fun deleteConversation(id: Long) { conversationDao.delete(id) }

    suspend fun addIncoming(conversationId: Long, body: String) {
        if (body.isBlank()) return
        messageDao.insert(MessageEntity(conversationId = conversationId, body = body.trim(), direction = MessageDirection.INCOMING))
        conversationDao.observeById(conversationId).first()?.let { conversationDao.upsert(it.copy(lastUpdated = System.currentTimeMillis())) }
    }

    suspend fun addOutgoing(conversationId: Long, body: String) {
        if (body.isBlank()) return
        messageDao.insert(MessageEntity(conversationId = conversationId, body = body.trim(), direction = MessageDirection.OUTGOING))
        conversationDao.observeById(conversationId).first()?.let { conversationDao.upsert(it.copy(lastUpdated = System.currentTimeMillis())) }
    }

    suspend fun generate(conversation: ConversationEntity, preferences: UserPreferences): ReplySuggestions {
        val history = messages(conversation.id).first()
        val latest = history.lastOrNull { it.direction == MessageDirection.INCOMING }?.body.orEmpty()
        require(latest.isNotBlank()) { "Paste or scan an incoming message first." }
        val result = generator.generate(GenerationRequest(conversation.name, history, latest, preferences))
        suggestionDao.insertAll(result.suggestions.flatMap { (style, replies) ->
            replies.map { reply -> SuggestionEntity(conversationId = conversation.id, message = reply, style = style) }
        })
        return result
    }

    suspend fun markCopied(id: Long) = suggestionDao.markCopied(id)

    suspend fun seedIfEmpty() {
        if (conversations().first().isNotEmpty()) return
        val ayesha = createConversation("Ayesha")
        addIncoming(ayesha, "Okay, maybe you’re right 😂")
        val muskan = createConversation("Muskan")
        addIncoming(muskan, "Let’s see 😄")
    }
}
