package com.example.data.repository

import com.example.data.local.ActiveLearnerEntity
import com.example.data.local.ActivityLogEntity
import com.example.data.local.CurriculumLoader
import com.example.data.local.LearnerStatsEntity
import com.example.data.local.LessonInProgressEntity
import com.example.data.local.LessonProgressEntity
import com.example.data.local.MistakeEntity
import com.example.data.local.ProgressDao
import com.example.data.model.ScienceBadge
import com.example.data.model.ScienceLesson
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class ScienceRepository(
  private val curriculumLoader: CurriculumLoader,
  private val progressDao: ProgressDao,
) {
  private val statsMutex = Mutex()
  suspend fun loadCurriculum(): List<ScienceLesson> = curriculumLoader.loadLessons()

  fun observeAllLearners(): Flow<List<LearnerStatsEntity>> = progressDao.observeAllLearners()

  fun observeStarsByLearner(): Flow<Map<Long, Int>> =
    progressDao.observeAllLearnersLessonProgress().map { rows ->
      rows.groupBy { it.learnerId }.mapValues { (_, list) -> list.sumOf { it.stars } }
    }

  fun observeLessonProgress(): Flow<Map<String, LessonProgressEntity>> =
    progressDao.observeActiveLearnerId().flatMapLatest { activeId ->
      if (activeId == null) {
        flowOf(emptyMap())
      } else {
        progressDao.observeAllLessonProgress(activeId).map { list -> list.associateBy { it.lessonId } }
      }
    }

  fun observeMistakes(): Flow<List<MistakeEntity>> =
    progressDao.observeActiveLearnerId().flatMapLatest { activeId ->
      if (activeId == null) {
        flowOf(emptyList())
      } else {
        progressDao.observeMistakes(activeId)
      }
    }

  fun observeActivityLogs(): Flow<List<ActivityLogEntity>> =
    progressDao.observeActiveLearnerId().flatMapLatest { activeId ->
      if (activeId == null) {
        flowOf(emptyList())
      } else {
        progressDao.observeActivityLogs(activeId)
      }
    }

  fun observeInProgressLessons(): Flow<Map<String, LessonInProgressEntity>> =
    progressDao.observeActiveLearnerId().flatMapLatest { activeId ->
      if (activeId == null) {
        flowOf(emptyMap())
      } else {
        progressDao.observeInProgressLessons(activeId).map { list -> list.associateBy { it.lessonId } }
      }
    }

  fun observeLearnerStats(): Flow<LearnerStatsEntity> =
    combine(progressDao.observeActiveLearnerId(), progressDao.observeAllLearners()) { activeId, learners ->
      learners.firstOrNull { it.id == activeId } ?: learners.firstOrNull() ?: LearnerStatsEntity(id = 1L)
    }

  suspend fun ensureInitialStats() {
    val learners = progressDao.getAllLearnersOnce()
    if (learners.isEmpty()) {
      val id = progressDao.insertLearner(
        LearnerStatsEntity(
          learnerName = "Young Scientist",
          selectedGrade = 5,
          avatarIndex = 0,
          createdAt = System.currentTimeMillis(),
        )
      )
      progressDao.setActiveLearner(ActiveLearnerEntity(activeLearnerId = id))
    } else {
      val activeId = progressDao.getActiveLearnerIdOnce()
      if (activeId == null || learners.none { it.id == activeId }) {
        progressDao.setActiveLearner(ActiveLearnerEntity(activeLearnerId = learners.first().id))
      }
    }
  }

  private suspend fun resolveActiveLearner(): LearnerStatsEntity {
    ensureInitialStats()
    val activeId = progressDao.getActiveLearnerIdOnce()
    if (activeId != null) {
      progressDao.getLearnerById(activeId)?.let { return it }
    }
    return progressDao.getAllLearnersOnce().first()
  }

  suspend fun addLearner(name: String, grade: Int, avatarIndex: Int, switchToNew: Boolean = true): Long {
    ensureInitialStats()
    val existing = progressDao.getAllLearnersOnce()
    val cleanName = name.trim().ifBlank { "Scientist ${existing.size + 1}" }
    val newId = progressDao.insertLearner(
      LearnerStatsEntity(
        learnerName = cleanName,
        selectedGrade = grade.coerceIn(3, 6),
        avatarIndex = avatarIndex.coerceIn(0, 5),
        createdAt = System.currentTimeMillis(),
      )
    )
    if (switchToNew) {
      progressDao.setActiveLearner(ActiveLearnerEntity(activeLearnerId = newId))
    }
    return newId
  }

  suspend fun switchLearner(learnerId: Long) {
    if (progressDao.getLearnerById(learnerId) != null) {
      progressDao.setActiveLearner(ActiveLearnerEntity(activeLearnerId = learnerId))
    }
  }

  suspend fun deleteLearner(learnerId: Long): Boolean {
    val learners = progressDao.getAllLearnersOnce()
    if (learners.size <= 1) return false
    val activeId = progressDao.getActiveLearnerIdOnce()
    progressDao.deleteLearnerById(learnerId)
    val remaining = progressDao.getAllLearnersOnce()
    if (activeId == learnerId && remaining.isNotEmpty()) {
      progressDao.setActiveLearner(ActiveLearnerEntity(activeLearnerId = remaining.first().id))
    }
    return true
  }

  suspend fun updateLearnerProfile(name: String, grade: Int, avatarIndex: Int? = null) {
    val cur = resolveActiveLearner()
    val cleanName = name.trim().ifBlank { "Young Scientist" }
    progressDao.updateLearnerStats(
      cur.copy(
        learnerName = cleanName,
        selectedGrade = grade.coerceIn(3, 6),
        avatarIndex = avatarIndex?.coerceIn(0, 5) ?: cur.avatarIndex,
      )
    )
  }

  suspend fun setSelectedGrade(grade: Int) {
    val cur = resolveActiveLearner()
    progressDao.updateLearnerStats(cur.copy(selectedGrade = grade.coerceIn(3, 6)))
  }

  suspend fun setTtsAutoRead(enabled: Boolean) {
    val cur = resolveActiveLearner()
    progressDao.updateLearnerStats(cur.copy(ttsAutoRead = enabled))
  }

  suspend fun recordQuestionResult(
    learnerId: Long,
    questionId: String,
    lessonId: String,
    isCorrect: Boolean,
  ) {
    val targetLearnerId = progressDao.getLearnerById(learnerId)?.id ?: resolveActiveLearner().id
    if (isCorrect) {
      progressDao.deleteMistake(targetLearnerId, questionId)
    } else {
      progressDao.insertMistake(
        MistakeEntity(
          learnerId = targetLearnerId,
          questionId = questionId,
          lessonId = lessonId,
        )
      )
    }
  }

  suspend fun getInProgressLesson(learnerId: Long, lessonId: String): LessonInProgressEntity? {
    val targetLearnerId = progressDao.getLearnerById(learnerId)?.id ?: resolveActiveLearner().id
    return progressDao.getInProgressLesson(targetLearnerId, lessonId)
  }

  suspend fun saveInProgressAndFlushStats(
    learnerId: Long,
    lessonId: String?,
    currentIndex: Int,
    answeredCount: Int,
    correctCount: Int,
    sessionXp: Int,
    streakInSession: Int,
    missedQuestionIds: List<String>,
    deltaCorrect: Int,
    deltaAnswered: Int,
    deltaXp: Int,
  ) = statsMutex.withLock {
    val cur = progressDao.getLearnerById(learnerId) ?: resolveActiveLearner()
    val targetLearnerId = cur.id
    val now = System.currentTimeMillis()
    if (deltaAnswered > 0 || deltaXp > 0) {
      val today = formatDate(now)
      val yesterday = formatDate(
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
      )
      val newStreak = when (cur.lastActiveDay) {
        today -> maxOf(1, cur.currentStreak)
        yesterday -> cur.currentStreak + 1
        else -> 1
      }
      progressDao.updateLearnerStats(
        cur.copy(
          totalXp = cur.totalXp + deltaXp.coerceAtLeast(0),
          currentStreak = newStreak,
          lastActiveDay = today,
          totalCorrect = cur.totalCorrect + deltaCorrect.coerceAtLeast(0),
          totalAnswered = cur.totalAnswered + deltaAnswered.coerceAtLeast(0),
        )
      )
    }

    if (lessonId != null && answeredCount > 0) {
      progressDao.upsertInProgressLesson(
        LessonInProgressEntity(
          learnerId = targetLearnerId,
          lessonId = lessonId,
          currentIndex = currentIndex,
          answeredCount = answeredCount,
          correctCount = correctCount,
          sessionXp = sessionXp,
          streakInSession = streakInSession,
          missedQuestionIds = missedQuestionIds.joinToString(","),
          updatedAt = now,
        )
      )
    }
  }

  suspend fun completeActivitySession(
    learnerId: Long,
    lessonId: String?,
    correctCount: Int,
    totalCount: Int,
    xpEarned: Int,
    activityTitle: String = "",
    activityCategory: String = "",
    gradeLevel: Int? = null,
    deltaCorrect: Int = correctCount,
    deltaAnswered: Int = totalCount,
    deltaXp: Int = xpEarned,
  ) = statsMutex.withLock {
    val cur = progressDao.getLearnerById(learnerId) ?: resolveActiveLearner()
    val targetLearnerId = cur.id
    val now = System.currentTimeMillis()
    val earnedStars = if (totalCount > 0) {
      val accuracy = (correctCount * 100f) / totalCount
      when {
        accuracy >= 85f -> 3
        accuracy >= 65f -> 2
        accuracy >= 40f -> 1
        else -> 0
      }
    } else 0

    if (lessonId != null && totalCount > 0) {
      val existing = progressDao.getLessonProgress(targetLearnerId, lessonId)
      progressDao.upsertLessonProgress(
        LessonProgressEntity(
          learnerId = targetLearnerId,
          lessonId = lessonId,
          stars = maxOf(existing?.stars ?: 0, earnedStars),
          bestScore = maxOf(existing?.bestScore ?: 0, correctCount),
          totalQuestions = totalCount,
          attempts = (existing?.attempts ?: 0) + 1,
          completedAt = now,
        )
      )
      progressDao.deleteInProgressLesson(targetLearnerId, lessonId)
    }

    if (totalCount > 0) {
      val resolvedCategory = activityCategory.ifBlank {
        if (lessonId != null) "Lesson" else "Challenge"
      }
      val resolvedTitle = activityTitle.ifBlank {
        if (lessonId != null) "Lesson $lessonId" else "Science Practice"
      }
      progressDao.insertActivityLog(
        ActivityLogEntity(
          learnerId = targetLearnerId,
          activityTitle = resolvedTitle,
          activityCategory = resolvedCategory,
          lessonId = lessonId,
          gradeLevel = gradeLevel ?: cur.selectedGrade,
          correctCount = correctCount,
          totalCount = totalCount,
          xpEarned = xpEarned,
          starsEarned = earnedStars,
          completedAt = now,
        )
      )
    }

    val today = formatDate(now)
    val yesterday = formatDate(
      Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
    )
    val newStreak = when (cur.lastActiveDay) {
      today -> maxOf(1, cur.currentStreak)
      yesterday -> cur.currentStreak + 1
      else -> 1
    }
    progressDao.updateLearnerStats(
      cur.copy(
        totalXp = cur.totalXp + deltaXp.coerceAtLeast(0),
        currentStreak = newStreak,
        lastActiveDay = today,
        totalCorrect = cur.totalCorrect + deltaCorrect.coerceAtLeast(0),
        totalAnswered = cur.totalAnswered + deltaAnswered.coerceAtLeast(0),
      )
    )
  }

  suspend fun getAllLearnersReportSnapshots(): List<LearnerReportSnapshot> {
    val learners = progressDao.getAllLearnersOnce()
    return learners.map { learner ->
      val progressList = progressDao.getAllLessonProgressOnce(learner.id)
      val mistakes = progressDao.getMistakesOnce(learner.id)
      val logs = progressDao.getActivityLogsOnce(learner.id)
      val inProgressList = progressDao.getInProgressLessonsOnce(learner.id)
      LearnerReportSnapshot(
        stats = learner,
        progressMap = progressList.associateBy { it.lessonId },
        inProgressMap = inProgressList.associateBy { it.lessonId },
        mistakes = mistakes,
        activityLogs = logs,
      )
    }
  }

  suspend fun resetProgress() = statsMutex.withLock {
    val cur = resolveActiveLearner()
    progressDao.clearProgressForLearner(cur.id)
    progressDao.clearMistakesForLearner(cur.id)
    progressDao.clearActivityLogsForLearner(cur.id)
    progressDao.clearInProgressForLearner(cur.id)
    progressDao.updateLearnerStats(
      cur.copy(
        totalXp = 0,
        currentStreak = 0,
        lastActiveDay = "",
        totalCorrect = 0,
        totalAnswered = 0,
      )
    )
  }

  fun computeBadges(
    lessons: List<ScienceLesson>,
    progressMap: Map<String, LessonProgressEntity>,
    stats: LearnerStatsEntity,
  ): List<ScienceBadge> {
    val completedCount = progressMap.values.count { it.stars >= 1 }
    val threeStarCount = progressMap.values.count { it.stars == 3 }
    val totalStars = progressMap.values.sumOf { it.stars }
    val g3Done = lessons.filter { it.grade == 3 }.count { (progressMap[it.id]?.stars ?: 0) >= 1 }
    val g4Done = lessons.filter { it.grade == 4 }.count { (progressMap[it.id]?.stars ?: 0) >= 1 }
    val g5Done = lessons.filter { it.grade == 5 }.count { (progressMap[it.id]?.stars ?: 0) >= 1 }
    val g6Done = lessons.filter { it.grade == 6 }.count { (progressMap[it.id]?.stars ?: 0) >= 1 }

    return listOf(
      ScienceBadge(
        id = "first_discovery",
        title = "First Discovery",
        description = "Complete your first Science lesson",
        unlocked = completedCount >= 1,
        progressText = "$completedCount / 1 lesson",
      ),
      ScienceBadge(
        id = "star_collector",
        title = "Star Collector",
        description = "Earn 15 mastery stars across lessons",
        unlocked = totalStars >= 15,
        progressText = "$totalStars / 15 stars",
      ),
      ScienceBadge(
        id = "lab_perfection",
        title = "3-Star Scientist",
        description = "Earn 3 stars in 3 different lessons",
        unlocked = threeStarCount >= 3,
        progressText = "$threeStarCount / 3 lessons",
      ),
      ScienceBadge(
        id = "streak_hero",
        title = "Curiosity Streak",
        description = "Reach a 3-day learning streak",
        unlocked = stats.currentStreak >= 3,
        progressText = "${stats.currentStreak} / 3 days",
      ),
      ScienceBadge(
        id = "xp_explorer",
        title = "Lab Whiz (500 XP)",
        description = "Accumulate 500 Science XP",
        unlocked = stats.totalXp >= 500,
        progressText = "${stats.totalXp} / 500 XP",
      ),
      ScienceBadge(
        id = "all_grades",
        title = "Grade Explorer",
        description = "Try at least 1 lesson in Grades 3, 4, 5 & 6",
        unlocked = g3Done >= 1 && g4Done >= 1 && g5Done >= 1 && g6Done >= 1,
        progressText = "${listOf(g3Done, g4Done, g5Done, g6Done).count { it >= 1 }} / 4 grades",
      ),
    )
  }

  private fun formatDate(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return sdf.format(Date(millis))
  }
}

data class LearnerReportSnapshot(
  val stats: LearnerStatsEntity,
  val progressMap: Map<String, LessonProgressEntity>,
  val inProgressMap: Map<String, LessonInProgressEntity> = emptyMap(),
  val mistakes: List<MistakeEntity>,
  val activityLogs: List<ActivityLogEntity>,
)
