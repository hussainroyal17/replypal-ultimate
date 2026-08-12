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

data class ConversationProfile(
    val summary: String,
    val importantFacts: String,
    val language: String,
    val tone: String,
    val flirting: String,
    val humor: String,
    val emojiStyle: String,
    val stage: String
)

data class GenerationRequest(
    val conversationId: Long,
    val contactName: String,
    val history: List<MessageEntity>,
    val latestMessage: String,
    val profile: ConversationProfile,
    val preferences: UserPreferences
)

data class ReplySuggestions(val suggestions: Map<ReplyStyle, List<String>>) {
    fun forStyle(style: ReplyStyle) = suggestions[style].orEmpty()
}

interface AIProvider { suspend fun generate(request: GenerationRequest): ReplySuggestions }

/** Privacy-preserving fallback. It uses only the selected conversation's request payload. */
class LocalReplyProvider : AIProvider {
    override suspend fun generate(request: GenerationRequest): ReplySuggestions {
        val latest = request.latestMessage.trim()
        val lower = latest.lowercase()
        val isHowAreYou = lower.contains("how are") || lower.contains("how's you") || lower.contains("hows you")
        val playful = request.profile.tone == "Playful" || request.profile.humor == "High"
        val emoji = if (request.profile.emojiStyle == "High" || request.preferences.emojiLevel == "High") " 😄" else ""
        val gentleFlirt = when (request.profile.flirting) { "Low" -> ""; "High" -> " Better now that you asked."; else -> " Better now that you’re checking on me." }
        fun followUp(value: String) = if (request.preferences.responseLength == "Very short") value else "$value How about you?"
        val base = if (isHowAreYou) {
            mapOf(
                ReplyStyle.FLIRTY to listOf(followUp("I’m good.$gentleFlirt$emoji"), "Doing well — your message made my day a little better.$emoji", "I’m good, and I like that you asked.$emoji"),
                ReplyStyle.FRIENDLY to listOf(followUp("I’m doing good$emoji"), "I’m good, just taking it easy. How’s your day going?", "Doing well! What are you up to today?"),
                ReplyStyle.FUNNY to listOf(followUp("Alive and functioning 😂"), "Surviving successfully — your message improved my situation 😂", "I was fine until this important wellness check arrived 😂"),
                ReplyStyle.CARING to listOf(followUp("I’m good$emoji"), "I’m okay — how are you feeling today?", "I’m good. Everything okay with you?"),
                ReplyStyle.CONFIDENT to listOf(followUp("Doing pretty well 😎"), "I’m good — keeping things moving. What about you?", "Doing great. Tell me what your day has been like.")
            )
        } else if (playful) {
            mapOf(
                ReplyStyle.FLIRTY to listOf("You’re making this chat more interesting already 😉", "I was hoping you’d say that.", "That sounds like an invitation to keep talking."),
                ReplyStyle.FRIENDLY to listOf("Haha, tell me more.", "That’s interesting — what happened next?", "I get that. What do you think now?"),
                ReplyStyle.FUNNY to listOf("Okay, I did not have that on today’s bingo card 😂", "This conversation has officially become entertaining.", "I need the full story before I react properly 😂"),
                ReplyStyle.CARING to listOf("That sounds like a lot. Are you okay?", "I’m here if you want to talk it through.", "Take your time — what’s on your mind?"),
                ReplyStyle.CONFIDENT to listOf("You’ve got my attention. Keep going.", "I like your way of thinking.", "That’s a good point. I want to hear the rest.")
            )
        } else {
            mapOf(
                ReplyStyle.FLIRTY to listOf("I like where this conversation is going.", "You have a nice way of putting things.", "That made me smile a little."),
                ReplyStyle.FRIENDLY to listOf("That makes sense. Tell me more.", "I hear you. What happened next?", "That’s interesting — how do you feel about it?"),
                ReplyStyle.FUNNY to listOf("I need a moment to process that 😂", "Well, that was unexpected in the best way.", "My official reaction is: explain immediately 😂"),
                ReplyStyle.CARING to listOf("Thanks for telling me. Are you doing okay?", "I’m listening — what do you need right now?", "That sounds important. Want to talk about it?"),
                ReplyStyle.CONFIDENT to listOf("I understand. Here’s what I think.", "That’s fair. What would you do next?", "I’m with you. Let’s keep the conversation going.")
            )
        }
        return ReplySuggestions(base)
    }
}

