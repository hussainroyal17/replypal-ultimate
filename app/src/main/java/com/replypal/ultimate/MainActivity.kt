package com.replypal.ultimate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypal.ultimate.data.ConversationEntity
import com.replypal.ultimate.data.MessageDirection
import com.replypal.ultimate.data.ReplyPalDatabase
import com.replypal.ultimate.data.ReplyStyle
import com.replypal.ultimate.data.SettingsManager
import com.replypal.ultimate.domain.ConversationRepository
import com.replypal.ultimate.domain.SuggestionGenerator
import com.replypal.ultimate.service.FloatingAssistantService
import com.replypal.ultimate.service.OcrService
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private val Purple = Color(0xFF6546E8)
private val Ink = Color(0xFF1B1235)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedImage = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        setContent { ReplyPalApp(sharedImage) }
    }
}

@Composable
private fun ReplyPalApp(sharedImage: Uri?) {
    val context = LocalContext.current
    val db = remember { ReplyPalDatabase.get(context) }
    val repository = remember { ConversationRepository(db.conversations(), db.messages(), db.suggestions(), SuggestionGenerator()) }
    val settings = remember { SettingsManager(context) }
    val conversations by repository.conversations().collectAsStateWithLifecycle(initialValue = emptyList())
    val prefs by settings.preferences.collectAsStateWithLifecycle(initialValue = com.replypal.ultimate.data.UserPreferences())
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedConversationId by remember { mutableLongStateOf(0L) }
    var pendingImage by remember { mutableStateOf(sharedImage) }

    LaunchedEffect(Unit) { repository.seedIfEmpty() }
    LaunchedEffect(conversations) {
        if (selectedConversationId == 0L && conversations.isNotEmpty()) selectedConversationId = conversations.first().id
    }

    MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(primary = Purple, secondary = Color(0xFF1F9FE9), surface = Color(0xFFFFFBFF))) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf("Home" to Icons.Default.Home, "Chat" to Icons.Default.Chat, "Suggestions" to Icons.Default.AutoAwesome, "History" to Icons.Default.History, "Settings" to Icons.Default.Settings).forEachIndexed { index, item ->
                        NavigationBarItem(selected = selectedTab == index, onClick = { selectedTab = index }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
                    }
                }
            }
        ) { padding ->
            when (selectedTab) {
                0 -> HomeScreen(Modifier.padding(padding), conversations, onSelect = { selectedConversationId = it; selectedTab = 1 }, onCreate = { name -> scope.launch { selectedConversationId = repository.createConversation(name); selectedTab = 1 } }, onDelete = { id -> scope.launch { repository.deleteConversation(id) } })
                1 -> ChatScreen(Modifier.padding(padding), repository, settings, prefs, selectedConversationId, pendingImage, onImageConsumed = { pendingImage = null })
                2, 3 -> SuggestionsScreen(Modifier.padding(padding), repository, selectedConversationId, title = if (selectedTab == 2) "Suggestions" else "Saved reply history")
                4 -> SettingsScreen(Modifier.padding(padding), prefs, onFloating = { enabled ->
                    scope.launch { settings.setFloating(enabled) }
                    if (enabled) enableFloating(context) else context.stopService(Intent(context, FloatingAssistantService::class.java))
                }, onTone = { scope.launch { settings.setTone(it) } }, onLength = { scope.launch { settings.setLength(it) } }, onEmoji = { scope.launch { settings.setEmoji(it) } }, onLanguage = { scope.launch { settings.setLanguage(it) } }, onApiUrl = { scope.launch { settings.setApiBaseUrl(it) } })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(modifier: Modifier, conversations: List<ConversationEntity>, onSelect: (Long) -> Unit, onCreate: (String) -> Unit, onDelete: (Long) -> Unit) {
    var newDialog by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier, topBar = { CenterAlignedTopAppBar(title = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("ReplyPal Ultimate", fontWeight = FontWeight.Bold); Text("Your AI conversation companion", style = MaterialTheme.typography.labelSmall) } }) }, floatingActionButton = { FloatingActionButton(onClick = { newDialog = true }, containerColor = Purple, contentColor = Color.White) { Icon(Icons.Default.Add, "New conversation") } }) { contentPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { HeroCard(); Text("Recent conversations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(conversations, key = { it.id }) { conversation ->
                ConversationRow(conversation, onClick = { onSelect(conversation.id) }, onDelete = { onDelete(conversation.id) })
            }
        }
    }
    if (newDialog) NameDialog("New conversation", "Contact name", onDismiss = { newDialog = false }) { name -> newDialog = false; onCreate(name) }
}

