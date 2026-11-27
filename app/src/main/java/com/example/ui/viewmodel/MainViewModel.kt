package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.ActivityLogEntity
import com.example.data.local.LearnerStatsEntity
import com.example.data.local.LessonInProgressEntity
import com.example.data.local.LessonProgressEntity
import com.example.data.local.MistakeEntity
import com.example.data.model.ChallengeMode
import com.example.data.model.QuestionType
import com.example.data.model.ScienceBadge
import com.example.data.model.ScienceLesson
import com.example.data.model.ScienceQuestion
import com.example.data.repository.LearnerReportSnapshot
import com.example.data.repository.ScienceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppUiState(
  val isLoading: Boolean = true,
  val errorMessage: String? = null,
  val allLessons: List<ScienceLesson> = emptyList(),
  val selectedGrade: Int = 5,
  val selectedTerm: Int? = null,
  val searchQuery: String = "",
  val progressMap: Map<String, LessonProgressEntity> = emptyMap(),
  val inProgressMap: Map<String, LessonInProgressEntity> = emptyMap(),
  val mistakes: List<MistakeEntity> = emptyList(),
  val activityLogs: List<ActivityLogEntity> = emptyList(),
  val stats: LearnerStatsEntity = LearnerStatsEntity(),
  val learners: List<LearnerStatsEntity> = emptyList(),
  val learnerStarsMap: Map<Long, Int> = emptyMap(),
  val badges: List<ScienceBadge> = emptyList(),
) {
  val filteredLessons: List<ScienceLesson>
    get() = allLessons.filter { lesson ->
      lesson.grade == selectedGrade &&
        (selectedTerm == null || lesson.term == selectedTerm) &&
        (searchQuery.isBlank() ||
          lesson.title.contains(searchQuery, ignoreCase = true) ||
          lesson.contentTopic.contains(searchQuery, ignoreCase = true))
    }

  val totalStars: Int get() = progressMap.values.sumOf { it.stars }
  val maxStars: Int get() = allLessons.size * 3
  val completedLessonsCount: Int get() = progressMap.values.count { it.stars >= 1 }
}

data class QuestionAnswerFeedback(
  val isCorrect: Boolean,
  val headline: String,
  val detail: String,
  val xpGained: Int,
)

data class ActivitySessionState(
  val learnerId: Long = 1L,
  val title: String = "",
  val subtitle: String = "",
  val activityCategory: String = "Lesson",
  val gradeLevel: Int = 5,
  val lessonId: String? = null,
  val questions: List<ScienceQuestion> = emptyList(),
  val currentIndex: Int = 0,
  val answeredCount: Int = 0,
  val correctCount: Int = 0,
  val sessionXp: Int = 0,
  val currentStreakInSession: Int = 0,
  val attemptNumber: Int = 1,
  val isRetryPending: Boolean = false,
  val flushedCorrectCount: Int = 0,
  val flushedAnsweredCount: Int = 0,
  val flushedXpEarned: Int = 0,
  val hasCompletedSessionRecord: Boolean = false,
  val missedInSession: List<ScienceQuestion> = emptyList(),
  val isCompleted: Boolean = false,
  val feedback: QuestionAnswerFeedback? = null,
  // Per-question interactive working states:
  val selectedSingleIndex: Int? = null,
  val selectedBool: Boolean? = null,
  val selectedMultiIndices: Set<Int> = emptySet(),
  // Matching: shuffled right indices & leftIdx -> rightOriginalIdx
  val shuffledRightIndices: List<Int> = emptyList(),
  val activeLeftIndex: Int? = null,
  val matchedPairs: Map<Int, Int> = emptyMap(),
  // Sort Buckets: shuffled card indices & cardOriginalIdx -> bucketIdx
  val shuffledCardIndices: List<Int> = emptyList(),
  val activeCardIndex: Int? = null,
  val cardBucketAssignments: Map<Int, Int> = emptyMap(),
  // Order Steps: list of original step indices in current user order
  val orderedStepIndices: List<Int> = emptyList(),
  // Hotspot
  val selectedHotspotIndices: Set<Int> = emptySet(),
  // Slider
  val sliderValue: Float = 0f,
  // Simulation
  val simHasRun: Boolean = false,
  val simSelectedChoice: Int? = null,
  // Fill Blank
  val fillBlankSelections: List<String> = emptyList(),
) {
  val currentQuestion: ScienceQuestion?
    get() = questions.getOrNull(currentIndex)
}