/** Calls a user-configured secure backend. Secrets stay on the backend, never in the APK. */
class SecureBackendProvider(private val baseUrl: String) : AIProvider {
    private val client = OkHttpClient.Builder().callTimeout(35, TimeUnit.SECONDS).build()
    override suspend fun generate(request: GenerationRequest): ReplySuggestions {
        require(baseUrl.startsWith("https://")) { "Use an HTTPS secure backend URL." }
        val payload = JSONObject().apply {
            put("conversation_id", request.conversationId)
            put("contact_name", request.contactName)
            put("latest_message", request.latestMessage)
            put("language", request.preferences.language)
            put("tone", request.preferences.preferredTone)
            put("reply_length", request.preferences.responseLength)
            put("emoji_level", request.preferences.emojiLevel)
            put("conversation_profile", JSONObject().apply {
                put("summary", request.profile.summary); put("important_facts", request.profile.importantFacts)
                put("language", request.profile.language); put("tone", request.profile.tone)
                put("flirting", request.profile.flirting); put("humor", request.profile.humor)
                put("emoji_style", request.profile.emojiStyle); put("stage", request.profile.stage)
            })
            put("history", JSONArray().apply { request.history.takeLast(20).forEach { message -> put(JSONObject().apply { put("body", message.body); put("direction", message.direction.name.lowercase()); put("timestamp", message.timestamp) }) } })
        }
        val httpRequest = Request.Builder().url("$baseUrl/v1/replies").post(payload.toString().toRequestBody(jsonMedia)).build()
        client.newCall(httpRequest).execute().use { response ->
            check(response.isSuccessful) { "The AI service could not generate replies right now." }
            val source = JSONObject(response.body?.string().orEmpty()).getJSONObject("suggestions")
            return ReplySuggestions(ReplyStyle.entries.associateWith { style ->
                val array = source.optJSONArray(style.name.lowercase()) ?: JSONArray()
                List(array.length()) { array.getString(it) }.filter { it.isNotBlank() }
            })
        }
    }
}

class SuggestionGenerator(private val localProvider: AIProvider = LocalReplyProvider()) {
    suspend fun generate(request: GenerationRequest): ReplySuggestions = request.preferences.apiBaseUrl.takeIf { it.isNotBlank() }?.let { SecureBackendProvider(it).generate(request) } ?: localProvider.generate(request)
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
    suspend fun renameConversation(conversation: ConversationEntity, name: String) = conversationDao.upsert(conversation.copy(name = name.trim().ifBlank { conversation.name }, lastUpdated = System.currentTimeMillis()))
    suspend fun updateProfile(conversation: ConversationEntity, profile: ConversationProfile) = conversationDao.upsert(conversation.copy(contextSummary = profile.summary, importantFacts = profile.importantFacts, preferredLanguage = profile.language, inferredTone = profile.tone, flirtingLevel = profile.flirting, humorLevel = profile.humor, emojiStyle = profile.emojiStyle, conversationStage = profile.stage, lastUpdated = System.currentTimeMillis()))
    suspend fun deleteConversation(id: Long) = conversationDao.delete(id)