@Composable
private fun HeroCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.background(Brush.linearGradient(listOf(Ink, Purple, Color(0xFF2D9EEB)))).padding(22.dp)) {
            Column { Text("Say it better. Every time.", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text("Paste a message or share a screenshot. Choose a natural reply, then copy it into your chat.", color = Color(0xFFF1EEFF)) }
        }
    }
}

@Composable
private fun ConversationRow(conversation: ConversationEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Purple, Color(0xFF4DC3EB)))), contentAlignment = Alignment.Center) { Text(conversation.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(conversation.name, fontWeight = FontWeight.SemiBold); Text("${conversation.inferredTone} • ${conversation.flirtingLevel} flirty • ${if (conversation.contextSummary.isBlank()) "Add messages to build context" else conversation.contextSummary.take(70)}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete conversation", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(modifier: Modifier, repository: ConversationRepository, settings: SettingsManager, prefs: com.replypal.ultimate.data.UserPreferences, conversationId: Long, initialImage: Uri?, onImageConsumed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversation by repository.conversation(conversationId).collectAsStateWithLifecycle(initialValue = null)
    val messages by repository.messages(conversationId).collectAsStateWithLifecycle(initialValue = emptyList())
    var composeText by remember { mutableStateOf("") }
    var extractedText by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var showAddMessage by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) scope.launch { readScreenshot(context, uri, prefs, onDone = { extractedText = it }, onError = { status = it }) } }
    LaunchedEffect(initialImage) { if (initialImage != null) { readScreenshot(context, initialImage, prefs, onDone = { extractedText = it }, onError = { status = it }); onImageConsumed() } }
    Scaffold(modifier = modifier, topBar = { CenterAlignedTopAppBar(title = { Text(conversation?.name ?: "Chat") }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)) }) { contentPadding ->
        if (conversation == null) Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { Text("Create or select a conversation to get started.") } else {
            Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
                ProfileContextCard(conversation!!)
                Text("Add the conversation → Tap Suggest → Choose a reply → Copy → Paste", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages, key = { it.id }) { message -> MessageBubble(message.body, message.direction == MessageDirection.OUTGOING) }
                    if (extractedText != null) item { OcrReview(extractedText!!, onChange = { extractedText = it }, onUse = { composeText = extractedText!!; extractedText = null }) }
                }
                AnimatedVisibility(status != null) { status?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(6.dp)) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = composeText, onValueChange = { composeText = it }, modifier = Modifier.weight(1f), label = { Text("Paste message") }, placeholder = { Text("Paste the latest message here…") }, maxLines = 3)
                    Column { IconButton(onClick = { imagePicker.launch("image/*") }) { Icon(Icons.Default.Image, "Analyze screenshot") }; IconButton(onClick = { val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip; if (clip != null && clip.itemCount > 0) composeText = clip.getItemAt(0).coerceToText(context).toString() }) { Icon(Icons.Default.ContentCopy, "Paste from clipboard") } }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAddMessage = true }, modifier = Modifier.weight(1f)) { Text("+ Add conversation message") }
                    Button(onClick = { scope.launch { status = "Using ${conversation!!.name}’s conversation context…"; runCatching { repository.generate(conversation!!, prefs) }.onSuccess { status = "Five context-aware suggestions are ready." }.onFailure { status = it.message ?: "Couldn’t generate replies." } } }, modifier = Modifier.weight(1f), enabled = messages.any { it.direction == MessageDirection.INCOMING }) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("What should I say next?") }
                }
            }
        }
    }
    if (showAddMessage && conversation != null) AddConversationMessageDialog(conversation!!.name, composeText, onDismiss = { showAddMessage = false }, onAdd = { body, incoming -> scope.launch { if (incoming) repository.addIncoming(conversationId, body) else repository.addOutgoing(conversationId, body); composeText = ""; showAddMessage = false } })
}

