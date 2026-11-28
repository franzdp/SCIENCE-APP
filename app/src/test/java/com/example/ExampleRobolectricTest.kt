package com.example

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.CurriculumLoader
import com.example.data.model.AiGameType
import com.example.data.model.GameGeneratorState
import com.example.data.model.GameRoundItem
import com.example.data.model.GeneratedScienceGame
import com.example.data.model.LiveVoiceState
import com.example.data.model.QuestionTutorContext
import com.example.data.model.QuestionType
import com.example.data.model.ScienceLesson
import com.example.data.model.ScienceQuestion
import com.example.data.model.TutorLanguage
import com.example.data.repository.AiTutorService
import com.example.data.repository.OFFLINE_AI_WARNING
import com.example.data.repository.ScienceRepository
import com.example.ui.viewmodel.AiAssistantViewModel
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.buildQuestionTutorContext
import com.example.util.ConnectivityObserver
import com.example.util.configureAppCheck
import com.example.util.ensureFirebaseAppInitialized
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  private fun sampleLesson(id: String = "test_lesson_1", questionCount: Int = 5): ScienceLesson {
    val questions = (1..questionCount).map { idx ->
      ScienceQuestion(
        id = "${id}_q$idx",
        lessonId = id,
        type = QuestionType.MULTIPLE_CHOICE,
        prompt = "Question $idx prompt",
        answerText = "Option B",
        explanation = "Option B is correct.",
        options = listOf("Option A", "Option B", "Option C", "Option D"),
        correctIndex = 1,
      )
    }
    return ScienceLesson(
      id = id,
      order = 1,
      title = "Matter & States",
      grade = 5,
      term = 1,
      contentTopic = "States of Matter",
      weeks = "Weeks 1-2",
      questions = questions,
    )
  }

  private suspend fun waitUntil(timeoutMs: Long = 5000L, predicate: () -> Boolean) {
    withTimeout(timeoutMs) {
      while (!predicate()) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        delay(15)
      }
    }
  }

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("SCIENTISTS of Bagacay ES", appName)
  }

  @Test
  fun `curriculum asset loads all grades and lessons`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val lessons = CurriculumLoader(context).loadLessons()
    assertEquals(60, lessons.size)
    assertTrue(lessons.any { it.grade == 3 && it.term == 1 })
    assertTrue(lessons.any { it.grade == 3 && it.term == 2 })
    assertTrue(lessons.any { it.grade == 3 && it.term == 3 })
    assertTrue(lessons.any { it.grade == 4 })
    assertTrue(lessons.any { it.grade == 5 })
    assertTrue(lessons.any { it.grade == 6 })
    assertEquals(1202, lessons.sumOf { it.questions.size })
  }

  @Test
  fun `multi learner switching keeps progress and mistakes isolated`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = ScienceRepository(CurriculumLoader(context), db.progressDao())
      repo.ensureInitialStats()

      val learner1 = repo.observeLearnerStats().first()
      assertEquals("Young Scientist", learner1.learnerName)

      // Record session & mistake for Learner 1
      repo.recordQuestionResult(learner1.id, "q1", "lesson_a", isCorrect = false)
      repo.completeActivitySession(learner1.id, "lesson_a", correctCount = 9, totalCount = 10, xpEarned = 95)

      val l1Progress = repo.observeLessonProgress().first()
      assertEquals(3, l1Progress["lesson_a"]?.stars)
      assertEquals(95, repo.observeLearnerStats().first().totalXp)
      assertEquals(1, repo.observeMistakes().first().size)

      // Add Learner 2 and automatically switch to them
      val learner2Id = repo.addLearner(name = "Maria", grade = 4, avatarIndex = 2, switchToNew = true)
      val learner2 = repo.observeLearnerStats().first()
      assertEquals(learner2Id, learner2.id)
      assertEquals("Maria", learner2.learnerName)
      assertEquals(4, learner2.selectedGrade)
      assertEquals(0, learner2.totalXp)
      assertTrue(repo.observeLessonProgress().first().isEmpty())
      assertTrue(repo.observeMistakes().first().isEmpty())

      // Record separate progress for Learner 2
      repo.recordQuestionResult(learner2Id, "q99", "lesson_b", isCorrect = false)
      repo.completeActivitySession(learner2Id, "lesson_b", correctCount = 7, totalCount = 10, xpEarned = 70)

      assertEquals(2, repo.observeLessonProgress().first()["lesson_b"]?.stars)
      assertFalse(repo.observeLessonProgress().first().containsKey("lesson_a"))
      assertEquals(70, repo.observeLearnerStats().first().totalXp)
      assertEquals("q99", repo.observeMistakes().first().single().questionId)

      // Switch back to Learner 1 and verify their records are preserved independently
      repo.switchLearner(learner1.id)
      val backToL1 = repo.observeLearnerStats().first()
      assertEquals(learner1.id, backToL1.id)
      assertEquals(95, backToL1.totalXp)
      assertEquals(3, repo.observeLessonProgress().first()["lesson_a"]?.stars)
      assertFalse(repo.observeLessonProgress().first().containsKey("lesson_b"))
      assertEquals("q1", repo.observeMistakes().first().single().questionId)

      // Resetting Learner 1 must not clear Learner 2
      repo.resetProgress()
      assertEquals(0, repo.observeLearnerStats().first().totalXp)
      assertTrue(repo.observeLessonProgress().first().isEmpty())

      repo.switchLearner(learner2Id)
      assertEquals(70, repo.observeLearnerStats().first().totalXp)
      assertEquals(2, repo.observeLessonProgress().first()["lesson_b"]?.stars)
    } finally {
      db.close()
    }
  }

  @Test
  fun `activity logs and competency report snapshots record per learner progress accurately`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val lessons = CurriculumLoader(context).loadLessons()
      val repo = ScienceRepository(CurriculumLoader(context), db.progressDao())
      repo.ensureInitialStats()
      val learner = repo.observeLearnerStats().first()
      val firstLesson = lessons.first { it.grade == 5 }

      repo.recordQuestionResult(learner.id, firstLesson.questions.first().id, firstLesson.id, isCorrect = false)
      repo.completeActivitySession(
        learnerId = learner.id,
        lessonId = firstLesson.id,
        correctCount = 17,
        totalCount = firstLesson.questions.size,
        xpEarned = 180,
        activityTitle = firstLesson.title,
        activityCategory = "Lesson (Term ${firstLesson.term})",
        gradeLevel = firstLesson.grade,
      )
      repo.completeActivitySession(
        learnerId = learner.id,
        lessonId = null,
        correctCount = 8,
        totalCount = 10,
        xpEarned = 90,
        activityTitle = "Daily Science Sprint",
        activityCategory = "Challenge",
        gradeLevel = 5,
      )

      val updatedLearner = repo.observeLearnerStats().first()
      val progressMap = repo.observeLessonProgress().first()
      val mistakes = repo.observeMistakes().first()
      val logs = repo.observeActivityLogs().first()
      assertEquals(2, logs.size)
      assertEquals("Daily Science Sprint", logs.first().activityTitle)
      assertEquals(firstLesson.title, logs.last().activityTitle)

      val snapshots = repo.getAllLearnersReportSnapshots()
      assertEquals(1, snapshots.size)
      assertEquals(2, snapshots.first().activityLogs.size)
      assertEquals(1, snapshots.first().mistakes.size)

      val badges = repo.computeBadges(lessons, progressMap, updatedLearner)
      com.example.util.PdfReportScope.entries.forEach { scope ->
        val req = com.example.util.SciencePdfReportRequest(
          scope = scope,
          activeLearner = updatedLearner,
          allLessons = lessons,
          progressMap = progressMap,
          mistakes = mistakes,
          activityLogs = logs,
          badges = badges,
          allLearnerSnapshots = snapshots,
        )
        val fileName = com.example.util.ScienceReportPdfGenerator.suggestedFileName(req)
        assertTrue(fileName.startsWith("Science_Competency_Report_"))
        assertFalse(fileName.contains("ARAL", ignoreCase = true))
        assertTrue(fileName.endsWith(".pdf"))
      }

      // Reset clears activity logs as well
      repo.resetProgress()
      assertTrue(repo.observeActivityLogs().first().isEmpty())
    } finally {
      db.close()
    }
  }

  @Test
  fun `two attempt rule allows one retry and records difficulty only after second wrong try`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = ScienceRepository(CurriculumLoader(context), db.progressDao())
      val vm = MainViewModel(repo)
      val collector = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }
      waitUntil { !vm.uiState.value.isLoading }

      val lesson = sampleLesson(questionCount = 3)
      vm.startLesson(lesson)

      // Item 1: Wrong on 1st try -> triggers retry prompt without advancing or recording mistake
      vm.onSelectSingle(0)
      vm.submitAnswer()
      val afterTry1Wrong = vm.sessionState.value!!
      assertTrue(afterTry1Wrong.isRetryPending)
      assertEquals(0, afterTry1Wrong.answeredCount)
      assertTrue(afterTry1Wrong.missedInSession.isEmpty())
      assertTrue(afterTry1Wrong.feedback?.headline?.contains("Try Again") == true)
      assertTrue(vm.uiState.value.mistakes.isEmpty())

      // Retry -> Attempt 2
      vm.retrySecondAttempt()
      val onTry2 = vm.sessionState.value!!
      assertEquals(2, onTry2.attemptNumber)
      assertFalse(onTry2.isRetryPending)
      assertNull(onTry2.feedback)

      // Item 1: Wrong again on 2nd try -> records mistake & proceeds
      vm.onSelectSingle(2)
      vm.submitAnswer()
      val afterTry2Wrong = vm.sessionState.value!!
      assertFalse(afterTry2Wrong.isRetryPending)
      assertEquals(1, afterTry2Wrong.answeredCount)
      assertEquals(0, afterTry2Wrong.correctCount)
      assertEquals(listOf("test_lesson_1_q1"), afterTry2Wrong.missedInSession.map { it.id })
      waitUntil { vm.uiState.value.mistakes.any { it.questionId == "test_lesson_1_q1" } }

      // Item 2: Wrong on 1st try, then CORRECT on 2nd try -> recovers without recording mistake
      vm.nextQuestion()
      vm.onSelectSingle(3)
      vm.submitAnswer()
      assertTrue(vm.sessionState.value!!.isRetryPending)

      vm.retrySecondAttempt()
      vm.onSelectSingle(1)
      vm.submitAnswer()
      val afterTry2Correct = vm.sessionState.value!!
      assertFalse(afterTry2Correct.isRetryPending)
      assertEquals(2, afterTry2Correct.answeredCount)
      assertEquals(1, afterTry2Correct.correctCount)
      assertEquals(1, afterTry2Correct.missedInSession.size)
      assertFalse(vm.uiState.value.mistakes.any { it.questionId == "test_lesson_1_q2" })

      collector.cancel()
    } finally {
      db.close()
    }
  }

  @Test
  fun `unfinished activity pauses at next unanswered item per learner and resumes cleanly`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = ScienceRepository(CurriculumLoader(context), db.progressDao())
      val vm = MainViewModel(repo)
      val collector = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }
      waitUntil { !vm.uiState.value.isLoading }

      val lesson = sampleLesson(id = "g5_t1_w1", questionCount = 4)
      val learner1Id = vm.uiState.value.stats.id

      // Learner 1 answers 2 out of 4 questions and exits mid-activity
      vm.startLesson(lesson)
      vm.onSelectSingle(1) // Q1 correct (+10 XP)
      vm.submitAnswer()
      vm.nextQuestion()

      vm.onSelectSingle(1) // Q2 correct (+10 XP)
      vm.submitAnswer()
      vm.exitSession()

      assertNull(vm.sessionState.value)
      waitUntil {
        vm.uiState.value.inProgressMap[lesson.id]?.currentIndex == 2 &&
          vm.uiState.value.stats.totalXp == 20
      }
      val savedForL1 = vm.uiState.value.inProgressMap[lesson.id]!!
      assertEquals(2, savedForL1.currentIndex)
      assertEquals(2, savedForL1.answeredCount)
      assertEquals(2, savedForL1.correctCount)
      assertEquals(20, vm.uiState.value.stats.totalXp)
      assertEquals(2, vm.uiState.value.stats.totalAnswered)

      // Add Learner 2 and verify Learner 2 has empty inProgressMap
      vm.addLearner("Pedro", grade = 5, avatarIndex = 1)
      waitUntil {
        vm.uiState.value.stats.learnerName == "Pedro" &&
          vm.uiState.value.inProgressMap.isEmpty()
      }
      assertTrue(vm.uiState.value.inProgressMap.isEmpty())
      assertEquals(0, vm.uiState.value.stats.totalXp)

      // Switch back to Learner 1 and return to the lesson -> directed to item #3 (index 2)
      vm.switchLearner(learner1Id)
      waitUntil {
        vm.uiState.value.stats.id == learner1Id &&
          vm.uiState.value.inProgressMap.containsKey(lesson.id)
      }
      assertNotNull(vm.uiState.value.inProgressMap[lesson.id])

      vm.startLesson(lesson)
      val resumed = vm.sessionState.value!!
      assertEquals(2, resumed.currentIndex)
      assertEquals(2, resumed.answeredCount)
      assertEquals("g5_t1_w1_q3", resumed.currentQuestion?.id)

      // Finish remaining 2 questions and check no XP / answered double-counting
      vm.onSelectSingle(1) // Q3 correct (+15 XP with 3-streak)
      vm.submitAnswer()
      vm.nextQuestion()
      vm.onSelectSingle(1) // Q4 correct (+15 XP with 4-streak)
      vm.submitAnswer()
      vm.nextQuestion()

      assertTrue(vm.sessionState.value!!.isCompleted)
      waitUntil {
        !vm.uiState.value.inProgressMap.containsKey(lesson.id) &&
          vm.uiState.value.progressMap.containsKey(lesson.id) &&
          vm.uiState.value.stats.totalXp == 50
      }
      assertEquals(4, vm.uiState.value.stats.totalAnswered)
      assertEquals(4, vm.uiState.value.stats.totalCorrect)
      assertEquals(50, vm.uiState.value.stats.totalXp)

      collector.cancel()
    } finally {
      db.close()
    }
  }

  @Test
  fun `grade 6 term 3 week 10 questions 4 and 5 match and sort accurately`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = ScienceRepository(CurriculumLoader(context), db.progressDao())
      val vm = MainViewModel(repo)
      val collector = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }
      waitUntil { !vm.uiState.value.isLoading }

      val week10 = vm.uiState.value.allLessons.first {
        it.grade == 6 && it.term == 3 && it.weeks == "Week 10"
      }
      val q4 = week10.questions[3]
      val q5 = week10.questions[4]
      assertEquals("g6_t3_l51_q04", q4.id)
      assertEquals("g6_t3_l51_q05", q5.id)
      assertEquals(mapOf(0 to 0, 1 to 1, 2 to 2), q4.correctPairs)
      assertEquals(listOf(0, 1, 2), q5.cards.map { it.bucketIndex })

      // Answer Q4 correctly using MainViewModel matching flow
      val q4OnlyLesson = week10.copy(questions = listOf(q4, q5))
      vm.startLesson(q4OnlyLesson)

      // Try 1: intentionally mismatch 0->1, 1->0, 2->2
      vm.onSelectMatchingLeft(0)
      vm.onSelectMatchingRight(1)
      vm.onSelectMatchingLeft(1)
      vm.onSelectMatchingRight(0)
      vm.onSelectMatchingLeft(2)
      vm.onSelectMatchingRight(2)
      vm.submitAnswer()
      assertTrue(vm.sessionState.value!!.isRetryPending)

      // Try 2: also mismatch to verify readable pair feedback (not 1-A, 2-B, 3-C)
      vm.retrySecondAttempt()
      vm.onSelectMatchingLeft(0)
      vm.onSelectMatchingRight(2)
      vm.onSelectMatchingLeft(1)
      vm.onSelectMatchingRight(1)
      vm.onSelectMatchingLeft(2)
      vm.onSelectMatchingRight(0)
      vm.submitAnswer()
      val wrongDetail = vm.sessionState.value!!.feedback!!.detail
      assertFalse(wrongDetail.contains("1-A"))
      assertTrue(wrongDetail.contains("Earth spinning on its axis (Rotation) → Cycle of day and night"))

      // Advance to Q5 and verify each card maps to its distinct bucket (0, 1, 2)
      vm.nextQuestion()
      q5.cards.indices.forEach { cardIdx ->
        vm.onSelectSortCard(cardIdx)
        vm.onAssignCardToBucket(q5.cards[cardIdx].bucketIndex)
      }
      vm.submitAnswer()
      assertTrue(vm.sessionState.value!!.feedback!!.isCorrect)

      collector.cancel()
    } finally {
      db.close()
    }
  }

  @Test
  fun `all curriculum questions validate structurally and fixed items evaluate accurately`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val lessons = CurriculumLoader(context).loadLessons()
    val seenLessonIds = mutableSetOf<String>()
    val seenQuestionIds = mutableSetOf<String>()

    lessons.forEach { lesson ->
      assertTrue("Duplicate lesson id ${lesson.id}", seenLessonIds.add(lesson.id))
      assertTrue("Empty questions in ${lesson.id}", lesson.questions.isNotEmpty())
      lesson.questions.forEach { q ->
        assertTrue("Duplicate question id ${q.id}", seenQuestionIds.add(q.id))
        when (q.type) {
          QuestionType.MULTIPLE_CHOICE -> {
            assertTrue("${q.id} MC index", q.correctIndex in q.options.indices)
          }
          QuestionType.TRUE_FALSE -> Unit
          QuestionType.MULTI_SELECT -> {
            assertTrue("${q.id} MS empty", q.correctIndices.isNotEmpty())
            assertTrue("${q.id} MS bounds", q.correctIndices.all { it in q.items.indices })
          }
          QuestionType.MATCHING -> {
            assertEquals("${q.id} MATCH sizes", q.leftItems.size, q.rightItems.size)
            assertEquals("${q.id} MATCH pairs", q.leftItems.size, q.correctPairs.size)
            q.correctPairs.forEach { (l, r) ->
              assertTrue("${q.id} left bounds", l in q.leftItems.indices)
              assertTrue("${q.id} right bounds", r in q.rightItems.indices)
            }
          }
          QuestionType.SORT_BUCKETS -> {
            assertTrue("${q.id} buckets", q.buckets.isNotEmpty())
            assertTrue("${q.id} cards", q.cards.isNotEmpty())
            assertTrue("${q.id} card bounds", q.cards.all { it.bucketIndex in q.buckets.indices })
            assertFalse(
              "${q.id} all cards in Not Included",
              q.cards.all { q.buckets[it.bucketIndex] == "Not Included" },
            )
          }
          QuestionType.ORDER_STEPS -> {
            assertTrue("${q.id} steps", q.steps.size >= 2)
          }
          QuestionType.HOTSPOT -> {
            assertTrue("${q.id} hotspot empty", q.correctIndices.isNotEmpty())
            assertTrue("${q.id} hotspot bounds", q.correctIndices.all { it in q.hotspots.indices })
          }
          QuestionType.SLIDER -> {
            assertTrue("${q.id} slider bounds", q.minValue < q.maxValue && q.targetValue in q.minValue..q.maxValue)
          }
          QuestionType.SIMULATION -> {
            assertTrue("${q.id} sim choices", q.correctIndex in q.choices.indices)
          }
          QuestionType.FILL_BLANK -> {
            assertTrue("${q.id} fill blank", q.correctAnswers.isNotEmpty() && q.correctAnswers.all { it in q.choices })
          }
        }
      }
    }

    val byId = lessons.flatMap { it.questions }.associateBy { it.id }
    assertEquals(listOf(1), byId.getValue("g3_t1_l02_q13").correctIndices)
    assertEquals(listOf(0, 1, 2), byId.getValue("g3_t1_l03_q05").cards.map { it.bucketIndex })
    assertEquals(listOf(0, 1, 2), byId.getValue("g4_t1_l07_q18").cards.map { it.bucketIndex })
    assertEquals(listOf(0, 1, 2), byId.getValue("g4_t1_l08_q05").cards.map { it.bucketIndex })
    assertEquals(listOf("Seashore / Mangrove Swamp", "Garden / Rice Field"), byId.getValue("g4_t1_l10_q05").buckets)
    assertEquals(listOf(0, 1, 2), byId.getValue("g4_t2_l12_q18").cards.map { it.bucketIndex })
    assertEquals(listOf(0, 1, 2), byId.getValue("g4_t2_l13_q05").cards.map { it.bucketIndex })
    assertEquals(listOf(0, 0, 1), byId.getValue("g5_t1_l21_q05").cards.map { it.bucketIndex })
    assertEquals(listOf(0, 1), byId.getValue("g5_t2_l28_q03").correctIndices)
    assertEquals(listOf(2), byId.getValue("g6_t1_l36_q13").correctIndices)
    assertEquals(listOf(0, 0), byId.getValue("g6_t1_l41_q05").cards.map { it.bucketIndex })
    assertEquals(listOf(0, 0, 0, 0), byId.getValue("g6_t2_l43_q05").cards.map { it.bucketIndex })
    assertEquals(listOf(0, 0), byId.getValue("g6_t3_l52_q05").cards.map { it.bucketIndex })

    // Verify new Grade 3 Term 2 Week 11 bidirectional slider starts at 0 and accepts +30 cm
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = ScienceRepository(CurriculumLoader(context), db.progressDao())
      val vm = MainViewModel(repo)
      val collector = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }
      waitUntil { !vm.uiState.value.isLoading }

      val g3PositionLesson = lessons.first { it.id == "g3_t2_l05" }
      val sliderQ = g3PositionLesson.questions[17]
      assertEquals("g3_t2_l05_q18", sliderQ.id)
      vm.startLesson(g3PositionLesson.copy(questions = listOf(sliderQ)))
      assertEquals(0f, vm.sessionState.value!!.sliderValue)
      vm.onSliderChange(30f)
      vm.submitAnswer()
      assertTrue(vm.sessionState.value!!.feedback!!.isCorrect)
      collector.cancel()
    } finally {
      db.close()
    }
  }

  @Test
  fun `offline mode disables AI features with warning while allowing offline activities`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = ScienceRepository(CurriculumLoader(context), db.progressDao())
      repo.ensureInitialStats()
      val fakeConnectivity = object : ConnectivityObserver {
        private val state = MutableStateFlow(false)
        override val isOnline: StateFlow<Boolean> = state
        override fun checkOnlineNow(): Boolean = state.value
      }
      var aiCalled = false
      val fakeAiService = object : AiTutorService {
        override fun streamTutorExplanation(
          userMessage: String,
          language: TutorLanguage,
          questionContext: QuestionTutorContext?,
          recentHistory: List<Pair<Boolean, String>>,
        ): Flow<String> {
          aiCalled = true
          return flowOf("Should not run offline")
        }

        override suspend fun generateScienceGame(
          topic: String,
          gradeLevel: Int,
          language: TutorLanguage,
          gameType: AiGameType,
        ): GeneratedScienceGame {
          aiCalled = true
          error("Should not run offline")
        }

        override suspend fun startVoiceConversation(
          language: TutorLanguage,
          questionContext: QuestionTutorContext?,
          onTranscription: (String?, String?) -> Unit,
        ): String {
          aiCalled = true
          return "gemini-3.1-flash-live-preview"
        }

        override suspend fun sendTextToVoiceSession(text: String) {}
        override suspend fun stopVoiceConversation() {}
      }

      val aiVm = AiAssistantViewModel(repo, fakeAiService, fakeConnectivity)
      val aiCollector = launch(Dispatchers.Unconfined) { aiVm.uiState.collect {} }
      assertFalse(aiVm.uiState.value.isOnline)

      // Attempt AI chat, voice, and game generation offline -> all blocked with warning
      aiVm.sendTutorMessage("Ipaathag sa Kinaray-a")
      waitUntil { aiVm.uiState.value.tutorError == OFFLINE_AI_WARNING }
      assertEquals(OFFLINE_AI_WARNING, aiVm.uiState.value.tutorError)

      aiVm.startVoiceConversation()
      waitUntil { aiVm.uiState.value.liveVoiceState is LiveVoiceState.Error }
      assertEquals(OFFLINE_AI_WARNING, (aiVm.uiState.value.liveVoiceState as LiveVoiceState.Error).message)

      aiVm.generateGame("Matter", 3, TutorLanguage.TAGALOG, AiGameType.ARCADE_CATCHER)
      waitUntil { aiVm.uiState.value.gameGeneratorState is GameGeneratorState.Error }
      assertEquals(OFFLINE_AI_WARNING, (aiVm.uiState.value.gameGeneratorState as GameGeneratorState.Error).message)
      assertFalse(aiCalled)

      // Offline curriculum activity proceeds uninterrupted
      val mainVm = MainViewModel(repo)
      val mainCollector = launch(Dispatchers.Unconfined) { mainVm.uiState.collect {} }
      waitUntil { !mainVm.uiState.value.isLoading }
      mainVm.startLesson(sampleLesson(questionCount = 2))
      mainVm.onSelectSingle(1)
      mainVm.submitAnswer()
      waitUntil { mainVm.sessionState.value?.feedback != null }
      assertTrue(mainVm.sessionState.value!!.feedback!!.isCorrect)

      aiCollector.cancel()
      mainCollector.cancel()
    } finally {
      db.close()
    }
  }

  @Test
  fun `online AI tutor passes multilingual language and question context and logs generated game XP`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = ScienceRepository(CurriculumLoader(context), db.progressDao())
      repo.ensureInitialStats()
      val fakeConnectivity = object : ConnectivityObserver {
        private val state = MutableStateFlow(true)
        override val isOnline: StateFlow<Boolean> = state
        override fun checkOnlineNow(): Boolean = state.value
      }

      var capturedLang: TutorLanguage? = null
      var capturedContext: QuestionTutorContext? = null
      val fakeAiService = object : AiTutorService {
        override fun streamTutorExplanation(
          userMessage: String,
          language: TutorLanguage,
          questionContext: QuestionTutorContext?,
          recentHistory: List<Pair<Boolean, String>>,
        ): Flow<String> {
          capturedLang = language
          capturedContext = questionContext
          return flowOf("Amo dya ang clue sa pamangkot...")
        }

        override suspend fun generateScienceGame(
          topic: String,
          gradeLevel: Int,
          language: TutorLanguage,
          gameType: AiGameType,
        ): GeneratedScienceGame {
          return GeneratedScienceGame(
            title = "Volcano Comet Lab",
            topic = topic,
            gradeLevel = gradeLevel,
            language = language,
            gameType = gameType,
            missionBrief = "Test mission",
            rounds = listOf(
              GameRoundItem("Sample?", listOf("A", "B", "C", "D"), 0, "Hint", "Explanation")
            ),
          )
        }

        override suspend fun startVoiceConversation(
          language: TutorLanguage,
          questionContext: QuestionTutorContext?,
          onTranscription: (String?, String?) -> Unit,
        ): String {
          capturedLang = language
          capturedContext = questionContext
          return "gemini-3.1-flash-live-preview"
        }

        override suspend fun sendTextToVoiceSession(text: String) {}
        override suspend fun stopVoiceConversation() {}
      }

      val aiVm = AiAssistantViewModel(repo, fakeAiService, fakeConnectivity)
      val aiCollector = launch(Dispatchers.Unconfined) { aiVm.uiState.collect {} }

      val q = sampleLesson().questions.first()
      val qCtx = buildQuestionTutorContext(5, "Matter", "Mixtures", q)
      aiVm.setLanguage(TutorLanguage.KINARAY_A)
      aiVm.openTutor(qCtx)
      aiVm.sendTutorMessage("Ipaathag sa Kinaray-a")
      waitUntil { !aiVm.uiState.value.isSendingText && aiVm.uiState.value.messages.any { !it.isUser && it.text.contains("clue") } }

      assertEquals(TutorLanguage.KINARAY_A, capturedLang)
      assertEquals(q.id, capturedContext?.questionId)

      aiVm.generateGame("Volcanoes", 6, TutorLanguage.HILIGAYNON, AiGameType.ARCADE_CATCHER)
      waitUntil { aiVm.uiState.value.gameGeneratorState is GameGeneratorState.Ready }
      val game = (aiVm.uiState.value.gameGeneratorState as GameGeneratorState.Ready).game
      val learner = repo.observeLearnerStats().first()
      aiVm.recordGeneratedGameCompletion(learner.id, game, correctCount = 5, totalCount = 6, xpEarned = 95)
      waitUntil {
        runBlocking {
          repo.observeActivityLogs().first().isNotEmpty() &&
            repo.observeLearnerStats().first().totalXp == 95
        }
      }
      assertEquals(95, repo.observeLearnerStats().first().totalXp)

      aiCollector.cancel()
    } finally {
      db.close()
    }
  }

  @Test
  fun `firebase app and app check initialize cleanly from google services resources`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val app = ensureFirebaseAppInitialized(context)
    assertNotNull(app)
    assertTrue(FirebaseApp.getApps(context).isNotEmpty())
    val recaptchaId = context.resources.getIdentifier("recaptcha_site_key", "string", context.packageName)
    assertTrue("recaptcha_site_key resource must exist for release App Check", recaptchaId != 0)
    assertTrue(context.getString(recaptchaId).isNotBlank())
    assertNull(com.example.util.appCheckConfigIssue(context))
    configureAppCheck(context, null)
    val ai = Firebase.ai(app = app!!, backend = GenerativeBackend.googleAI())
    assertNotNull(ai)
  }
}