class MainViewModel(
  private val repository: ScienceRepository,
) : ViewModel() {

  private val _lessons = MutableStateFlow<List<ScienceLesson>>(emptyList())
  private val _isLoading = MutableStateFlow(true)
  private val _error = MutableStateFlow<String?>(null)
  private val _selectedTerm = MutableStateFlow<Int?>(null)
  private val _searchQuery = MutableStateFlow("")

  private val _session = MutableStateFlow<ActivitySessionState?>(null)
  val sessionState: StateFlow<ActivitySessionState?> = _session.asStateFlow()
  private val dbMutex = Mutex()

  private fun launchPersist(block: suspend () -> Unit) {
    viewModelScope.launch {
      dbMutex.withLock {
        block()
      }
    }
  }

  val uiState: StateFlow<AppUiState> = combine(
    _isLoading,
    _error,
    _lessons,
    _selectedTerm,
    _searchQuery,
    repository.observeLessonProgress(),
    repository.observeMistakes(),
    repository.observeLearnerStats(),
    repository.observeAllLearners(),
    repository.observeStarsByLearner(),
    repository.observeActivityLogs(),
    repository.observeInProgressLessons(),
  ) { values ->
    @Suppress("UNCHECKED_CAST")
    val loading = values[0] as Boolean
    val err = values[1] as String?
    val lessons = values[2] as List<ScienceLesson>
    val term = values[3] as Int?
    val query = values[4] as String
    @Suppress("UNCHECKED_CAST")
    val progressMap = values[5] as Map<String, LessonProgressEntity>
    @Suppress("UNCHECKED_CAST")
    val mistakes = values[6] as List<MistakeEntity>
    val stats = values[7] as LearnerStatsEntity
    @Suppress("UNCHECKED_CAST")
    val learners = values[8] as List<LearnerStatsEntity>
    @Suppress("UNCHECKED_CAST")
    val learnerStarsMap = values[9] as Map<Long, Int>
    @Suppress("UNCHECKED_CAST")
    val activityLogs = values[10] as List<ActivityLogEntity>
    @Suppress("UNCHECKED_CAST")
    val inProgressMap = values[11] as Map<String, LessonInProgressEntity>

    AppUiState(
      isLoading = loading,
      errorMessage = err,
      allLessons = lessons,
      selectedGrade = stats.selectedGrade,
      selectedTerm = term,
      searchQuery = query,
      progressMap = progressMap,
      inProgressMap = inProgressMap,
      mistakes = mistakes,
      activityLogs = activityLogs,
      stats = stats,
      learners = learners,
      learnerStarsMap = learnerStarsMap,
      badges = repository.computeBadges(lessons, progressMap, stats),
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = AppUiState(),
  )

  init {
    viewModelScope.launch {
      try {
        repository.ensureInitialStats()
        _lessons.value = repository.loadCurriculum()
        _isLoading.value = false
      } catch (e: Exception) {
        _error.value = e.message ?: "Failed to load curriculum"
        _isLoading.value = false
      }
    }
  }

  fun selectGrade(grade: Int) {
    launchPersist { repository.setSelectedGrade(grade) }
  }

  fun selectTerm(term: Int?) {
    _selectedTerm.value = term
  }

  fun updateSearchQuery(query: String) {
    _searchQuery.value = query
  }

  fun updateProfile(name: String, grade: Int, avatarIndex: Int? = null) {
    launchPersist { repository.updateLearnerProfile(name, grade, avatarIndex) }
  }

  fun switchLearner(learnerId: Long) {
    _session.value = null
    launchPersist { repository.switchLearner(learnerId) }
  }

  fun addLearner(name: String, grade: Int, avatarIndex: Int) {
    _session.value = null
    launchPersist { repository.addLearner(name, grade, avatarIndex, switchToNew = true) }
  }

  fun deleteLearner(learnerId: Long) {
    launchPersist { repository.deleteLearner(learnerId) }
  }

  fun setTtsAutoRead(enabled: Boolean) {
    launchPersist { repository.setTtsAutoRead(enabled) }
  }

  fun resetAllProgress() {
    launchPersist { repository.resetProgress() }
  }

  suspend fun loadAllLearnersReportSnapshots(): List<LearnerReportSnapshot> =
    dbMutex.withLock { repository.getAllLearnersReportSnapshots() }

  fun startLesson(lesson: ScienceLesson) {
    if (lesson.questions.isEmpty()) return
    val activeLearnerId = uiState.value.stats.id
    val saved = uiState.value.inProgressMap[lesson.id]
    val resumeIdx = if (saved != null && saved.currentIndex in 1 until lesson.questions.size) {
      saved.currentIndex
    } else 0
    val targetQ = lesson.questions[resumeIdx]
    val restoredMissed = if (saved != null && resumeIdx > 0 && saved.missedQuestionIds.isNotBlank()) {
      val missedSet = saved.missedQuestionIds.split(",").filter { it.isNotBlank() }.toSet()
      lesson.questions.filter { it.id in missedSet }
    } else emptyList()

    _session.value = initQuestionState(
      ActivitySessionState(
        learnerId = activeLearnerId,
        title = lesson.title,
        subtitle = "Grade ${lesson.grade} • Term ${lesson.term} • ${lesson.weeks}",
        activityCategory = "Lesson (Term ${lesson.term})",
        gradeLevel = lesson.grade,
        lessonId = lesson.id,
        questions = lesson.questions,
        currentIndex = resumeIdx,
        answeredCount = if (resumeIdx > 0) saved?.answeredCount ?: resumeIdx else 0,
        correctCount = if (resumeIdx > 0) saved?.correctCount ?: 0 else 0,
        sessionXp = if (resumeIdx > 0) saved?.sessionXp ?: 0 else 0,
        currentStreakInSession = if (resumeIdx > 0) saved?.streakInSession ?: 0 else 0,
        flushedCorrectCount = if (resumeIdx > 0) saved?.correctCount ?: 0 else 0,
        flushedAnsweredCount = if (resumeIdx > 0) saved?.answeredCount ?: resumeIdx else 0,
        flushedXpEarned = if (resumeIdx > 0) saved?.sessionXp ?: 0 else 0,
        missedInSession = restoredMissed,
      ),
      targetQ,
    )
  }

  fun startReviewMistakes() {
    val allQs = _lessons.value.flatMap { it.questions }.associateBy { it.id }
    val mistakeIds = uiState.value.mistakes.map { it.questionId }
    val reviewQs = mistakeIds.mapNotNull { allQs[it] }.take(15)
    if (reviewQs.isEmpty()) return
    val activeLearnerId = uiState.value.stats.id
    _session.value = initQuestionState(
      ActivitySessionState(
        learnerId = activeLearnerId,
        title = "Review Missed Questions",
        subtitle = "${reviewQs.size} items to master",
        activityCategory = "Intervention Review",
        gradeLevel = uiState.value.selectedGrade,
        lessonId = null,
        questions = reviewQs,
      ),
      reviewQs.first(),
    )
  }

  fun startChallenge(mode: ChallengeMode) {
    if (mode == ChallengeMode.MISTAKE_REVIEW) {
      startReviewMistakes()
      return
    }
    val grade = uiState.value.selectedGrade
    val activeLearnerId = uiState.value.stats.id
    val gradeQs = _lessons.value.filter { it.grade == grade }.flatMap { it.questions }
    val pool = when (mode) {
      ChallengeMode.DAILY_MIX -> gradeQs
      ChallengeMode.QUICK_QUIZ -> gradeQs.filter {
        it.type == QuestionType.MULTIPLE_CHOICE || it.type == QuestionType.TRUE_FALSE
      }
      ChallengeMode.SORT_AND_MATCH -> gradeQs.filter {
        it.type == QuestionType.SORT_BUCKETS ||
          it.type == QuestionType.MATCHING ||
          it.type == QuestionType.ORDER_STEPS ||
          it.type == QuestionType.FILL_BLANK
      }
      ChallengeMode.VISUAL_SPOTTER -> gradeQs.filter {
        it.type == QuestionType.MULTI_SELECT ||
          it.type == QuestionType.HOTSPOT ||
          it.type == QuestionType.SLIDER ||
          it.type == QuestionType.SIMULATION
      }
      ChallengeMode.MISTAKE_REVIEW -> emptyList()
    }
    val picked = pool.shuffled().take(10)
    if (picked.isEmpty()) return
    _session.value = initQuestionState(
      ActivitySessionState(
        learnerId = activeLearnerId,
        title = mode.title,
        subtitle = "Grade $grade • ${picked.size} Activities",
        activityCategory = "Challenge",
        gradeLevel = grade,
        lessonId = null,
        questions = picked,
      ),
      picked.first(),
    )
  }

  fun startRetryMissedInSession() {
    val cur = _session.value ?: return
    if (cur.missedInSession.isEmpty()) return
    val qs = cur.missedInSession
    _session.value = initQuestionState(
      ActivitySessionState(
        learnerId = cur.learnerId,
        title = "Practice Missed Items",
        subtitle = "${qs.size} items",
        activityCategory = "Remediation Practice",
        gradeLevel = cur.gradeLevel,
        lessonId = null,
        questions = qs,
      ),
      qs.first(),
    )
  }

  fun saveAndPauseSession(closeSession: Boolean = true) {
    val cur = _session.value ?: return
    val isCurrentFinalized = cur.feedback != null && !cur.isRetryPending
    val nextUnansweredIdx = if (isCurrentFinalized) cur.currentIndex + 1 else cur.currentIndex

    if (!cur.hasCompletedSessionRecord && cur.answeredCount >= cur.questions.size && cur.questions.isNotEmpty()) {
      val deltaCorrect = (cur.correctCount - cur.flushedCorrectCount).coerceAtLeast(0)
      val deltaAnswered = (cur.answeredCount - cur.flushedAnsweredCount).coerceAtLeast(0)
      val deltaXp = (cur.sessionXp - cur.flushedXpEarned).coerceAtLeast(0)
      launchPersist {
        repository.completeActivitySession(
          learnerId = cur.learnerId,
          lessonId = cur.lessonId,
          correctCount = cur.correctCount,
          totalCount = cur.questions.size,
          xpEarned = cur.sessionXp,
          activityTitle = cur.title,
          activityCategory = cur.activityCategory,
          gradeLevel = cur.gradeLevel,
          deltaCorrect = deltaCorrect,
          deltaAnswered = deltaAnswered,
          deltaXp = deltaXp,
        )
      }
      _session.value = if (closeSession) null else cur.copy(hasCompletedSessionRecord = true)
      return
    }

    if (cur.answeredCount > 0) {
      val deltaCorrect = (cur.correctCount - cur.flushedCorrectCount).coerceAtLeast(0)
      val deltaAnswered = (cur.answeredCount - cur.flushedAnsweredCount).coerceAtLeast(0)
      val deltaXp = (cur.sessionXp - cur.flushedXpEarned).coerceAtLeast(0)
      launchPersist {
        repository.saveInProgressAndFlushStats(
          learnerId = cur.learnerId,
          lessonId = cur.lessonId,
          currentIndex = nextUnansweredIdx.coerceAtMost(cur.questions.lastIndex.coerceAtLeast(0)),
          answeredCount = cur.answeredCount,
          correctCount = cur.correctCount,
          sessionXp = cur.sessionXp,
          streakInSession = cur.currentStreakInSession,
          missedQuestionIds = cur.missedInSession.map { it.id },
          deltaCorrect = deltaCorrect,
          deltaAnswered = deltaAnswered,
          deltaXp = deltaXp,
        )
      }
    }
    if (closeSession) {
      _session.value = null
    } else {
      _session.value = cur.copy(
        flushedCorrectCount = cur.correctCount,
        flushedAnsweredCount = cur.answeredCount,
        flushedXpEarned = cur.sessionXp,
      )
    }
  }

  fun exitSession() {
    saveAndPauseSession(closeSession = true)
  }

  fun retrySecondAttempt() {
    val cur = _session.value ?: return
    val q = cur.currentQuestion ?: return
    _session.value = initQuestionState(
      cur.copy(
        attemptNumber = 2,
        isRetryPending = false,
      ),
      q,
    )
  }

  private fun initQuestionState(
    base: ActivitySessionState,
    q: ScienceQuestion,
  ): ActivitySessionState {
    val shuffledRight = if (q.type == QuestionType.MATCHING) {
      val idxs = q.rightItems.indices.toList().shuffled()
      if (idxs == q.rightItems.indices.toList() && idxs.size > 1) idxs.reversed() else idxs
    } else emptyList()

    val shuffledCards = if (q.type == QuestionType.SORT_BUCKETS) {
      q.cards.indices.toList().shuffled()
    } else emptyList()

    val shuffledSteps = if (q.type == QuestionType.ORDER_STEPS) {
      val orig = q.steps.indices.toList()
      var shuf = orig.shuffled()
      if (shuf == orig && orig.size > 1) shuf = orig.reversed()
      shuf
    } else emptyList()

    return base.copy(
      feedback = null,
      selectedSingleIndex = null,
      selectedBool = null,
      selectedMultiIndices = emptySet(),
      shuffledRightIndices = shuffledRight,
      activeLeftIndex = if (q.type == QuestionType.MATCHING) 0 else null,
      matchedPairs = emptyMap(),
      shuffledCardIndices = shuffledCards,
      activeCardIndex = shuffledCards.firstOrNull(),
      cardBucketAssignments = emptyMap(),
      orderedStepIndices = shuffledSteps,
      selectedHotspotIndices = emptySet(),
      sliderValue = if (q.minValue < 0 && q.maxValue > 0) 0f else q.minValue.toFloat(),
      simHasRun = false,
      simSelectedChoice = null,
      fillBlankSelections = emptyList(),
    )
  }

  fun onSelectSingle(index: Int) {
    _session.update { it?.copy(selectedSingleIndex = index) }
  }

  fun onSelectBool(value: Boolean) {
    _session.update { it?.copy(selectedBool = value) }
  }

  fun onToggleMultiSelect(index: Int) {
    _session.update { cur ->
      cur ?: return@update null
      val updated = cur.selectedMultiIndices.toMutableSet()
      if (!updated.add(index)) updated.remove(index)
      cur.copy(selectedMultiIndices = updated)
    }
  }

  fun onSelectMatchingLeft(leftIdx: Int) {
    _session.update { cur ->
      cur ?: return@update null
      // Tapping an already matched left clears it or selects it
      val updatedMap = cur.matchedPairs.toMutableMap()
      updatedMap.remove(leftIdx)
      cur.copy(activeLeftIndex = leftIdx, matchedPairs = updatedMap)
    }
  }

  fun onSelectMatchingRight(rightOriginalIdx: Int) {
    _session.update { cur ->
      cur ?: return@update null
      val q = cur.currentQuestion ?: return@update cur
      val leftIdx = cur.activeLeftIndex ?: (q.leftItems.indices.firstOrNull { it !in cur.matchedPairs }) ?: return@update cur
      val updatedMap = cur.matchedPairs.toMutableMap()
      // Remove any existing pair pointing to this rightOriginalIdx
      updatedMap.entries.removeAll { it.value == rightOriginalIdx }
      updatedMap[leftIdx] = rightOriginalIdx
      val nextLeft = q.leftItems.indices.firstOrNull { it !in updatedMap }
      cur.copy(matchedPairs = updatedMap, activeLeftIndex = nextLeft)
    }
  }

  fun onSelectSortCard(cardOriginalIdx: Int) {
    _session.update { it?.copy(activeCardIndex = cardOriginalIdx) }
  }

  fun onAssignCardToBucket(bucketIdx: Int) {
    _session.update { cur ->
      cur ?: return@update null
      val cardIdx = cur.activeCardIndex ?: return@update cur
      val updated = cur.cardBucketAssignments.toMutableMap()
      updated[cardIdx] = bucketIdx
      val nextCard = cur.shuffledCardIndices.firstOrNull { it !in updated }
      cur.copy(cardBucketAssignments = updated, activeCardIndex = nextCard)
    }
  }

  fun onRemoveCardFromBucket(cardOriginalIdx: Int) {
    _session.update { cur ->
      cur ?: return@update null
      val updated = cur.cardBucketAssignments.toMutableMap()
      updated.remove(cardOriginalIdx)
      cur.copy(cardBucketAssignments = updated, activeCardIndex = cardOriginalIdx)
    }
  }

  fun onMoveStepUp(position: Int) {
    if (position <= 0) return
    _session.update { cur ->
      cur ?: return@update null
      val list = cur.orderedStepIndices.toMutableList()
      val tmp = list[position - 1]
      list[position - 1] = list[position]
      list[position] = tmp
      cur.copy(orderedStepIndices = list)
    }
  }

  fun onMoveStepDown(position: Int) {
    _session.update { cur ->
      cur ?: return@update null
      if (position >= cur.orderedStepIndices.size - 1) return@update cur
      val list = cur.orderedStepIndices.toMutableList()
      val tmp = list[position + 1]
      list[position + 1] = list[position]
      list[position] = tmp
      cur.copy(orderedStepIndices = list)
    }
  }

  fun onToggleHotspot(index: Int) {
    _session.update { cur ->
      cur ?: return@update null
      val q = cur.currentQuestion ?: return@update cur
      val updated = if (q.correctIndices.size == 1) {
        setOf(index)
      } else {
        val set = cur.selectedHotspotIndices.toMutableSet()
        if (!set.add(index)) set.remove(index)
        set
      }
      cur.copy(selectedHotspotIndices = updated)
    }
  }

  fun onSliderChange(value: Float) {
    _session.update { it?.copy(sliderValue = value) }
  }

  fun onSimulationRun() {
    _session.update { it?.copy(simHasRun = true) }
  }

  fun onSelectSimulationChoice(index: Int) {
    _session.update { it?.copy(simSelectedChoice = index) }
  }

  fun onToggleFillBlankChoice(choice: String) {
    _session.update { cur ->
      cur ?: return@update null
      val q = cur.currentQuestion ?: return@update cur
      val needed = q.correctAnswers.size.coerceAtLeast(1)
      val list = cur.fillBlankSelections.toMutableList()
      if (list.contains(choice)) {
        list.remove(choice)
      } else {
        if (list.size >= needed) {
          list.removeAt(list.lastIndex)
        }
        list.add(choice)
      }
      cur.copy(fillBlankSelections = list)
    }
  }

  fun canSubmitCurrentAnswer(): Boolean {
    val s = _session.value ?: return false
    val q = s.currentQuestion ?: return false
    if (s.feedback != null) return false
    return when (q.type) {
      QuestionType.MULTIPLE_CHOICE -> s.selectedSingleIndex != null
      QuestionType.TRUE_FALSE -> s.selectedBool != null
      QuestionType.MULTI_SELECT -> s.selectedMultiIndices.isNotEmpty()
      QuestionType.MATCHING -> s.matchedPairs.size == q.leftItems.size
      QuestionType.SORT_BUCKETS -> s.cardBucketAssignments.size == q.cards.size
      QuestionType.ORDER_STEPS -> s.orderedStepIndices.size == q.steps.size
      QuestionType.HOTSPOT -> s.selectedHotspotIndices.isNotEmpty()
      QuestionType.SLIDER -> true
      QuestionType.SIMULATION -> s.simSelectedChoice != null
      QuestionType.FILL_BLANK -> s.fillBlankSelections.size == q.correctAnswers.size.coerceAtLeast(1)
    }
  }

  fun submitAnswer() {
    val cur = _session.value ?: return
    val q = cur.currentQuestion ?: return
    if (cur.feedback != null) return

    val isCorrect = when (q.type) {
      QuestionType.MULTIPLE_CHOICE -> cur.selectedSingleIndex == q.correctIndex
      QuestionType.TRUE_FALSE -> cur.selectedBool == q.correctBool
      QuestionType.MULTI_SELECT -> cur.selectedMultiIndices == q.correctIndices.toSet()
      QuestionType.MATCHING -> q.correctPairs.all { (l, r) -> cur.matchedPairs[l] == r }
      QuestionType.SORT_BUCKETS -> q.cards.indices.all { idx ->
        cur.cardBucketAssignments[idx] == q.cards[idx].bucketIndex
      }
      QuestionType.ORDER_STEPS -> cur.orderedStepIndices == q.steps.indices.toList()
      QuestionType.HOTSPOT -> {
        val selected = cur.selectedHotspotIndices
        val targets = q.correctIndices.toSet()
        selected.isNotEmpty() && selected.all { it in targets } &&
          (targets.size == 1 || selected == targets || q.answerText.contains(" or ", ignoreCase = true) || q.answerText.contains("either", ignoreCase = true))
      }
      QuestionType.SLIDER -> kotlin.math.abs(cur.sliderValue - q.targetValue) <= 1.5f
      QuestionType.SIMULATION -> cur.simSelectedChoice == q.correctIndex
      QuestionType.FILL_BLANK -> {
        cur.fillBlankSelections.map { it.trim().lowercase() } ==
          q.correctAnswers.map { it.trim().lowercase() }
      }
    }

    if (!isCorrect && cur.attemptNumber == 1) {
      _session.value = cur.copy(
        isRetryPending = true,
        feedback = QuestionAnswerFeedback(
          isCorrect = false,
          headline = "Not quite — Try Again! (1 try left)",
          detail = "Look closely at the question and give it one more try.",
          xpGained = 0,
        ),
      )
      return
    }

    val newStreak = if (isCorrect) cur.currentStreakInSession + 1 else 0
    val xpGained = when {
      isCorrect && cur.attemptNumber == 1 -> 10 + (if (newStreak >= 3) 5 else 0)
      isCorrect -> 6
      else -> 2
    }
    val headline = when {
      isCorrect && cur.attemptNumber > 1 -> "Great recovery on Try 2! +$xpGained XP"
      isCorrect && newStreak >= 5 -> "Unstoppable! $newStreak in a row!"
      isCorrect && newStreak >= 3 -> "Great Streak! +$xpGained XP"
      isCorrect -> "Awesome Job! +$xpGained XP"
      else -> "Recorded for Review — Let's Learn!"
    }
    val detail = buildString {
      if (!isCorrect) {
        append("Correct answer:")
        val formattedAnswer = when (q.type) {
          QuestionType.MULTIPLE_CHOICE -> " " + (q.options.getOrNull(q.correctIndex) ?: q.answerText)
          QuestionType.TRUE_FALSE -> " " + if (q.correctBool) "True" else "False"
          QuestionType.MATCHING -> "\n" + q.leftItems.indices.joinToString("\n") { lIdx ->
            val rIdx = q.correctPairs[lIdx]
            val rightText = if (rIdx != null) q.rightItems.getOrElse(rIdx) { "" } else ""
            "• ${q.leftItems[lIdx]} → $rightText"
          }
          else -> " ${q.answerText}"
        }
        append(formattedAnswer)
      }
      if (q.explanation.isNotBlank()) {
        if (isNotEmpty()) append("\n")
        append(q.explanation)
      }
    }.ifBlank {
      if (isCorrect) "You nailed this science concept!" else "Review the correct answer above."
    }

    val newCorrect = cur.correctCount + if (isCorrect) 1 else 0
    val newAnswered = cur.answeredCount + 1
    val newXp = cur.sessionXp + xpGained
    val newMissed = if (isCorrect) cur.missedInSession else cur.missedInSession + q
    val deltaCorrect = (newCorrect - cur.flushedCorrectCount).coerceAtLeast(0)
    val deltaAnswered = (newAnswered - cur.flushedAnsweredCount).coerceAtLeast(0)
    val deltaXp = (newXp - cur.flushedXpEarned).coerceAtLeast(0)
    val nextIdxForSave = (cur.currentIndex + 1).coerceAtMost(cur.questions.lastIndex.coerceAtLeast(0))

    launchPersist {
      repository.recordQuestionResult(cur.learnerId, q.id, q.lessonId, isCorrect)
      if (newAnswered < cur.questions.size) {
        repository.saveInProgressAndFlushStats(
          learnerId = cur.learnerId,
          lessonId = cur.lessonId,
          currentIndex = nextIdxForSave,
          answeredCount = newAnswered,
          correctCount = newCorrect,
          sessionXp = newXp,
          streakInSession = newStreak,
          missedQuestionIds = newMissed.map { it.id },
          deltaCorrect = deltaCorrect,
          deltaAnswered = deltaAnswered,
          deltaXp = deltaXp,
        )
      }
    }

    _session.value = cur.copy(
      answeredCount = newAnswered,
      correctCount = newCorrect,
      sessionXp = newXp,
      currentStreakInSession = newStreak,
      isRetryPending = false,
      flushedCorrectCount = if (newAnswered < cur.questions.size) newCorrect else cur.flushedCorrectCount,
      flushedAnsweredCount = if (newAnswered < cur.questions.size) newAnswered else cur.flushedAnsweredCount,
      flushedXpEarned = if (newAnswered < cur.questions.size) newXp else cur.flushedXpEarned,
      missedInSession = newMissed,
      feedback = QuestionAnswerFeedback(
        isCorrect = isCorrect,
        headline = headline,
        detail = detail,
        xpGained = xpGained,
      ),
    )
  }

  fun nextQuestion() {
    val cur = _session.value ?: return
    val nextIdx = cur.currentIndex + 1
    if (nextIdx >= cur.questions.size) {
      if (!cur.hasCompletedSessionRecord) {
        val deltaCorrect = (cur.correctCount - cur.flushedCorrectCount).coerceAtLeast(0)
        val deltaAnswered = (cur.answeredCount - cur.flushedAnsweredCount).coerceAtLeast(0)
        val deltaXp = (cur.sessionXp - cur.flushedXpEarned).coerceAtLeast(0)
        launchPersist {
          repository.completeActivitySession(
            learnerId = cur.learnerId,
            lessonId = cur.lessonId,
            correctCount = cur.correctCount,
            totalCount = cur.questions.size,
            xpEarned = cur.sessionXp,
            activityTitle = cur.title,
            activityCategory = cur.activityCategory,
            gradeLevel = cur.gradeLevel,
            deltaCorrect = deltaCorrect,
            deltaAnswered = deltaAnswered,
            deltaXp = deltaXp,
          )
        }
      }
      _session.value = cur.copy(
        isCompleted = true,
        hasCompletedSessionRecord = true,
        flushedCorrectCount = cur.correctCount,
        flushedAnsweredCount = cur.answeredCount,
        flushedXpEarned = cur.sessionXp,
        feedback = null,
        attemptNumber = 1,
        isRetryPending = false,
      )
    } else {
      _session.value = initQuestionState(
        cur.copy(
          currentIndex = nextIdx,
          attemptNumber = 1,
          isRetryPending = false,
        ),
        cur.questions[nextIdx],
      )
    }
  }
}

class MainViewModelFactory(
  private val repository: ScienceRepository,
) : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
      @Suppress("UNCHECKED_CAST")
      return MainViewModel(repository) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}