@Composable
private fun ProfileContextCard(conversation: ConversationEntity) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF0EDFF)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("✨ AI understands this conversation", fontWeight = FontWeight.Bold)
            Text("Tone: ${conversation.inferredTone} • Flirting: ${conversation.flirtingLevel} • Humor: ${conversation.humorLevel}", style = MaterialTheme.typography.bodySmall)
            if (conversation.contextSummary.isNotBlank()) Text("Memory: ${conversation.contextSummary.take(180)}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AddConversationMessageDialog(name: String, initial: String, onDismiss: () -> Unit, onAdd: (String, Boolean) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    var incoming by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add conversation message") }, text = { Column { Text("Who sent this message?"); Row { AssistChip(onClick = { incoming = true }, label = { Text("$name") }, leadingIcon = if (incoming) ({ Icon(Icons.Default.AutoAwesome, null) }) else null); Spacer(Modifier.width(8.dp)); AssistChip(onClick = { incoming = false }, label = { Text("Me") }, leadingIcon = if (!incoming) ({ Icon(Icons.Default.AutoAwesome, null) }) else null) }; OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { Button(onClick = { onAdd(value, incoming) }, enabled = value.isNotBlank()) { Text("Add message") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } })
}

private suspend fun readScreenshot(context: Context, uri: Uri, prefs: com.replypal.ultimate.data.UserPreferences, onDone: (String) -> Unit, onError: (String) -> Unit) {
    runCatching { OcrService(context).read(uri, prefs.language == "Hindi" || prefs.language == "Hinglish").text }.onSuccess { text -> if (text.isBlank()) onError("Couldn’t read the screenshot clearly. Try cropping the chat area and try again.") else onDone(text) }.onFailure { onError("Screenshot analysis failed. Please try another image.") }
}

@Composable
private fun OcrReview(value: String, onChange: (String) -> Unit, onUse: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF0EDFF)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) { Text("Review extracted text", fontWeight = FontWeight.SemiBold); OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), label = { Text("Edit extracted text") }); Button(onClick = onUse, modifier = Modifier.padding(top = 8.dp)) { Text("Use in chat") } }
    }
}

