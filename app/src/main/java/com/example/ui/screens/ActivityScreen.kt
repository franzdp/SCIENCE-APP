package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.QuestionType
import com.example.data.repository.LearnerReportSnapshot
import com.example.ui.components.PdfReportDialog
import com.example.ui.components.QuestionInteractionBody
import com.example.ui.theme.CoralError
import com.example.ui.theme.LeafSecondary
import com.example.ui.theme.SkyPrimary
import com.example.ui.theme.SunGold
import com.example.ui.viewmodel.ActivitySessionState
import com.example.ui.viewmodel.AppUiState
import com.example.ui.viewmodel.MainViewModel

@Composable
fun ActivityScreen(
  session: ActivitySessionState,
  uiState: AppUiState,
  ttsAutoRead: Boolean,
  onSpeakText: (String) -> Unit,
  onStopSpeech: () -> Unit,
  onLoadAllSnapshots: suspend () -> List<LearnerReportSnapshot>,
  viewModel: MainViewModel,
) {
  var showExitConfirm by remember { mutableStateOf(false) }
  var showPdfReportDialog by remember { mutableStateOf(false) }

  BackHandler {
    if (session.isCompleted) {
      onStopSpeech()
      viewModel.exitSession()
    } else {
      showExitConfirm = true
    }
  }

  val curQ = session.currentQuestion
  LaunchedEffect(curQ?.id, ttsAutoRead) {
    if (ttsAutoRead && curQ != null && !session.isCompleted) {
      onSpeakText(curQ.prompt)
    }
  }

  if (session.isCompleted) {
    LessonSummaryView(
      session = session,
      onRetryMissed = { viewModel.startRetryMissedInSession() },
      onSendReport = { showPdfReportDialog = true },
      onDone = {
        onStopSpeech()
        viewModel.exitSession()
      },
    )
    if (showPdfReportDialog) {
      PdfReportDialog(
        uiState = uiState,
        onLoadAllSnapshots = onLoadAllSnapshots,
        onDismiss = { showPdfReportDialog = false },
      )
    }
    return
  }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = {
      ActivityBottomBar(
        session = session,
        canSubmit = viewModel.canSubmitCurrentAnswer(),
        onSubmit = {
          onStopSpeech()
          viewModel.submitAnswer()
        },
        onRetrySecondAttempt = {
          onStopSpeech()
          viewModel.retrySecondAttempt()
        },
        onNext = {
          onStopSpeech()
          viewModel.nextQuestion()
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier
          .widthIn(max = 680.dp)
          .fillMaxSize()
          .padding(horizontal = 16.dp),
      ) {
        // Top Header: Close + Progress Bar + Streak + TTS Button
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(
            onClick = { showExitConfirm = true },
            modifier = Modifier.testTag("close_activity_button"),
          ) {
            Icon(Icons.Rounded.Close, contentDescription = "Exit activity")
          }
          val progress = ((session.currentIndex + 1).toFloat() / session.questions.size.coerceAtLeast(1))
          LinearProgressIndicator(
            progress = { progress },
            color = SkyPrimary,
            trackColor = SkyPrimary.copy(alpha = 0.18f),
            modifier = Modifier
              .weight(1f)
              .height(10.dp)
              .clip(RoundedCornerShape(50)),
          )
          Spacer(Modifier.width(10.dp))
          if (session.currentStreakInSession >= 2) {
            Surface(
              color = CoralError.copy(alpha = 0.14f),
              shape = RoundedCornerShape(50),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  Icons.Rounded.LocalFireDepartment,
                  contentDescription = null,
                  tint = CoralError,
                  modifier = Modifier.size(16.dp),
                )
                Text(
                  "${session.currentStreakInSession}",
                  style = MaterialTheme.typography.labelMedium,
                  color = CoralError,
                )
              }
            }
            Spacer(Modifier.width(6.dp))
          }
          Text(
            text = "${session.currentIndex + 1}/${session.questions.size}",
            style = MaterialTheme.typography.labelLarge,
          )
        }

        if (curQ != null) {
          Column(
            modifier = Modifier
              .weight(1f)
              .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            // Activity Type Tag + Attempt Badge + Read Aloud Button
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Surface(
                  color = MaterialTheme.colorScheme.primaryContainer,
                  shape = RoundedCornerShape(8.dp),
                ) {
                  Text(
                    text = friendlyTypeLabel(curQ.type, curQ.isPictureMode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                  )
                }
                Surface(
                  color = if (session.attemptNumber == 2) SunGold.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier.testTag("attempt_badge"),
                ) {
                  Text(
                    text = if (session.attemptNumber == 2) "Try 2 of 2" else "Try 1 of 2",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (session.attemptNumber == 2) SunGold else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                  )
                }
              }
              Surface(
                onClick = { onSpeakText(curQ.prompt) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier.testTag("tts_speak_button"),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Icon(
                    Icons.Rounded.VolumeUp,
                    contentDescription = "Read aloud",
                    tint = SkyPrimary,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(Modifier.width(4.dp))
                  Text("Read Aloud", style = MaterialTheme.typography.labelMedium, color = SkyPrimary)
                }
              }
            }

            // Question Prompt Card
            Surface(
              shape = RoundedCornerShape(20.dp),
              color = MaterialTheme.colorScheme.surface,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = curQ.prompt,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
              )
            }

            // Interactive Body
            QuestionInteractionBody(
              question = curQ,
              session = session,
              viewModel = viewModel,
            )

            Spacer(Modifier.height(16.dp))
          }
        }
      }
    }
  }

  if (showExitConfirm) {
    val resumeItemNumber = (if (session.feedback != null && !session.isRetryPending) {
      session.currentIndex + 2
    } else {
      session.currentIndex + 1
    }).coerceAtMost(session.questions.size.coerceAtLeast(1))

    AlertDialog(
      onDismissRequest = { showExitConfirm = false },
      title = { Text("Pause & Save Activity?") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            if (session.lessonId != null) {
              "You answered ${session.answeredCount} of ${session.questions.size} items. Your progress is saved so you can return at Item #$resumeItemNumber — or send your progress report now even before finishing."
            } else {
              "You answered ${session.answeredCount} of ${session.questions.size} items. Your XP and accuracy so far are saved, and you can send your progress report now."
            }
          )
          OutlinedButton(
            onClick = {
              showExitConfirm = false
              onStopSpeech()
              viewModel.saveAndPauseSession(closeSession = false)
              showPdfReportDialog = true
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .testTag("exit_send_report_button"),
          ) {
            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = SkyPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Send Progress Report (PDF)", color = SkyPrimary, fontWeight = FontWeight.SemiBold)
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showExitConfirm = false
            onStopSpeech()
            viewModel.saveAndPauseSession(closeSession = true)
          },
          colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary),
          modifier = Modifier.testTag("exit_save_button"),
        ) {
          Text("Save & Exit")
        }
      },
      dismissButton = {
        TextButton(onClick = { showExitConfirm = false }) { Text("Keep Learning") }
      },
    )
  }

  if (showPdfReportDialog) {
    PdfReportDialog(
      uiState = uiState,
      onLoadAllSnapshots = onLoadAllSnapshots,
      onDismiss = { showPdfReportDialog = false },
    )
  }
}

