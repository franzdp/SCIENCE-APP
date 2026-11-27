package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.SunGold

@Composable
fun OfflineAiWarningBanner(
  modifier: Modifier = Modifier,
  compact: Boolean = false,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = SunGold.copy(alpha = 0.14f),
    border = BorderStroke(1.2.dp, SunGold.copy(alpha = 0.65f)),
    modifier = modifier
      .fillMaxWidth()
      .testTag("offline_ai_warning_banner"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Rounded.WifiOff,
        contentDescription = "Offline warning",
        tint = SunGold,
        modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.width(10.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Internet Connection Required for AI",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurface,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = if (compact) {
            "Connect to the internet to talk with the AI Tutor or generate games. Offline lessons continue normally."
          } else {
            "You are currently offline. AI voice conversations and game generation are disabled, but all offline Science lessons, challenges, and PDF reports remain available."
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