@Composable
private fun MessageBubble(text: String, outgoing: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Text(text, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (outgoing) Purple else Color(0xFFF0EEFA)).padding(horizontal = 14.dp, vertical = 10.dp), color = if (outgoing) Color.White else Ink)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionsScreen(modifier: Modifier, repository: ConversationRepository, conversationId: Long, title: String) {
    val suggestions by repository.suggestions(conversationId).collectAsStateWithLifecycle(initialValue = emptyList())
    var styleIndex by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var whyId by remember { mutableLongStateOf(0L) }
    val styles = ReplyStyle.entries
    Scaffold(modifier = modifier, topBar = { CenterAlignedTopAppBar(title = { Text(title) }) }) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            TabRow(selectedTabIndex = styleIndex) { styles.forEachIndexed { index, style -> Tab(selected = styleIndex == index, onClick = { styleIndex = index }, text = { Text("${style.emoji} ${style.label}") }) } }
            val visible = suggestions.filter { it.style == styles[styleIndex] }
            if (visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Generate a reply from Chat to see ${styles[styleIndex].label.lowercase()} options.", modifier = Modifier.padding(28.dp)) } else LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible, key = { it.id }) { suggestion -> Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(suggestion.message, style = MaterialTheme.typography.bodyLarge); if (whyId == suggestion.id) Text(suggestion.rationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { AssistChip(onClick = { whyId = if (whyId == suggestion.id) 0L else suggestion.id }, label = { Text("Why this reply?") }); Spacer(Modifier.width(6.dp)); AssistChip(onClick = { copyToClipboard(context, suggestion.message); scope.launch { repository.markCopied(suggestion.id) }; Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show() }, label = { Text("Copy") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }) } } } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(modifier: Modifier, prefs: com.replypal.ultimate.data.UserPreferences, onFloating: (Boolean) -> Unit, onTone: (String) -> Unit, onLength: (String) -> Unit, onEmoji: (String) -> Unit, onLanguage: (String) -> Unit, onApiUrl: (String) -> Unit) {
    var showProvider by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier, topBar = { CenterAlignedTopAppBar(title = { Text("Settings") }) }) { contentPadding ->
        LazyColumn(Modifier.fillMaxSize().padding(contentPadding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Personalize ReplyPal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Your preferences apply in both Normal and Floating Assistant modes.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { SettingSwitch("Floating Assistant", "User-enabled overlay with a persistent notification and an easy off switch.", prefs.floatingEnabled, onFloating) }
            item { ChoiceRow("Preferred tone", prefs.preferredTone, listOf("Casual", "Romantic", "Funny", "Respectful", "Confident"), onTone) }
            item { ChoiceRow("Reply length", prefs.responseLength, listOf("Very short", "Short", "Medium", "Detailed"), onLength) }
            item { ChoiceRow("Emoji usage", prefs.emojiLevel, listOf("None", "Low", "Normal", "High"), onEmoji) }
            item { ChoiceRow("Language", prefs.language, listOf("Auto", "English", "Hindi", "Gujarati", "Hinglish"), onLanguage) }
            item { OutlinedButton(onClick = { showProvider = true }, modifier = Modifier.fillMaxWidth()) { Text(if (prefs.apiBaseUrl.isBlank()) "Configure secure AI backend" else "Secure AI backend configured") } }
            item { PrivacyCard() }
        }
    }
    if (showProvider) ApiDialog(prefs.apiBaseUrl, onDismiss = { showProvider = false }, onSave = { showProvider = false; onApiUrl(it) })
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) { Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = checked, onCheckedChange = onChange) } } }

@Composable
private fun ChoiceRow(title: String, selected: String, values: List<String>, onSelect: (String) -> Unit) { Column { Text(title, fontWeight = FontWeight.SemiBold); Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 5.dp)) { values.take(4).forEach { value -> AssistChip(onClick = { onSelect(value) }, label = { Text(value) }, leadingIcon = if (selected == value) ({ Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp)) }) else null) } } } }

@Composable
private fun PrivacyCard() { Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF0EDFF)), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp)) { Text("Privacy by design", fontWeight = FontWeight.Bold); Text("ReplyPal never monitors other apps or sends messages. You explicitly paste text or share an image. OCR runs on-device. Conversation data is stored locally; only the text you choose to generate with is sent to your configured HTTPS AI service.", style = MaterialTheme.typography.bodySmall) } } }

@Composable
private fun NameDialog(title: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) { var value by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }) }, confirmButton = { Button(onClick = { onConfirm(value) }) { Text("Save") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }) }

@Composable
private fun ApiDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) { var value by remember { mutableStateOf(current) }; AlertDialog(onDismissRequest = onDismiss, title = { Text("Secure AI backend") }, text = { Column { Text("Use an HTTPS endpoint for the supplied backend. Never enter a model-provider secret here.", style = MaterialTheme.typography.bodySmall); OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("https://your-backend.example") }, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { Button(onClick = { onSave(value) }) { Text("Save") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }) }

private fun enableFloating(context: Context) {
    if (!Settings.canDrawOverlays(context)) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Toast.makeText(context, "Allow Display over other apps, then turn on Floating Assistant again.", Toast.LENGTH_LONG).show()
    } else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { /* notification permission is requested by system settings if needed; overlay remains user-controlled */ }
        context.startForegroundService(Intent(context, FloatingAssistantService::class.java))
    }
}

private fun copyToClipboard(context: Context, text: String) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ReplyPal suggestion", text)) }
