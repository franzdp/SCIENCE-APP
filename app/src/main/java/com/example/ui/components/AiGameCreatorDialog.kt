package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AiGameType
import com.example.data.model.GameGeneratorState
import com.example.data.model.TutorLanguage
import com.example.ui.theme.CoralError
import com.example.ui.theme.LeafSecondary
import com.example.ui.theme.SkyPrimary
import com.example.ui.theme.VioletAccent

@Composable
fun AiGameCreatorDialog(
  isOnline: Boolean,
  defaultGrade: Int,
  generatorState: GameGeneratorState,
  onGenerate: (topic: String, gradeLevel: Int, language: TutorLanguage, gameType: AiGameType) -> Unit,
  onDismiss: () -> Unit,
) {
  var selectedGrade by remember { mutableIntStateOf(defaultGrade.coerceIn(3, 6)) }
  var selectedLanguage by remember { mutableStateOf(TutorLanguage.ENGLISH) }
  var selectedType by remember { mutableStateOf(AiGameType.ARCADE_CATCHER) }
  var topicInput by remember { mutableStateOf("") }

  val presetTopics = remember(selectedGrade) {
    when (selectedGrade) {
      3 -> listOf("Properties of Matter", "States of Matter", "Rocks & Soil", "Weather & Sun")
      4 -> listOf("Plant & Animal Parts", "Force & Motion", "Light & Heat", "Water Cycle")
      5 -> listOf("Mixtures & Solutions", "Body Systems", "Simple Machines", "Typhoons & Weather")
      else -> listOf("Circuits & Electricity", "Volcanoes & Earthquakes", "Solar System", "Ecosystems")
    }
  }

  val isGenerating = generatorState is GameGeneratorState.Generating

  AlertDialog(
    onDismissRequest = { if (!isGenerating) onDismiss() },
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.SportsEsports, contentDescription = null, tint = VioletAccent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(8.dp))
        Column {
          Text("AI Science Game Maker", style = MaterialTheme.typography.titleLarge)
          Text(
            text = "Create custom interactive games",
            style = MaterialTheme.typography.labelMedium,
            color = VioletAccent,
          )
        }
      }
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (!isOnline) {
          OfflineAiWarningBanner(compact = true)
        }

        Text("1. Choose Game Mode", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        AiGameType.entries.forEach { mode ->
          val selected = selectedType == mode
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (selected) VioletAccent.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            border = BorderStroke(
              1.2.dp,
              if (selected) VioletAccent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            ),
            modifier = Modifier
              .fillMaxWidth()
              .clickable(enabled = !isGenerating) { selectedType = mode }
              .testTag("game_type_${mode.name.lowercase()}"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = selected,
                onClick = { if (!isGenerating) selectedType = mode },
              )
              Column(modifier = Modifier.weight(1f)) {
                Text("${mode.title} (${mode.badge})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(mode.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
        }

        Text("2. Grade & Language", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf(3, 4, 5, 6).forEach { g ->
            FilterChip(
              selected = selectedGrade == g,
              onClick = { if (!isGenerating) selectedGrade = g },
              label = { Text("G$g") },
            )
          }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          items(listOf(TutorLanguage.ENGLISH, TutorLanguage.TAGALOG, TutorLanguage.HILIGAYNON, TutorLanguage.KINARAY_A)) { lang ->
            FilterChip(
              selected = selectedLanguage == lang,
              onClick = { if (!isGenerating) selectedLanguage = lang },
              label = { Text(lang.displayName) },
            )
          }
        }

        Text("3. Science Topic", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(
          value = topicInput,
          onValueChange = { topicInput = it },
          enabled = isOnline && !isGenerating,
          placeholder = { Text(presetTopics.first()) },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("game_topic_input"),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          items(presetTopics) { topic ->
            SuggestionChip(
              onClick = { if (!isGenerating) topicInput = topic },
              enabled = isOnline && !isGenerating,
              label = { Text(topic, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }

        if (isGenerating) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(10.dp))
            Text("Building your Science game...", style = MaterialTheme.typography.bodySmall)
          }
        }

        if (generatorState is GameGeneratorState.Error) {
          Text(
            text = generatorState.message,
            style = MaterialTheme.typography.bodySmall,
            color = CoralError,
          )
        }
      }
    },
    confirmButton = {
      Button(
        enabled = isOnline && !isGenerating,
        onClick = {
          onGenerate(
            topicInput.ifBlank { presetTopics.first() },
            selectedGrade,
            selectedLanguage,
            selectedType,
          )
        },
        colors = ButtonDefaults.buttonColors(
          containerColor = LeafSecondary,
          contentColor = Color.White,
        ),
        modifier = Modifier.height(48.dp).testTag("generate_game_submit_button"),
      ) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Generate & Play", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(enabled = !isGenerating, onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
