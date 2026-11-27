package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.model.LiveVoiceState
import com.example.data.model.TutorLanguage
import com.example.ui.theme.CoralError
import com.example.ui.theme.LeafSecondary
import com.example.ui.theme.SkyPrimary
import com.example.ui.theme.VioletAccent
import com.example.ui.viewmodel.AiAssistantUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTutorSheet(
  uiState: AiAssistantUiState,
  onSelectLanguage: (TutorLanguage) -> Unit,
  onSendMessage: (String) -> Unit,
  onStartVoice: () -> Unit,
  onStopVoice: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var draftText by remember { mutableStateOf("") }
  var micDeniedNotice by remember { mutableStateOf(false) }

  val micPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      micDeniedNotice = false
      onStartVoice()
    } else {
      micDeniedNotice = true
    }
  }

  fun handleMicTap() {
    if (!uiState.isOnline) return
    val isVoiceActiveOrConnecting =
      uiState.liveVoiceState is LiveVoiceState.Active || uiState.liveVoiceState is LiveVoiceState.Connecting
    if (isVoiceActiveOrConnecting) {
      onStopVoice()
      return
    }
    val hasPermission = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    if (hasPermission) {
      micDeniedNotice = false
      onStartVoice()
    } else {
      micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  ModalBottomSheet(
    onDismissRequest = {
      onStopVoice()
      onDismiss()
    },
    sheetState = sheetState,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 16.dp)
        .padding(bottom = 14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Surface(
            shape = CircleShape,
            color = VioletAccent.copy(alpha = 0.14f),
            modifier = Modifier.size(40.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = VioletAccent)
            }
          }
          Spacer(Modifier.width(10.dp))
          Column {
            Text("AI Science Voice Tutor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
              text = "Understands & speaks English, Tagalog, Hiligaynon, Kinaray-a",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        IconButton(onClick = {
          onStopVoice()
          onDismiss()
        }) {
          Icon(Icons.Rounded.Close, contentDescription = "Close AI Tutor")
        }
      }

      if (!uiState.isOnline) {
        OfflineAiWarningBanner(compact = true)
      }

      // Active question context banner (when opened before answering an item)
      val qCtx = uiState.questionContext
      if (qCtx != null) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = SkyPrimary.copy(alpha = 0.09f),
          border = BorderStroke(1.dp, SkyPrimary.copy(alpha = 0.35f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Text(
              text = "Asking about Grade ${qCtx.grade} • ${qCtx.contentTopic}",
              style = MaterialTheme.typography.labelSmall,
              color = SkyPrimary,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = qCtx.prompt,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      // Language Chips
      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        item {
          Icon(
            Icons.Rounded.Translate,
            contentDescription = "Language",
            tint = VioletAccent,
            modifier = Modifier.size(18.dp),
          )
        }
        items(TutorLanguage.entries) { lang ->
          FilterChip(
            selected = uiState.selectedLanguage == lang,
            onClick = { onSelectLanguage(lang) },
            label = { Text(lang.displayName, style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.testTag("tutor_lang_${lang.name.lowercase()}"),
          )
        }
      }

      // Live Voice Conversation Card (Gemini Live API)
      VoiceConversationCard(
        isOnline = uiState.isOnline,
        liveVoiceState = uiState.liveVoiceState,
        micDeniedNotice = micDeniedNotice,
        onToggleVoice = { handleMicTap() },
      )

      // Quick helper prompts
      val quickPrompts = if (qCtx != null) {
        listOf(
          "Explain this question" to "Explain what this question is asking in simple words without giving away the answer.",
          "Tagalog" to "Ipaliwanag sa Tagalog kung ano ang ibig sabihin ng tanong na ito at magbigay ng clue.",
          "Hiligaynon" to "Ipaathag sa Hiligaynon ang ini nga pamangkot kag maghatag sang clue.",
          "Kinaray-a" to "Ipaathag sa Kinaray-a ang dya nga pamangkot kag magtao ka clue.",
        )
      } else {
        listOf(
          "Talk in Tagalog" to "Magandang araw! Pwede mo ba akong turuan ng Science sa Tagalog?",
          "Talk in Hiligaynon" to "Maayong adlaw! Pwede mo ako buligan magtuon sang Science sa Hiligaynon?",
          "Talk in Kinaray-a" to "Maayong adlaw! Pwede mo ako tudluan ka Science sa Kinaray-a?",
        )
      }
      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
      ) {
        items(quickPrompts) { (chipLabel, promptText) ->
          SuggestionChip(
            onClick = { onSendMessage(promptText) },
            enabled = uiState.isOnline && !uiState.isSendingText,
            label = { Text(chipLabel, style = MaterialTheme.typography.labelSmall) },
          )
        }
      }

      // Error message (e.g., offline or API error)
      uiState.tutorError?.let { err ->
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = CoralError.copy(alpha = 0.12f),
          border = BorderStroke(1.dp, CoralError.copy(alpha = 0.45f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = err,
            style = MaterialTheme.typography.bodySmall,
            color = CoralError,
            modifier = Modifier.padding(10.dp),
          )
        }
      }

      // Message bubbles
      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 120.dp, max = 230.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(uiState.messages, key = { it.id }) { msg ->
          val bubbleBg = if (msg.isUser) {
            SkyPrimary.copy(alpha = 0.14f)
          } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
          ) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = bubbleBg,
              modifier = Modifier.fillMaxWidth(0.88f),
            ) {
              Column(modifier = Modifier.padding(10.dp)) {
                Text(
                  text = if (msg.isUser) {
                    if (msg.isVoiceTranscript) "You (Voice)" else "You"
                  } else {
                    if (msg.isVoiceTranscript) "Teacher Kiko (Voice)" else "Teacher Kiko"
                  },
                  style = MaterialTheme.typography.labelSmall,
                  color = if (msg.isUser) SkyPrimary else VioletAccent,
                  fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                  text = if (msg.text.isBlank() && msg.isStreaming) "Thinking..." else msg.text,
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          }
        }
      }

      // Text input row
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = draftText,
          onValueChange = { draftText = it },
          enabled = uiState.isOnline,
          placeholder = {
            Text(
              if (uiState.isOnline) {
                "Ask in Tagalog, Hiligaynon, Kinaray-a, or English..."
              } else {
                "Connect to the internet to ask AI..."
              },
              style = MaterialTheme.typography.bodySmall,
            )
          },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier
            .weight(1f)
            .testTag("tutor_text_input"),
        )
        Surface(
          onClick = {
            val toSend = draftText
            draftText = ""
            onSendMessage(toSend)
          },
          enabled = uiState.isOnline && !uiState.isSendingText && draftText.isNotBlank(),
          shape = RoundedCornerShape(14.dp),
          color = if (uiState.isOnline && draftText.isNotBlank()) SkyPrimary else MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier
            .size(50.dp)
            .testTag("tutor_send_button"),
        ) {
          Box(contentAlignment = Alignment.Center) {
            if (uiState.isSendingText) {
              CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = "Send question",
                tint = if (uiState.isOnline && draftText.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun VoiceConversationCard(
  isOnline: Boolean,
  liveVoiceState: LiveVoiceState,
  micDeniedNotice: Boolean,
  onToggleVoice: () -> Unit,
) {
  val isConnecting = liveVoiceState is LiveVoiceState.Connecting
  val isActive = liveVoiceState is LiveVoiceState.Active

  val pulse = rememberInfiniteTransition(label = "voice_pulse")
  val scale by pulse.animateFloat(
    initialValue = 1f,
    targetValue = if (isActive) 1.08f else 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(700),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "voice_scale",
  )

  Surface(
    shape = RoundedCornerShape(18.dp),
    color = when {
      isActive -> LeafSecondary.copy(alpha = 0.12f)
      !isOnline -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
      else -> VioletAccent.copy(alpha = 0.08f)
    },
    border = BorderStroke(
      1.2.dp,
      when {
        isActive -> LeafSecondary
        !isOnline -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        else -> VioletAccent.copy(alpha = 0.35f)
      },
    ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = when {
              !isOnline -> "Voice Conversation Unavailable Offline"
              isConnecting -> "Connecting Live Voice..."
              isActive -> "Live Voice Active — Speak Now!"
              else -> "Talk Live with AI Tutor"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = when {
              !isOnline -> "Internet needed for live voice conversation."
              isActive -> "Speak in Tagalog, Hiligaynon, Kinaray-a, or English."
              else -> "Tap the mic to speak and hear real-time voice answers."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.width(8.dp))
        Button(
          onClick = onToggleVoice,
          enabled = isOnline,
          colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) CoralError else VioletAccent,
            contentColor = Color.White,
          ),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .height(48.dp)
            .scale(scale)
            .testTag("voice_conversation_button"),
        ) {
          when {
            isConnecting -> {
              CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
              )
              Spacer(Modifier.width(6.dp))
              Text("Cancel")
            }
            isActive -> {
              Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("End Voice")
            }
            else -> {
              Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Talk")
            }
          }
        }
      }

      if (micDeniedNotice) {
        Text(
          text = "Microphone permission was denied. Enable microphone permission in settings to talk by voice, or type your question below.",
          style = MaterialTheme.typography.bodySmall,
          color = CoralError,
        )
      }

      if (liveVoiceState is LiveVoiceState.Error) {
        Text(
          text = liveVoiceState.message,
          style = MaterialTheme.typography.bodySmall,
          color = CoralError,
        )
      }

      if (liveVoiceState is LiveVoiceState.Active &&
        (liveVoiceState.userTranscript.isNotBlank() || liveVoiceState.modelTranscript.isNotBlank())
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = LeafSecondary, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Live Transcript", style = MaterialTheme.typography.labelSmall, color = LeafSecondary, fontWeight = FontWeight.Bold)
            }
            if (liveVoiceState.userTranscript.isNotBlank()) {
              Text("You: ${liveVoiceState.userTranscript}", style = MaterialTheme.typography.bodySmall)
            }
            if (liveVoiceState.modelTranscript.isNotBlank()) {
              Text("Tutor: ${liveVoiceState.modelTranscript}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
          }
        }
      }
    }
  }
}
