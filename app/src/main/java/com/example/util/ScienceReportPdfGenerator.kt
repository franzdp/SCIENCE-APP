package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.local.ActivityLogEntity
import com.example.data.local.LearnerStatsEntity
import com.example.data.local.LessonInProgressEntity
import com.example.data.local.LessonProgressEntity
import com.example.data.local.MistakeEntity
import com.example.data.model.ScienceBadge
import com.example.data.model.ScienceLesson
import com.example.data.repository.LearnerReportSnapshot
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PdfReportScope(
  val title: String,
  val subtitle: String,
) {
  SELECTED_GRADE(
    title = "Selected Grade Activities",
    subtitle = "Detailed activity-by-activity report for the active grade level",
  ),
  ALL_GRADES(
    title = "All Grades (G3–G6) Activities",
    subtitle = "Complete learner portfolio across all 52 Science activities",
  ),
  ALL_LEARNERS(
    title = "All Learners Class Summary",
    subtitle = "Consolidated comparison & activity progress for all learners on this device",
  ),
}

data class SciencePdfReportRequest(
  val scope: PdfReportScope,
  val activeLearner: LearnerStatsEntity,
  val allLessons: List<ScienceLesson>,
  val progressMap: Map<String, LessonProgressEntity>,
  val inProgressMap: Map<String, LessonInProgressEntity> = emptyMap(),
  val mistakes: List<MistakeEntity>,
  val activityLogs: List<ActivityLogEntity>,
  val badges: List<ScienceBadge>,
  val allLearnerSnapshots: List<LearnerReportSnapshot> = emptyList(),
)

object ScienceReportPdfGenerator {

