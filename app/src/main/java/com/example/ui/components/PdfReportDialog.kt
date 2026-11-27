package com.example.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.repository.LearnerReportSnapshot
import com.example.ui.theme.LeafSecondary
import com.example.ui.theme.SkyContainer
import com.example.ui.theme.SkyPrimary
import com.example.ui.theme.SunGold
import com.example.ui.viewmodel.AppUiState
import com.example.util.PdfReportScope
import com.example.util.SciencePdfReportRequest
import com.example.util.ScienceReportPdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PdfReportDialog(
  uiState: AppUiState,
  onLoadAllSnapshots: suspend () -> List<LearnerReportSnapshot>,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var selectedScope by remember { mutableStateOf(PdfReportScope.SELECTED_GRADE) }
  var isGenerating by remember { mutableStateOf(false) }

  suspend fun buildRequest(scope: PdfReportScope): SciencePdfReportRequest {
    val snapshots = if (scope == PdfReportScope.ALL_LEARNERS) {
      withContext(Dispatchers.IO) { onLoadAllSnapshots() }
    } else {
      emptyList()
    }
    return SciencePdfReportRequest(
      scope = scope,
      activeLearner = uiState.stats,
      allLessons = uiState.allLessons,
      progressMap = uiState.progressMap,
      inProgressMap = uiState.inProgressMap,
      mistakes = uiState.mistakes,
      activityLogs = uiState.activityLogs,
      badges = uiState.badges,
      allLearnerSnapshots = snapshots,
    )
  }

  val saveDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/pdf"),
  ) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    coroutineScope.launch {
      isGenerating = true
      val result = runCatching {
        val request = buildRequest(selectedScope)
        withContext(Dispatchers.IO) {
          context.contentResolver.openOutputStream(uri)?.use { out ->
            ScienceReportPdfGenerator.writePdf(context, request, out)
          } ?: error("Unable to open destination file")
        }
      }
      isGenerating = false
      if (result.isSuccess) {
        Toast.makeText(context, "ARAL PDF report saved!", Toast.LENGTH_LONG).show()
      } else {
        Toast.makeText(
          context,
          result.exceptionOrNull()?.message ?: "Failed to save PDF",
          Toast.LENGTH_LONG,
        ).show()
      }
    }
  }

  val gradeLessons = remember(uiState.allLessons, uiState.stats.selectedGrade) {
    uiState.allLessons.filter { it.grade == uiState.stats.selectedGrade }
  }
  val scopeActivityCount = when (selectedScope) {
    PdfReportScope.SELECTED_GRADE -> gradeLessons.size
    PdfReportScope.ALL_GRADES -> uiState.allLessons.size
    PdfReportScope.ALL_LEARNERS -> uiState.allLessons.size
  }
  val scopeCompletedCount = when (selectedScope) {
    PdfReportScope.SELECTED_GRADE -> gradeLessons.count { (uiState.progressMap[it.id]?.stars ?: 0) >= 1 }
    PdfReportScope.ALL_GRADES -> uiState.completedLessonsCount
    PdfReportScope.ALL_LEARNERS -> uiState.learners.size
  }

  AlertDialog(
    onDismissRequest = { if (!isGenerating) onDismiss() },
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Rounded.PictureAsPdf,
          contentDescription = null,
          tint = SkyPrimary,
          modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
          Text("ARAL PDF Progress Report", style = MaterialTheme.typography.titleLarge)
          Text(
            text = "SCIENTISTS of Bagacay ES",
            style = MaterialTheme.typography.labelMedium,
            color = SkyPrimary,
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
        Text(
          text = "Select Report Scope",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
        )

        PdfReportScope.entries.forEach { scope ->
          val label = when (scope) {
            PdfReportScope.SELECTED_GRADE -> "Grade ${uiState.stats.selectedGrade} Activities (${uiState.stats.learnerName})"
            PdfReportScope.ALL_GRADES -> "All Grades G3–G6 (${uiState.stats.learnerName})"
            PdfReportScope.ALL_LEARNERS -> "All Learners Summary (${uiState.learners.size} profiles)"
          }
          val selected = selectedScope == scope
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (selected) SkyContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(
              1.2.dp,
              if (selected) SkyPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            ),
            modifier = Modifier
              .fillMaxWidth()
              .clickable(enabled = !isGenerating) { selectedScope = scope }
              .testTag("pdf_scope_${scope.name.lowercase()}"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = selected,
                onClick = { if (!isGenerating) selectedScope = scope },
              )
              Spacer(Modifier.width(4.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                  scope.subtitle,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }

        // Preview Summary Card
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              "Included in ARAL Format PDF:",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
            )
            val inProgressCount = when (selectedScope) {
              PdfReportScope.SELECTED_GRADE -> gradeLessons.count { uiState.inProgressMap.containsKey(it.id) }
              else -> uiState.inProgressMap.size
            }
            val summaryLine = if (selectedScope == PdfReportScope.ALL_LEARNERS) {
              "• ${uiState.learners.size} learners compared side-by-side + individual breakdowns"
            } else {
              "• $scopeCompletedCount / $scopeActivityCount completed" +
                if (inProgressCount > 0) " ($inProgressCount in progress)" else ""
            }
            Text(summaryLine, style = MaterialTheme.typography.bodySmall)
            Text(
              "• Topics with difficulties & missed items (${uiState.mistakes.size} after 2nd try)",
              style = MaterialTheme.typography.bodySmall,
            )
            Text(
              "• Mastery levels, unfinished items, session logs (${uiState.activityLogs.size}) & signatures",
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }

        if (isGenerating) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(10.dp))
            Text("Generating ARAL PDF report...", style = MaterialTheme.typography.bodySmall)
          }
        }

        // Export Actions
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            enabled = !isGenerating,
            onClick = {
              coroutineScope.launch {
                isGenerating = true
                val res = runCatching {
                  val req = buildRequest(selectedScope)
                  withContext(Dispatchers.IO) {
                    ScienceReportPdfGenerator.generatePdfToCache(context, req)
                  }
                }
                isGenerating = false
                res.onSuccess { file ->
                  ScienceReportPdfGenerator.sharePdf(context, file)
                }.onFailure { err ->
                  Toast.makeText(context, err.message ?: "Failed to generate PDF", Toast.LENGTH_LONG).show()
                }
              }
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = SkyPrimary,
              contentColor = Color.White,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("pdf_share_button"),
          ) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Share", fontWeight = FontWeight.SemiBold)
          }

          Button(
            enabled = !isGenerating,
            onClick = {
              val placeholderReq = SciencePdfReportRequest(
                scope = selectedScope,
                activeLearner = uiState.stats,
                allLessons = uiState.allLessons,
                progressMap = uiState.progressMap,
                inProgressMap = uiState.inProgressMap,
                mistakes = uiState.mistakes,
                activityLogs = uiState.activityLogs,
                badges = uiState.badges,
              )
              saveDocumentLauncher.launch(ScienceReportPdfGenerator.suggestedFileName(placeholderReq))
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = LeafSecondary,
              contentColor = Color.White,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("pdf_save_button"),
          ) {
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save", fontWeight = FontWeight.SemiBold)
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            enabled = !isGenerating,
            onClick = {
              coroutineScope.launch {
                isGenerating = true
                val res = runCatching {
                  val req = buildRequest(selectedScope)
                  withContext(Dispatchers.IO) {
                    ScienceReportPdfGenerator.generatePdfToCache(context, req)
                  }
                }
                isGenerating = false
                res.onSuccess { file ->
                  ScienceReportPdfGenerator.openPdf(context, file)
                }.onFailure { err ->
                  Toast.makeText(context, err.message ?: "Unable to open PDF", Toast.LENGTH_LONG).show()
                }
              }
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("pdf_open_button"),
          ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open PDF")
          }

          OutlinedButton(
            enabled = !isGenerating,
            onClick = {
              coroutineScope.launch {
                isGenerating = true
                val res = runCatching {
                  val req = buildRequest(selectedScope)
                  val file = withContext(Dispatchers.IO) {
                    ScienceReportPdfGenerator.generatePdfToCache(context, req)
                  }
                  file to req
                }
                isGenerating = false
                res.onSuccess { (file, req) ->
                  ScienceReportPdfGenerator.printPdf(
                    context = context,
                    cachedFile = file,
                    jobName = "ARAL Science Report - ${req.activeLearner.learnerName}",
                    fileName = ScienceReportPdfGenerator.suggestedFileName(req),
                  )
                }.onFailure { err ->
                  Toast.makeText(context, err.message ?: "Unable to print PDF", Toast.LENGTH_LONG).show()
                }
              }
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("pdf_print_button"),
          ) {
            Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Print")
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = !isGenerating,
        onClick = onDismiss,
      ) {
        Text("Close")
      }
    },
  )
}