@Composable
private fun ActivityBottomBar(
  session: ActivitySessionState,
  canSubmit: Boolean,
  onSubmit: () -> Unit,
  onRetrySecondAttempt: () -> Unit,
  onNext: () -> Unit,
) {
  val fb = session.feedback
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = 8.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        AnimatedVisibility(
          visible = fb != null,
          enter = fadeIn() + slideInVertically { it / 2 },
        ) {
          if (fb != null) {
            val fbColor = when {
              fb.isCorrect -> LeafSecondary
              session.isRetryPending -> SunGold
              else -> CoralError
            }
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = fbColor.copy(alpha = 0.13f),
              border = BorderStroke(1.5.dp, fbColor),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
              ) {
                Icon(
                  imageVector = if (fb.isCorrect) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                  contentDescription = null,
                  tint = fbColor,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = fb.headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = fbColor,
                    fontWeight = FontWeight.Bold,
                  )
                  Spacer(Modifier.height(2.dp))
                  Text(
                    text = fb.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                }
              }
            }
          }
        }

        if (fb == null) {
          Button(
            onClick = onSubmit,
            enabled = canSubmit,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(52.dp)
              .testTag("check_answer_button"),
          ) {
            Text(
              text = if (session.attemptNumber == 2) "Check Answer (2nd Try)" else "Check Answer",
              style = MaterialTheme.typography.titleSmall,
            )
          }
        } else if (session.isRetryPending) {
          Button(
            onClick = onRetrySecondAttempt,
            colors = ButtonDefaults.buttonColors(
              containerColor = SunGold,
              contentColor = Color(0xFF422006),
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(52.dp)
              .testTag("try_again_button"),
          ) {
            Text(
              text = "Try Again (1 Try Left)",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
            )
          }
        } else {
          val isLast = session.currentIndex >= session.questions.lastIndex
          Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(
              containerColor = if (fb.isCorrect) LeafSecondary else SkyPrimary,
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(52.dp)
              .testTag("continue_button"),
          ) {
            Text(
              text = if (isLast) "See Results" else "Continue",
              style = MaterialTheme.typography.titleSmall,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LessonSummaryView(
  session: ActivitySessionState,
  onRetryMissed: () -> Unit,
  onSendReport: () -> Unit,
  onDone: () -> Unit,
) {
  val total = session.questions.size.coerceAtLeast(1)
  val accuracy = (session.correctCount * 100f / total).toInt()
  val stars = when {
    accuracy >= 85 -> 3
    accuracy >= 65 -> 2
    accuracy >= 40 -> 1
    else -> 0
  }

  Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = BorderStroke(1.5.dp, SkyPrimary.copy(alpha = 0.35f)),
        modifier = Modifier.widthIn(max = 500.dp).fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            color = SunGold.copy(alpha = 0.16f),
            shape = RoundedCornerShape(50),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = SunGold, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("ACTIVITY COMPLETE!", style = MaterialTheme.typography.labelLarge, color = SunGold)
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in 1..3) {
              Icon(
                imageVector = if (s <= stars) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = null,
                tint = if (s <= stars) SunGold else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(48.dp),
              )
            }
          }

          Text(
            text = when (stars) {
              3 -> "Outstanding Scientist!"
              2 -> "Great Discovery!"
              1 -> "Good Effort — Keep Exploring!"
              else -> "Nice Try — Practice Makes Progress!"
            },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
          )
          Text(
            text = session.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            SummaryStatBox("Score", "${session.correctCount}/$total", SkyPrimary, Modifier.weight(1f))
            SummaryStatBox("Accuracy", "$accuracy%", LeafSecondary, Modifier.weight(1f))
            SummaryStatBox("XP Earned", "+${session.sessionXp}", SunGold, Modifier.weight(1f))
          }

          Button(
            onClick = onSendReport,
            colors = ButtonDefaults.buttonColors(
              containerColor = LeafSecondary,
              contentColor = Color.White,
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(50.dp)
              .testTag("completion_send_report_button"),
          ) {
            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Send Progress Report (PDF)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
          }

          if (session.missedInSession.isNotEmpty()) {
            OutlinedButton(
              onClick = onRetryMissed,
              shape = RoundedCornerShape(16.dp),
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("retry_missed_button"),
            ) {
              Icon(Icons.Rounded.Replay, contentDescription = null)
              Spacer(Modifier.width(8.dp))
              Text("Review ${session.missedInSession.size} Missed Items")
            }
          }

          OutlinedButton(
            onClick = onDone,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .testTag("summary_done_button"),
          ) {
            Text("Back to Science Hub", style = MaterialTheme.typography.titleSmall)
          }
        }
      }
    }
  }
}

@Composable
private fun SummaryStatBox(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = color.copy(alpha = 0.12f),
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
      Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

private fun friendlyTypeLabel(type: QuestionType, isPictureMode: Boolean): String = when (type) {
  QuestionType.MULTIPLE_CHOICE -> "Multiple Choice"
  QuestionType.TRUE_FALSE -> "True or False"
  QuestionType.MULTI_SELECT -> if (isPictureMode) "Select Pictures" else "Select Objects"
  QuestionType.MATCHING -> "Matching Type"
  QuestionType.SORT_BUCKETS -> "Interactive Sort"
  QuestionType.ORDER_STEPS -> "Put in Order"
  QuestionType.HOTSPOT -> "Interactive Diagram"
  QuestionType.SLIDER -> "Interactive Ruler"
  QuestionType.SIMULATION -> "Science Simulation"
  QuestionType.FILL_BLANK -> "Complete Sentence"
}