    suspend fun addIncoming(conversationId: Long, body: String) = addMessage(conversationId, body, MessageDirection.INCOMING)
    suspend fun addOutgoing(conversationId: Long, body: String) = addMessage(conversationId, body, MessageDirection.OUTGOING)
    private suspend fun addMessage(conversationId: Long, body: String, direction: MessageDirection) {
        if (body.isBlank()) return
        messageDao.insert(MessageEntity(conversationId = conversationId, body = body.trim(), direction = direction))
        refreshProfile(conversationId)
    }

    suspend fun refreshProfile(conversationId: Long): ConversationEntity? {
        val conversation = conversation(conversationId).first() ?: return null
        val history = messages(conversationId).first()
        val incoming = history.filter { it.direction == MessageDirection.INCOMING }.joinToString(" ") { it.body }.lowercase()
        val all = history.joinToString(" ") { it.body }.lowercase()
        val emojiCount = all.count { it in "😂😄😁😊😉❤️😎" }
        val tone = when { incoming.contains("😂") || incoming.contains("😁") || incoming.contains("haha") -> "Playful"; history.size <= 2 -> "New / neutral"; else -> "Friendly" }
        val flirting = when { incoming.contains("😉") || incoming.contains("miss") || incoming.contains("cute") -> "Medium"; tone == "Playful" -> "Low"; else -> "Low" }
        val humor = when { incoming.contains("😂") || incoming.contains("haha") -> "High"; tone == "Playful" -> "Medium"; else -> "Low" }
        val language = when { Regex("[\u0900-\u097F]").containsMatchIn(all) -> "Hindi / Hinglish"; else -> "English / Hinglish" }
        val facts = listOf("college", "photography", "travel", "travelling", "work", "exam").filter { all.contains(it) }.joinToString(", ")
        val summary = history.takeLast(8).joinToString(" • ") { if (it.direction == MessageDirection.INCOMING) "${conversation.name}: ${it.body}" else "Me: ${it.body}" }.take(600)
        val profile = ConversationProfile(summary, facts.ifBlank { conversation.importantFacts }, language, tone, flirting, humor, if (emojiCount >= 3) "High" else if (emojiCount > 0) "Medium" else "Low", if (history.size < 4) "Getting to know each other" else "Established conversation")
        updateProfile(conversation, profile)
        return conversation(conversationId).first()
    }

    suspend fun generate(conversation: ConversationEntity, preferences: UserPreferences): ReplySuggestions {
        val profiled = refreshProfile(conversation.id) ?: conversation
        val history = messages(conversation.id).first()
        val latest = history.lastOrNull { it.direction == MessageDirection.INCOMING }?.body.orEmpty()
        require(latest.isNotBlank()) { "Add an incoming message from ${conversation.name} first." }
        val profile = ConversationProfile(profiled.contextSummary, profiled.importantFacts, profiled.preferredLanguage, profiled.inferredTone, profiled.flirtingLevel, profiled.humorLevel, profiled.emojiStyle, profiled.conversationStage)
        val result = generator.generate(GenerationRequest(profiled.id, profiled.name, history, latest, profile, preferences))
        suggestionDao.insertAll(result.suggestions.flatMap { (style, replies) -> replies.map { reply -> SuggestionEntity(conversationId = profiled.id, message = reply, style = style, rationale = rationale(style, profiled, latest)) } })
        return result
    }

    fun rationale(style: ReplyStyle, conversation: ConversationEntity, latest: String): String = "This ${style.label.lowercase()} option uses ${conversation.name}’s ${conversation.inferredTone.lowercase()} conversation profile, answers “${latest.take(80)}”, and keeps the next turn easy to respond to."
    suspend fun markCopied(id: Long) = suggestionDao.markCopied(id)
    suspend fun seedIfEmpty() {
        if (conversations().first().isNotEmpty()) return
        val muskan = createConversation("Muskan"); addIncoming(muskan, "Let’s see 😁"); addIncoming(muskan, "how’s you")
        val ayesha = createConversation("Ayesha"); addIncoming(ayesha, "I’m going to college now"); addIncoming(ayesha, "I’ll talk later")
    }
}