  private const val PAGE_WIDTH = 612
  private const val PAGE_HEIGHT = 792
  private const val MARGIN_X = 36f
  private const val MARGIN_TOP = 36f
  private const val FOOTER_Y = 762f
  private const val CONTENT_BOTTOM = 736f
  private const val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_X * 2) // 540f

  private val COLOR_NAVY = Color.parseColor("#0F172A")
  private val COLOR_SLATE = Color.parseColor("#475569")
  private val COLOR_MUTED = Color.parseColor("#64748B")
  private val COLOR_SKY = Color.parseColor("#0284C7")
  private val COLOR_SKY_DARK = Color.parseColor("#0C4A6E")
  private val COLOR_SKY_BG = Color.parseColor("#E0F2FE")
  private val COLOR_GREEN = Color.parseColor("#059669")
  private val COLOR_GREEN_BG = Color.parseColor("#D1FAE5")
  private val COLOR_AMBER = Color.parseColor("#D97706")
  private val COLOR_AMBER_BG = Color.parseColor("#FEF3C7")
  private val COLOR_CORAL = Color.parseColor("#E11D48")
  private val COLOR_CORAL_BG = Color.parseColor("#FFE4E6")
  private val COLOR_GOLD = Color.parseColor("#FACC15")
  private val COLOR_ROW_ALT = Color.parseColor("#F8FAFC")
  private val COLOR_BORDER = Color.parseColor("#CBD5E1")
  private val COLOR_GRAY_BG = Color.parseColor("#F1F5F9")

  fun suggestedFileName(request: SciencePdfReportRequest): String {
    val dateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    val safeName = when (request.scope) {
      PdfReportScope.ALL_LEARNERS -> "All_Learners"
      else -> request.activeLearner.learnerName
        .replace(Regex("[^A-Za-z0-9_-]+"), "_")
        .trim('_')
        .ifBlank { "Learner" }
    }
    val scopeSuffix = when (request.scope) {
      PdfReportScope.SELECTED_GRADE -> "G${request.activeLearner.selectedGrade}"
      PdfReportScope.ALL_GRADES -> "AllGrades"
      PdfReportScope.ALL_LEARNERS -> "ClassSummary"
    }
    return "ARAL_Science_Report_${safeName}_${scopeSuffix}_$dateStamp.pdf"
  }

  fun generatePdfToCache(context: Context, request: SciencePdfReportRequest): File {
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    dir.listFiles()?.forEach { old ->
      if (System.currentTimeMillis() - old.lastModified() > 24 * 60 * 60 * 1000L) {
        old.delete()
      }
    }
    val outFile = File(dir, suggestedFileName(request))
    FileOutputStream(outFile).use { fos ->
      writePdf(context, request, fos)
    }
    return outFile
  }

  fun writePdf(context: Context, request: SciencePdfReportRequest, outputStream: OutputStream) {
    val doc = PdfDocument()
    val logo = decodeLogoBitmap(context)
    val writer = PdfPageWriter(doc, logo, request)
    try {
      writer.buildReport()
      doc.writeTo(outputStream)
      outputStream.flush()
    } finally {
      doc.close()
      logo?.recycle()
    }
  }

  fun sharePdf(context: Context, pdfFile: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "application/pdf"
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_SUBJECT, "ARAL Science Progress Report - SCIENTISTS of Bagacay ES")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ARAL Science PDF Report"))
  }

  fun openPdf(context: Context, pdfFile: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/pdf")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(viewIntent, "Open ARAL Science PDF Report")
    try {
      context.startActivity(chooser)
    } catch (_: Exception) {
      sharePdf(context, pdfFile)
    }
  }

  fun printPdf(context: Context, cachedFile: File, jobName: String, fileName: String) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val adapter = object : PrintDocumentAdapter() {
      override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?,
      ) {
        if (cancellationSignal?.isCanceled == true) {
          callback?.onLayoutCancelled()
          return
        }
        val info = PrintDocumentInfo.Builder(fileName)
          .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
          .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
          .build()
        callback?.onLayoutFinished(info, true)
      }

      override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?,
      ) {
        if (destination == null) {
          callback?.onWriteFailed("Missing print destination")
          return
        }
        try {
          FileInputStream(cachedFile).use { input ->
            FileOutputStream(destination.fileDescriptor).use { output ->
              input.copyTo(output)
            }
          }
          if (cancellationSignal?.isCanceled == true) {
            callback?.onWriteCancelled()
          } else {
            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
          }
        } catch (e: Exception) {
          callback?.onWriteFailed(e.message ?: "Failed to print PDF")
        }
      }
    }
    printManager.print(jobName, adapter, PrintAttributes.Builder().build())
  }

  private fun decodeLogoBitmap(context: Context): Bitmap? {
    return runCatching {
      BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_art)
    }.getOrNull()
  }

  private class PdfPageWriter(
    private val doc: PdfDocument,
    private val logo: Bitmap?,
    private val request: SciencePdfReportRequest,
  ) {
    private val generatedDateTime = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US).format(Date())
    private val shortDateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("MM/dd hh:mm a", Locale.US)

    private var pageNumber = 0
    private var currentPage: PdfDocument.Page? = null
    private var canvas: Canvas = Canvas()
    private var y = MARGIN_TOP

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 0.8f
      color = COLOR_BORDER
    }

    private val titlePaint = textPaint(14.5f, COLOR_NAVY, bold = true)
    private val subtitlePaint = textPaint(8.8f, COLOR_SLATE)
    private val brandAccentPaint = textPaint(9.2f, COLOR_SKY_DARK, bold = true)
    private val sectionPaint = textPaint(10.8f, COLOR_NAVY, bold = true)
    private val headerCellPaint = textPaint(8f, Color.WHITE, bold = true)
    private val bodyPaint = textPaint(8.2f, COLOR_NAVY)
    private val bodyBoldPaint = textPaint(8.2f, COLOR_NAVY, bold = true)
    private val smallMutedPaint = textPaint(7.4f, COLOR_MUTED)

    fun buildReport() {
      startNewPage(firstPage = true)
      if (request.scope == PdfReportScope.ALL_LEARNERS) {
        drawAllLearnersReport()
      } else {
        drawSingleLearnerReport()
      }
      finishCurrentPage()
    }

    private fun startNewPage(firstPage: Boolean) {
      finishCurrentPage()
      pageNumber += 1
      val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
      currentPage = doc.startPage(info)
      canvas = currentPage!!.canvas
      y = MARGIN_TOP
      if (firstPage) {
        drawFullBanner()
      } else {
        drawContinuationHeader()
      }
    }

    private fun finishCurrentPage() {
      val page = currentPage ?: return
      // Footer divider & text
      fillPaint.color = COLOR_BORDER
      canvas.drawLine(MARGIN_X, FOOTER_Y - 12f, MARGIN_X + CONTENT_WIDTH, FOOTER_Y - 12f, strokePaint)
      canvas.drawText(
        "SCIENTISTS of Bagacay ES • Innovated by FRANZ D. PADOJINOG",
        MARGIN_X,
        FOOTER_Y,
        smallMutedPaint,
      )
      val pageLabel = "Page $pageNumber"
      val pw = smallMutedPaint.measureText(pageLabel)
      canvas.drawText(pageLabel, MARGIN_X + CONTENT_WIDTH - pw, FOOTER_Y, smallMutedPaint)
      doc.finishPage(page)
      currentPage = null
    }

    private fun ensureSpace(neededHeight: Float, onNewPage: (() -> Unit)? = null) {
      if (y + neededHeight > CONTENT_BOTTOM) {
        startNewPage(firstPage = false)
        onNewPage?.invoke()
      }
    }

    private fun drawFullBanner() {
      val boxRect = RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + 76f)
      fillPaint.color = COLOR_SKY_BG
      canvas.drawRoundRect(boxRect, 8f, 8f, fillPaint)
      strokePaint.color = COLOR_SKY
      canvas.drawRoundRect(boxRect, 8f, 8f, strokePaint)
      strokePaint.color = COLOR_BORDER

      if (logo != null) {
        val dest = Rect(
          (MARGIN_X + 10f).toInt(),
          (y + 12f).toInt(),
          (MARGIN_X + 62f).toInt(),
          (y + 64f).toInt(),
        )
        canvas.drawBitmap(logo, null, dest, null)
      }

      val textLeft = MARGIN_X + 74f
      canvas.drawText("SCIENTISTS of Bagacay ES", textLeft, y + 23f, titlePaint)
      canvas.drawText(
        "ARAL Science Activity Progress Report • Bagacay Elementary School",
        textLeft,
        y + 38f,
        brandAccentPaint,
      )
      canvas.drawText(
        "Innovated by: FRANZ D. PADOJINOG • District of Culasi",
        textLeft,
        y + 52f,
        subtitlePaint,
      )
      canvas.drawText(
        "Generated: $generatedDateTime",
        textLeft,
        y + 65f,
        smallMutedPaint,
      )

      // Gold tag badge on top right
      val pillText = "ARAL REPORT"
      val pillWidth = 86f
      val pillRect = RectF(MARGIN_X + CONTENT_WIDTH - pillWidth - 10f, y + 10f, MARGIN_X + CONTENT_WIDTH - 10f, y + 28f)
      fillPaint.color = COLOR_GOLD
      canvas.drawRoundRect(pillRect, 6f, 6f, fillPaint)
      val badgeTextPaint = textPaint(7.8f, COLOR_NAVY, bold = true)
      val tw = badgeTextPaint.measureText(pillText)
      canvas.drawText(pillText, pillRect.centerX() - tw / 2f, pillRect.top + 12.5f, badgeTextPaint)

      y += 84f
      // Accent bar
      fillPaint.color = COLOR_SKY
      canvas.drawRect(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH * 0.72f, y + 2.5f, fillPaint)
      fillPaint.color = COLOR_GOLD
      canvas.drawRect(MARGIN_X + CONTENT_WIDTH * 0.72f, y, MARGIN_X + CONTENT_WIDTH, y + 2.5f, fillPaint)
      y += 12f
    }

    private fun drawContinuationHeader() {
      val headerTitle = if (request.scope == PdfReportScope.ALL_LEARNERS) {
        "SCIENTISTS of Bagacay ES — All Learners ARAL Science Report"
      } else {
        "SCIENTISTS of Bagacay ES — ${request.activeLearner.learnerName} (Grade ${request.activeLearner.selectedGrade})"
      }
      canvas.drawText(headerTitle, MARGIN_X, y + 10f, brandAccentPaint)
      val gw = smallMutedPaint.measureText(generatedDateTime)
      canvas.drawText(generatedDateTime, MARGIN_X + CONTENT_WIDTH - gw, y + 10f, smallMutedPaint)
      y += 16f
      canvas.drawLine(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y, strokePaint)
      y += 10f
    }

    private fun drawSectionHeader(title: String, badgeText: String? = null) {
      ensureSpace(32f)
      fillPaint.color = COLOR_GRAY_BG
      val rect = RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + 22f)
      canvas.drawRoundRect(rect, 4f, 4f, fillPaint)
      fillPaint.color = COLOR_SKY
      canvas.drawRect(MARGIN_X, y, MARGIN_X + 4f, y + 22f, fillPaint)
      canvas.drawText(title, MARGIN_X + 10f, y + 14.5f, sectionPaint)
      if (!badgeText.isNullOrBlank()) {
        val bw = smallMutedPaint.measureText(badgeText)
        canvas.drawText(badgeText, MARGIN_X + CONTENT_WIDTH - bw - 8f, y + 14f, smallMutedPaint)
      }
      y += 28f
    }

    private fun drawMasteryLegend() {
      ensureSpace(26f)
      val items = listOf(
        Triple("★★★ Mastered (85–100%)", COLOR_GREEN, COLOR_GREEN_BG),
        Triple("★★☆ Developing (65–84%)", COLOR_SKY, COLOR_SKY_BG),
        Triple("★☆☆ Emerging (40–64%)", COLOR_AMBER, COLOR_AMBER_BG),
        Triple("Needs Help (<40%)", COLOR_CORAL, COLOR_CORAL_BG),
      )
      val chipW = (CONTENT_WIDTH - 18f) / 4f
      items.forEachIndexed { index, (label, fg, bg) ->
        val x = MARGIN_X + index * (chipW + 6f)
        val rect = RectF(x, y, x + chipW, y + 18f)
        fillPaint.color = bg
        canvas.drawRoundRect(rect, 4f, 4f, fillPaint)
        val p = textPaint(7f, fg, bold = true)
        val tw = p.measureText(label)
        canvas.drawText(label, rect.centerX() - tw / 2f, y + 12f, p)
      }
      y += 26f
    }

    private fun drawSingleLearnerReport() {
      val stats = request.activeLearner
      val lessons = request.allLessons
      val targetLessons = when (request.scope) {
        PdfReportScope.SELECTED_GRADE -> lessons.filter { it.grade == stats.selectedGrade }
        else -> lessons
      }
      val totalCompleted = lessons.count { (request.progressMap[it.id]?.stars ?: 0) >= 1 }
      val totalStars = request.progressMap.values.sumOf { it.stars }
      val maxStars = lessons.size * 3
      val accuracy = if (stats.totalAnswered > 0) {
        ((stats.totalCorrect * 100f) / stats.totalAnswered).toInt()
      } else 0

      drawLearnerSummaryGrid(
        stats = stats,
        scopeLabel = if (request.scope == PdfReportScope.SELECTED_GRADE) {
          "Grade ${stats.selectedGrade} Activities"
        } else {
          "All Grades (G3–G6)"
        },
        completedCount = totalCompleted,
        totalLessons = lessons.size,
        totalStars = totalStars,
        maxStars = maxStars,
        accuracy = accuracy,
        pendingMistakes = request.mistakes.size,
      )

      drawMasteryLegend()

      // Grade-Level Overview Table
      drawSectionHeader("1. Progress Overview by Grade Level", "${lessons.size} Total Activities")
      drawGradeSummaryTable(lessons, request.progressMap)

      // Activity-by-Activity Table grouped by Grade & Term
      val scopeHeading = if (request.scope == PdfReportScope.SELECTED_GRADE) {
        "2. Grade ${stats.selectedGrade} Progress by Activities"
      } else {
        "2. All Grades Progress by Activities"
      }
      val inProgressCount = targetLessons.count { request.inProgressMap.containsKey(it.id) }
      val activitiesBadge = if (inProgressCount > 0) {
        "${targetLessons.size} Activities • $inProgressCount In Progress"
      } else {
        "${targetLessons.size} Curriculum Activities"
      }
      drawSectionHeader(scopeHeading, activitiesBadge)
      drawActivitiesProgressTables(targetLessons, request.progressMap, request.inProgressMap)

      // Recent Activity & Challenge Sessions Log
      val filteredLogs = if (request.scope == PdfReportScope.SELECTED_GRADE) {
        request.activityLogs.filter { it.gradeLevel == stats.selectedGrade }
      } else {
        request.activityLogs
      }
      drawSectionHeader("3. Completed Activity & Challenge Session Log", "${filteredLogs.size} Recorded Sessions")
      drawActivityLogTable(filteredLogs)

      // Topics with Difficulties
      drawSectionHeader(
        "4. Topics with Difficulties & Intervention Needed",
        "${request.mistakes.size} Missed Item(s) after 2 Tries",
      )
      drawInterventionSection(lessons, request.progressMap, request.inProgressMap, request.mistakes)

      // Badges Summary
      drawSectionHeader(
        "5. Science Mastery Badges",
        "${request.badges.count { it.unlocked }} / ${request.badges.size} Unlocked",
      )
      drawBadgesSection(request.badges)

      drawSignatureBlock()
    }

    private fun drawLearnerSummaryGrid(
      stats: LearnerStatsEntity,
      scopeLabel: String,
      completedCount: Int,
      totalLessons: Int,
      totalStars: Int,
      maxStars: Int,
      accuracy: Int,
      pendingMistakes: Int,
    ) {
      ensureSpace(76f)
      val colW = CONTENT_WIDTH / 4f
      val rowH = 34f
      val rows = listOf(
        listOf(
          "LEARNER NAME" to stats.learnerName,
          "GRADE LEVEL" to "Grade ${stats.selectedGrade}",
          "SCIENCE XP" to "${stats.totalXp} XP",
          "DAILY STREAK" to "${stats.currentStreak} day(s)",
        ),
        listOf(
          "ACTIVITIES DONE" to "$completedCount / $totalLessons",
          "MASTERY STARS" to "$totalStars / $maxStars ★",
          "OVERALL ACCURACY" to "$accuracy% (${stats.totalCorrect}/${stats.totalAnswered})",
          "FOR INTERVENTION" to "$pendingMistakes missed | $scopeLabel",
        ),
      )

      rows.forEachIndexed { rIdx, cells ->
        val top = y + rIdx * rowH
        cells.forEachIndexed { cIdx, (label, value) ->
          val left = MARGIN_X + cIdx * colW
          val rect = RectF(left, top, left + colW, top + rowH)
          fillPaint.color = if (rIdx == 0) Color.WHITE else COLOR_ROW_ALT
          canvas.drawRect(rect, fillPaint)
          canvas.drawRect(rect, strokePaint)
          canvas.drawText(label, left + 6f, top + 11f, smallMutedPaint)
          val valText = ellipsize(value, bodyBoldPaint, colW - 12f)
          canvas.drawText(valText, left + 6f, top + 25f, bodyBoldPaint)
        }
      }
      y += rows.size * rowH + 10f
    }

    private fun drawGradeSummaryTable(
      lessons: List<ScienceLesson>,
      progressMap: Map<String, LessonProgressEntity>,
    ) {
      val widths = floatArrayOf(88f, 76f, 76f, 82f, 82f, 64f, 72f)
      val headers = listOf("Grade Level", "Activities", "Completed", "Mastered (3★)", "Stars Earned", "Progress", "Standing")
      drawTableHeader(headers, widths)

      listOf(3, 4, 5, 6).forEachIndexed { idx, grade ->
        val gLessons = lessons.filter { it.grade == grade }
        val gDone = gLessons.count { (progressMap[it.id]?.stars ?: 0) >= 1 }
        val gMastered = gLessons.count { (progressMap[it.id]?.stars ?: 0) == 3 }
        val gStars = gLessons.sumOf { progressMap[it.id]?.stars ?: 0 }
        val gMax = (gLessons.size * 3).coerceAtLeast(1)
        val pct = (gStars * 100) / gMax
        val standing = when {
          gDone == 0 -> "Not Started"
          pct >= 80 -> "Proficient"
          pct >= 50 -> "Developing"
          else -> "Emerging"
        }
        val rowHeight = 20f
        ensureSpace(rowHeight) { drawTableHeader(headers, widths) }
        drawSimpleRow(
          cells = listOf(
            "Grade $grade Science",
            "${gLessons.size}",
            "$gDone / ${gLessons.size}",
            "$gMastered",
            "$gStars / $gMax",
            "$pct%",
            standing,
          ),
          widths = widths,
          rowHeight = rowHeight,
          isAlt = idx % 2 == 1,
          lastColColor = standingColor(standing),
        )
      }
      y += 12f
    }

    private fun drawActivitiesProgressTables(
      lessons: List<ScienceLesson>,
      progressMap: Map<String, LessonProgressEntity>,
      inProgressMap: Map<String, LessonInProgressEntity>,
    ) {
      val widths = floatArrayOf(26f, 62f, 220f, 36f, 68f, 44f, 84f)
      val headers = listOf("No.", "Weeks", "Activity / Lesson & Topic", "Tries", "Score / Status", "Stars", "Mastery Level")

      val grouped = lessons.groupBy { it.grade to it.term }
        .entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second }))

      grouped.forEach { (gradeTerm, termLessons) ->
        val (grade, term) = gradeTerm
        val doneInTerm = termLessons.count { (progressMap[it.id]?.stars ?: 0) >= 1 }
        val inProgInTerm = termLessons.count { inProgressMap.containsKey(it.id) }
        ensureSpace(44f)
        fillPaint.color = COLOR_SKY_BG
        val subRect = RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + 18f)
        canvas.drawRect(subRect, fillPaint)
        canvas.drawRect(subRect, strokePaint)
        val termSummary = if (inProgInTerm > 0) {
          "Grade $grade • Term $term ($doneInTerm/${termLessons.size} done, $inProgInTerm in progress)"
        } else {
          "Grade $grade • Term $term ($doneInTerm / ${termLessons.size} completed)"
        }
        canvas.drawText(termSummary, MARGIN_X + 6f, y + 12.5f, brandAccentPaint)
        y += 18f
        drawTableHeader(headers, widths)

        termLessons.sortedBy { it.order }.forEachIndexed { idx, lesson ->
          val prog = progressMap[lesson.id]
          val inProg = inProgressMap[lesson.id]
          val stars = prog?.stars ?: 0
          val attempts = prog?.attempts ?: if (inProg != null) 1 else 0
          val bestScoreText = when {
            inProg != null -> "${inProg.correctCount}/${inProg.answeredCount} (Item ${inProg.currentIndex + 1}/${lesson.questions.size})"
            prog != null && prog.totalQuestions > 0 -> {
              val pct = (prog.bestScore * 100) / prog.totalQuestions
              "${prog.bestScore}/${prog.totalQuestions} ($pct%)"
            }
            else -> "— / ${lesson.questions.size}"
          }
          val starsText = buildString {
            repeat(stars) { append("★") }
            repeat((3 - stars).coerceAtLeast(0)) { append("☆") }
          }
          val statusLabel = when {
            inProg != null -> "In Progress"
            attempts == 0 -> "Not Started"
            stars == 3 -> "Mastered"
            stars == 2 -> "Developing"
            stars == 1 -> "Emerging"
            else -> "Needs Help"
          }
          val titleDisplay = "${lesson.title} — ${lesson.contentTopic}"
          val wrappedTitle = buildStaticLayout(titleDisplay, bodyPaint, (widths[2] - 8f).toInt(), maxLines = 2)
          val rowHeight = (wrappedTitle.height + 8f).coerceAtLeast(22f)

          ensureSpace(rowHeight) { drawTableHeader(headers, widths) }

          val top = y
          var x = MARGIN_X
          val bgColor = if (idx % 2 == 1) COLOR_ROW_ALT else Color.WHITE

          widths.forEachIndexed { cIdx, w ->
            val cellRect = RectF(x, top, x + w, top + rowHeight)
            fillPaint.color = bgColor
            canvas.drawRect(cellRect, fillPaint)
            canvas.drawRect(cellRect, strokePaint)

            when (cIdx) {
              0 -> canvas.drawText("${idx + 1}", x + 6f, top + 14f, bodyPaint)
              1 -> canvas.drawText(ellipsize(lesson.weeks, bodyPaint, w - 8f), x + 4f, top + 14f, bodyPaint)
              2 -> {
                canvas.save()
                canvas.translate(x + 4f, top + 4f)
                wrappedTitle.draw(canvas)
                canvas.restore()
              }
              3 -> canvas.drawText("$attempts", x + 10f, top + 14f, bodyPaint)
              4 -> canvas.drawText(ellipsize(bestScoreText, bodyPaint, w - 6f), x + 4f, top + 14f, bodyPaint)
              5 -> {
                val starPaint = textPaint(8.5f, if (stars > 0) COLOR_AMBER else COLOR_MUTED, bold = true)
                canvas.drawText(starsText, x + 5f, top + 14f, starPaint)
              }
              6 -> drawStatusPill(x + 4f, top + (rowHeight - 14f) / 2f, w - 8f, 14f, statusLabel)
            }
            x += w
          }
          y += rowHeight
        }
        y += 8f
      }
      y += 4f
    }

    private fun drawActivityLogTable(logs: List<ActivityLogEntity>) {
      if (logs.isEmpty()) {
        ensureSpace(28f)
        canvas.drawText(
          "No completed activity sessions recorded yet. Complete a lesson or challenge to log results here.",
          MARGIN_X + 4f,
          y + 12f,
          subtitlePaint,
        )
        y += 24f
        return
      }

      val displayLogs = logs.take(25)
      val widths = floatArrayOf(88f, 198f, 92f, 42f, 64f, 56f)
      val headers = listOf("Date & Time", "Activity Completed", "Category", "Grade", "Score", "XP / ★")
      drawTableHeader(headers, widths)

      displayLogs.forEachIndexed { idx, log ->
        val pct = if (log.totalCount > 0) (log.correctCount * 100) / log.totalCount else 0
        val scoreStr = "${log.correctCount}/${log.totalCount} ($pct%)"
        val xpStar = "+${log.xpEarned} XP"
        val rowHeight = 20f
        ensureSpace(rowHeight) { drawTableHeader(headers, widths) }
        drawSimpleRow(
          cells = listOf(
            dateTimeFormat.format(Date(log.completedAt)),
            log.activityTitle,
            log.activityCategory,
            "G${log.gradeLevel}",
            scoreStr,
            xpStar,
          ),
          widths = widths,
          rowHeight = rowHeight,
          isAlt = idx % 2 == 1,
          lastColColor = COLOR_SKY_DARK,
        )
      }
      y += 12f
    }

    private fun drawInterventionSection(
      lessons: List<ScienceLesson>,
      progressMap: Map<String, LessonProgressEntity>,
      inProgressMap: Map<String, LessonInProgressEntity>,
      mistakes: List<MistakeEntity>,
    ) {
      val lessonLookup = lessons.associateBy { it.id }
      val questionLookup = lessons.flatMap { it.questions }.associateBy { it.id }
      val mistakesByLesson = mistakes.groupBy { it.lessonId }

      val difficultyLessonIds = linkedSetOf<String>()
      mistakesByLesson.entries.sortedByDescending { it.value.size }.forEach { difficultyLessonIds.add(it.key) }
      progressMap.values.filter { it.attempts > 0 && it.stars <= 1 }.forEach { difficultyLessonIds.add(it.lessonId) }
      inProgressMap.values.filter { it.answeredCount > 0 && (it.correctCount * 100 / it.answeredCount) < 65 }
        .forEach { difficultyLessonIds.add(it.lessonId) }

      if (difficultyLessonIds.isEmpty()) {
        ensureSpace(26f)
        val okPaint = textPaint(8.6f, COLOR_GREEN, bold = true)
        canvas.drawText(
          "✓ All attempted topics are on track! No missed items after 2nd tries at this time.",
          MARGIN_X + 4f,
          y + 12f,
          okPaint,
        )
        y += 22f
        return
      }

      val widths = floatArrayOf(66f, 168f, 224f, 82f)
      val headers = listOf("Grade / Term", "Topic with Difficulty", "Missed Concepts (Failed 2nd Try)", "Difficulty Level")
      drawTableHeader(headers, widths)

      difficultyLessonIds.forEachIndexed { idx, lessonId ->
        val lesson = lessonLookup[lessonId]
        val gradeTerm = if (lesson != null) "G${lesson.grade} • T${lesson.term}" else "General"
        val topicTitle = if (lesson != null) "${lesson.contentTopic} — ${lesson.title}" else lessonId
        val lessonMistakes = mistakesByLesson[lessonId].orEmpty()
        val prog = progressMap[lessonId]
        val inProg = inProgressMap[lessonId]

        val detailText = buildString {
          if (lessonMistakes.isNotEmpty()) {
            append("${lessonMistakes.size} missed item(s): ")
            append(
              lessonMistakes.take(2).mapNotNull { m ->
                questionLookup[m.questionId]?.prompt?.take(62)
              }.joinToString(" | ")
            )
            if (lessonMistakes.size > 2) append(" (+${lessonMistakes.size - 2} more)")
          } else if (prog != null && prog.totalQuestions > 0) {
            val pct = (prog.bestScore * 100) / prog.totalQuestions
            append("Low mastery score (${prog.bestScore}/${prog.totalQuestions}, $pct%)")
          } else if (inProg != null && inProg.answeredCount > 0) {
            append("Unfinished score (${inProg.correctCount}/${inProg.answeredCount} answered)")
          }
        }.ifBlank { "Needs teacher-guided review" }

        val levelLabel = when {
          lessonMistakes.size >= 3 || (prog != null && prog.stars == 0 && prog.attempts > 0) -> "Needs Help"
          else -> "Needs Review"
        }

        val wrappedTopic = buildStaticLayout(topicTitle, bodyBoldPaint, (widths[1] - 8f).toInt(), maxLines = 2)
        val wrappedDetail = buildStaticLayout(detailText, bodyPaint, (widths[2] - 8f).toInt(), maxLines = 3)
        val rowHeight = maxOf(wrappedTopic.height, wrappedDetail.height).plus(8f).coerceAtLeast(24f)

        ensureSpace(rowHeight) { drawTableHeader(headers, widths) }
        val top = y
        var x = MARGIN_X
        val bgColor = if (idx % 2 == 1) COLOR_ROW_ALT else Color.WHITE

        widths.forEachIndexed { cIdx, w ->
          val rect = RectF(x, top, x + w, top + rowHeight)
          fillPaint.color = bgColor
          canvas.drawRect(rect, fillPaint)
          canvas.drawRect(rect, strokePaint)
          when (cIdx) {
            0 -> canvas.drawText(gradeTerm, x + 5f, top + 14f, bodyPaint)
            1 -> {
              canvas.save()
              canvas.translate(x + 4f, top + 4f)
              wrappedTopic.draw(canvas)
              canvas.restore()
            }
            2 -> {
              canvas.save()
              canvas.translate(x + 4f, top + 4f)
              wrappedDetail.draw(canvas)
              canvas.restore()
            }
            3 -> {
              val p = textPaint(7.8f, COLOR_CORAL, bold = true)
              canvas.drawText(levelLabel, x + 6f, top + 14f, p)
            }
          }
          x += w
        }
        y += rowHeight
      }
      y += 12f
    }

    private fun drawBadgesSection(badges: List<ScienceBadge>) {
      val colW = (CONTENT_WIDTH - 8f) / 2f
      val rows = badges.chunked(2)
      rows.forEach { pair ->
        ensureSpace(34f)
        pair.forEachIndexed { colIdx, badge ->
          val left = MARGIN_X + colIdx * (colW + 8f)
          val rect = RectF(left, y, left + colW, y + 28f)
          fillPaint.color = if (badge.unlocked) COLOR_AMBER_BG else COLOR_ROW_ALT
          canvas.drawRoundRect(rect, 4f, 4f, fillPaint)
          canvas.drawRoundRect(rect, 4f, 4f, strokePaint)
          val status = if (badge.unlocked) "UNLOCKED" else badge.progressText
          val statusPaint = textPaint(7.2f, if (badge.unlocked) COLOR_GREEN else COLOR_MUTED, bold = true)
          canvas.drawText(ellipsize(badge.title, bodyBoldPaint, colW - 85f), left + 8f, y + 12f, bodyBoldPaint)
          canvas.drawText(ellipsize(badge.description, smallMutedPaint, colW - 14f), left + 8f, y + 22.5f, smallMutedPaint)
          val sw = statusPaint.measureText(status)
          canvas.drawText(status, left + colW - sw - 8f, y + 12f, statusPaint)
        }
        y += 33f
      }
      y += 6f
    }

    private fun drawAllLearnersReport() {
      val snapshots = request.allLearnerSnapshots
      val lessons = request.allLessons
      val totalLearners = snapshots.size
      val combinedXp = snapshots.sumOf { it.stats.totalXp }
      val totalSessions = snapshots.sumOf { it.activityLogs.size }
      val totalLessonsCompleted = snapshots.sumOf { snap ->
        snap.progressMap.values.count { it.stars >= 1 }
      }

      // Class / Device Summary Grid
      ensureSpace(48f)
      val colW = CONTENT_WIDTH / 4f
      val rowH = 34f
      val summaryCells = listOf(
        "TOTAL LEARNERS" to "$totalLearners Learner(s)",
        "CURRICULUM ACTIVITIES" to "${lessons.size} Lessons (G3–G6)",
        "COMBINED COMPLETIONS" to "$totalLessonsCompleted Lessons | $totalSessions Logs",
        "COMBINED SCIENCE XP" to "$combinedXp XP",
      )
      summaryCells.forEachIndexed { idx, (label, value) ->
        val left = MARGIN_X + idx * colW
        val rect = RectF(left, y, left + colW, y + rowH)
        fillPaint.color = Color.WHITE
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, strokePaint)
        canvas.drawText(label, left + 6f, y + 11f, smallMutedPaint)
        canvas.drawText(ellipsize(value, bodyBoldPaint, colW - 12f), left + 6f, y + 25f, bodyBoldPaint)
      }
      y += rowH + 10f

      drawMasteryLegend()

      // Consolidated Learner Table
      drawSectionHeader("1. Consolidated Learners Summary", "$totalLearners Profiles")
      val widths = floatArrayOf(128f, 44f, 54f, 48f, 68f, 62f, 56f, 80f)
      val headers = listOf("Learner Name", "Grade", "XP", "Streak", "Lessons Done", "Stars", "Accuracy", "Standing")
      drawTableHeader(headers, widths)

      val maxStars = lessons.size * 3
      snapshots.forEachIndexed { idx, snap ->
        val done = snap.progressMap.values.count { it.stars >= 1 }
        val stars = snap.progressMap.values.sumOf { it.stars }
        val acc = if (snap.stats.totalAnswered > 0) {
          (snap.stats.totalCorrect * 100) / snap.stats.totalAnswered
        } else 0
        val standing = when {
          done == 0 && snap.stats.totalAnswered == 0 -> "Not Started"
          acc >= 85 -> "Mastered"
          acc >= 65 -> "Developing"
          acc >= 40 -> "Emerging"
          else -> "Needs Help"
        }
        val rowHeight = 20f
        ensureSpace(rowHeight) { drawTableHeader(headers, widths) }
        drawSimpleRow(
          cells = listOf(
            snap.stats.learnerName,
            "G${snap.stats.selectedGrade}",
            "${snap.stats.totalXp}",
            "${snap.stats.currentStreak}d",
            "$done / ${lessons.size}",
            "$stars / $maxStars",
            "$acc%",
            standing,
          ),
          widths = widths,
          rowHeight = rowHeight,
          isAlt = idx % 2 == 1,
          lastColColor = standingColor(standing),
        )
      }
      y += 14f

      // Per-Learner Breakdown of Attempted Activities
      drawSectionHeader("2. Per-Learner Activity Progress Details", "Individual Breakdown")
      val lessonMap = lessons.associateBy { it.id }
      snapshots.forEach { snap ->
        ensureSpace(42f)
        fillPaint.color = COLOR_SKY_BG
        val barRect = RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + 20f)
        canvas.drawRect(barRect, fillPaint)
        canvas.drawRect(barRect, strokePaint)
        val done = snap.progressMap.values.count { it.stars >= 1 }
        val stars = snap.progressMap.values.sumOf { it.stars }
        canvas.drawText(
          "${snap.stats.learnerName} (Grade ${snap.stats.selectedGrade}) • $done Completed • $stars★ • ${snap.stats.totalXp} XP • ${snap.mistakes.size} Missed Items",
          MARGIN_X + 6f,
          y + 13f,
          brandAccentPaint,
        )
        y += 20f

        val completedEntries = snap.progressMap.values
          .sortedWith(compareBy({ lessonMap[it.lessonId]?.grade ?: 99 }, { lessonMap[it.lessonId]?.order ?: 999 }))
        val inProgEntries = snap.inProgressMap.values
          .filter { !snap.progressMap.containsKey(it.lessonId) }
          .sortedWith(compareBy({ lessonMap[it.lessonId]?.grade ?: 99 }, { lessonMap[it.lessonId]?.order ?: 999 }))

        if (completedEntries.isEmpty() && inProgEntries.isEmpty() && snap.activityLogs.isEmpty()) {
          ensureSpace(22f)
          canvas.drawText("No completed or in-progress activities recorded yet for this learner.", MARGIN_X + 8f, y + 14f, smallMutedPaint)
          y += 22f
        } else {
          val aWidths = floatArrayOf(58f, 214f, 40f, 76f, 44f, 108f)
          val aHeaders = listOf("Grade/Term", "Activity / Lesson", "Tries", "Score", "Stars", "Status / Date")
          drawTableHeader(aHeaders, aWidths)
          var rowCursor = 0
          completedEntries.forEach { prog ->
            val lesson = lessonMap[prog.lessonId]
            val gt = if (lesson != null) "G${lesson.grade} • T${lesson.term}" else "Lesson"
            val title = lesson?.title ?: prog.lessonId
            val pct = if (prog.totalQuestions > 0) (prog.bestScore * 100) / prog.totalQuestions else 0
            val scoreStr = "${prog.bestScore}/${prog.totalQuestions} ($pct%)"
            val starsStr = "★".repeat(prog.stars) + "☆".repeat((3 - prog.stars).coerceAtLeast(0))
            val inProg = snap.inProgressMap[prog.lessonId]
            val statusStr = if (inProg != null) {
              "In Progress (Item ${inProg.currentIndex + 1})"
            } else {
              when (prog.stars) {
                3 -> "Mastered"
                2 -> "Developing"
                1 -> "Emerging"
                else -> "Needs Help"
              } + " (${shortDateFormat.format(Date(prog.completedAt))})"
            }
            val rowHeight = 20f
            ensureSpace(rowHeight) { drawTableHeader(aHeaders, aWidths) }
            drawSimpleRow(
              cells = listOf(gt, title, "${prog.attempts}", scoreStr, starsStr, statusStr),
              widths = aWidths,
              rowHeight = rowHeight,
              isAlt = rowCursor % 2 == 1,
              lastColColor = standingColor(statusStr),
            )
            rowCursor++
          }
          inProgEntries.forEach { inProg ->
            val lesson = lessonMap[inProg.lessonId]
            val gt = if (lesson != null) "G${lesson.grade} • T${lesson.term}" else "Lesson"
            val title = lesson?.title ?: inProg.lessonId
            val totalQ = lesson?.questions?.size ?: inProg.answeredCount
            val scoreStr = "${inProg.correctCount}/${inProg.answeredCount} (${inProg.answeredCount}/$totalQ)"
            val statusStr = "In Progress (Item ${inProg.currentIndex + 1})"
            val rowHeight = 20f
            ensureSpace(rowHeight) { drawTableHeader(aHeaders, aWidths) }
            drawSimpleRow(
              cells = listOf(gt, title, "1", scoreStr, "☆☆☆", statusStr),
              widths = aWidths,
              rowHeight = rowHeight,
              isAlt = rowCursor % 2 == 1,
              lastColColor = COLOR_AMBER,
            )
            rowCursor++
          }
          y += 8f
        }
      }

      drawSignatureBlock()
    }

    private fun drawSignatureBlock() {
      ensureSpace(88f)
      y += 6f
      val boxRect = RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + 32f)
      fillPaint.color = COLOR_ROW_ALT
      canvas.drawRoundRect(boxRect, 4f, 4f, fillPaint)
      canvas.drawRoundRect(boxRect, 4f, 4f, strokePaint)
      canvas.drawText("Teacher / Tutor Remarks:", MARGIN_X + 8f, y + 12f, bodyBoldPaint)
      y += 44f

      val halfW = (CONTENT_WIDTH - 40f) / 2f
      // Left signature
      canvas.drawLine(MARGIN_X, y + 18f, MARGIN_X + halfW, y + 18f, strokePaint)
      canvas.drawText("Prepared / Checked by (Teacher / Coordinator)", MARGIN_X, y + 29f, smallMutedPaint)
      canvas.drawText("SCIENTISTS of Bagacay ES • FRANZ D. PADOJINOG", MARGIN_X, y + 39f, smallMutedPaint)

      // Right signature
      val rightX = MARGIN_X + halfW + 40f
      canvas.drawLine(rightX, y + 18f, rightX + halfW, y + 18f, strokePaint)
      canvas.drawText("Noted by (Parent / Guardian) & Date", rightX, y + 29f, smallMutedPaint)
      y += 46f
    }

    private fun drawTableHeader(headers: List<String>, widths: FloatArray) {
      ensureSpace(20f)
      var x = MARGIN_X
      headers.forEachIndexed { idx, h ->
        val w = widths[idx]
        val rect = RectF(x, y, x + w, y + 19f)
        fillPaint.color = COLOR_SKY_DARK
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, strokePaint)
        canvas.drawText(ellipsize(h, headerCellPaint, w - 6f), x + 4f, y + 13f, headerCellPaint)
        x += w
      }
      y += 19f
    }

    private fun drawSimpleRow(
      cells: List<String>,
      widths: FloatArray,
      rowHeight: Float,
      isAlt: Boolean,
      lastColColor: Int? = null,
    ) {
      var x = MARGIN_X
      cells.forEachIndexed { idx, text ->
        val w = widths[idx]
        val rect = RectF(x, y, x + w, y + rowHeight)
        fillPaint.color = if (isAlt) COLOR_ROW_ALT else Color.WHITE
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, strokePaint)
        val p = if (idx == cells.lastIndex && lastColColor != null) {
          textPaint(8f, lastColColor, bold = true)
        } else {
          bodyPaint
        }
        canvas.drawText(ellipsize(text, p, w - 8f), x + 4f, y + 13.5f, p)
        x += w
      }
      y += rowHeight
    }

    private fun drawStatusPill(x: Float, top: Float, width: Float, height: Float, label: String) {
      val (fg, bg) = when (label) {
        "Mastered", "Proficient" -> COLOR_GREEN to COLOR_GREEN_BG
        "Developing" -> COLOR_SKY to COLOR_SKY_BG
        "In Progress" -> COLOR_AMBER to COLOR_AMBER_BG
        "Emerging" -> COLOR_AMBER to COLOR_AMBER_BG
        "Needs Help" -> COLOR_CORAL to COLOR_CORAL_BG
        else -> COLOR_MUTED to COLOR_GRAY_BG
      }
      val rect = RectF(x, top, x + width, top + height)
      fillPaint.color = bg
      canvas.drawRoundRect(rect, 3f, 3f, fillPaint)
      val p = textPaint(7f, fg, bold = true)
      val tw = p.measureText(label)
      canvas.drawText(label, rect.centerX() - tw / 2f, top + 10f, p)
    }

    private fun standingColor(label: String): Int = when {
      label.startsWith("Mastered") || label.startsWith("Proficient") -> COLOR_GREEN
      label.startsWith("Developing") -> COLOR_SKY
      label.startsWith("In Progress") || label.startsWith("Emerging") -> COLOR_AMBER
      label.startsWith("Needs") -> COLOR_CORAL
      else -> COLOR_MUTED
    }

    private fun textPaint(sizeSp: Float, color: Int, bold: Boolean = false): TextPaint {
      return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = sizeSp
        this.color = color
        this.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
      }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
      if (paint.measureText(text) <= maxWidth) return text
      val ellipsis = "…"
      var end = text.length
      while (end > 0 && paint.measureText(text, 0, end) + paint.measureText(ellipsis) > maxWidth) {
        end--
      }
      return if (end <= 0) ellipsis else text.substring(0, end) + ellipsis
    }

    private fun buildStaticLayout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout {
      return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(20))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, 1.05f)
        .setIncludePad(false)
        .setMaxLines(maxLines)
        .build()
    }
  }
}
