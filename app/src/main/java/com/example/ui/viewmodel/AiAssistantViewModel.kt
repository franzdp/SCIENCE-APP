package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.AiGameType
import com.example.data.model.GameGeneratorState
import com.example.data.model.GeneratedScienceGame
import com.example.data.model.LiveVoiceState
import com.example.data.model.QuestionType
import com.example.data.model.QuestionTutorContext
import com.example.data.model.ScienceQuestion
import com.example.data.model.TutorChatMessage
import com.example.data.model.TutorLanguage
import com.example.data.repository.AiTutorService
import com.example.data.repository.OFFLINE_AI_WARNING
import com.example.data.repository.ScienceRepository
import com.example.util.ConnectivityObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "AiAssistantViewModel"

data class AiAssistantUiState(
  val isOnline: Boolean = true,
  val selectedLanguage: TutorLanguage = TutorLanguage.AUTO,
  val questionContext: QuestionTutorContext? = null,
  val messages: List<TutorChatMessage> = emptyList(),
  val isSendingText: Boolean = false,
  val tutorError: String? = null,
  val liveVoiceState: LiveVoiceState = LiveVoiceState.Idle,
  val gameGeneratorState: GameGeneratorState = GameGeneratorState.Idle,
)

class AiAssistantViewModel(
  private val scienceRepository: ScienceRepository,
  private val aiService: AiTutorService,
  private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

  private val _selectedLanguage = MutableStateFlow(TutorLanguage.AUTO)
  private val _questionContext = MutableStateFlow<QuestionTutorContext?>(null)
  private val _messages = MutableStateFlow<List<TutorChatMessage>>(emptyList())
  private val _isSendingText = MutableStateFlow(false)
  private val _tutorError = MutableStateFlow<String?>(null)
  private val _liveVoiceState = MutableStateFlow<LiveVoiceState>(LiveVoiceState.Idle)
  private val _gameGeneratorState = MutableStateFlow<GameGeneratorState>(GameGeneratorState.Idle)

  private var textStreamJob: Job? = null
  private var voiceJob: Job? = null
  private var gameJob: Job? = null

  private val _part1 = combine(
    connectivityObserver.isOnline,
    _selectedLanguage,
    _questionContext,
    _messages,
  ) { online, lang, ctx, msgs ->
    Quad(online, lang, ctx, msgs)
  }

  val uiState: StateFlow<AiAssistantUiState> = combine(
    _part1,
    _isSendingText,
    _tutorError,
    _liveVoiceState,
    _gameGeneratorState,
  ) { q, sending, err, voice, game ->
    AiAssistantUiState(
      isOnline = q.first,
      selectedLanguage = q.second,
      questionContext = q.third,
      messages = q.fourth,
      isSendingText = sending,
      tutorError = err,
      liveVoiceState = voice,
      gameGeneratorState = game,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = AiAssistantUiState(isOnline = connectivityObserver.isOnline.value),
  )

  fun refreshConnectivity(): Boolean = connectivityObserver.checkOnlineNow()

  fun setLanguage(language: TutorLanguage) {
    _selectedLanguage.value = language
  }

  fun openTutor(questionContext: QuestionTutorContext?) {
    val previousId = _questionContext.value?.questionId
    val newId = questionContext?.questionId
    connectivityObserver.checkOnlineNow()
    _tutorError.value = null

    if (previousId != newId || _messages.value.isEmpty()) {
      _questionContext.value = questionContext
      _messages.value = if (questionContext != null) {
        listOf(
          TutorChatMessage(
            id = "welcome_${questionContext.questionId}",
            isUser = false,
            text = "Hi! Ask me about this Grade ${questionContext.grade} question in English, Tagalog, Hiligaynon, or Kinaray-a. I will explain the concept and give clues without spoiling your answer!",
          )
        )
      } else {
        listOf(
          TutorChatMessage(
            id = "welcome_general",
            isUser = false,
            text = "Maayong adlaw / Magandang araw! Talk or type any Science question in English, Tagalog, Hiligaynon, or Kinaray-a.",
          )
        )
      }
    }
  }

  fun sendTutorMessage(rawText: String) {
    val trimmed = rawText.trim()
    if (trimmed.isBlank()) return

    if (!connectivityObserver.checkOnlineNow()) {
      _tutorError.value = OFFLINE_AI_WARNING
      return
    }

    textStreamJob?.cancel()
    _tutorError.value = null
    _isSendingText.value = true

    val userMsg = TutorChatMessage(
      id = UUID.randomUUID().toString(),
      isUser = true,
      text = trimmed,
    )
    val aiMsgId = UUID.randomUUID().toString()
    val historyBefore = _messages.value
      .filter { !it.id.startsWith("welcome_") && it.text.isNotBlank() }
      .map { it.isUser to it.text }

    _messages.value = _messages.value + userMsg + TutorChatMessage(
      id = aiMsgId,
      isUser = false,
      text = "",
      isStreaming = true,
    )

    val language = _selectedLanguage.value
    val ctx = _questionContext.value

    textStreamJob = viewModelScope.launch {
      try {
        aiService.streamTutorExplanation(
          userMessage = trimmed,
          language = language,
          questionContext = ctx,
          recentHistory = historyBefore,
        ).collect { partial ->
          _messages.value = _messages.value.map { msg ->
            if (msg.id == aiMsgId) msg.copy(text = partial, isStreaming = true) else msg
          }
        }
        _messages.value = _messages.value.map { msg ->
          if (msg.id == aiMsgId) msg.copy(isStreaming = false) else msg
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Tutor message failed", e)
        val errText = e.localizedMessage ?: "Unable to reach AI Tutor right now."
        _tutorError.value = errText
        _messages.value = _messages.value.filterNot { it.id == aiMsgId && it.text.isBlank() }
          .map { if (it.id == aiMsgId) it.copy(isStreaming = false) else it }
      } finally {
        _isSendingText.value = false
      }
    }
  }

  fun startVoiceConversation() {
    if (!connectivityObserver.checkOnlineNow()) {
      _liveVoiceState.value = LiveVoiceState.Error(OFFLINE_AI_WARNING)
      return
    }

    voiceJob?.cancel()
    _tutorError.value = null
    _liveVoiceState.value = LiveVoiceState.Connecting

    val language = _selectedLanguage.value
    val ctx = _questionContext.value

    voiceJob = viewModelScope.launch {
      try {
        val modelUsed = aiService.startVoiceConversation(
          language = language,
          questionContext = ctx,
          onTranscription = { inputChunk, outputChunk ->
            handleVoiceTranscription(inputChunk, outputChunk)
          },
        )
        _liveVoiceState.value = LiveVoiceState.Active(modelName = modelUsed)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start voice conversation", e)
        _liveVoiceState.value = LiveVoiceState.Error(
          e.localizedMessage ?: "Could not start live voice conversation."
        )
      }
    }
  }

  private fun handleVoiceTranscription(inputChunk: String?, outputChunk: String?) {
    val cur = _liveVoiceState.value
    if (cur !is LiveVoiceState.Active) return

    var newUser = cur.userTranscript
    var newModel = cur.modelTranscript

    if (!inputChunk.isNullOrBlank()) {
      newUser = if (newUser.isBlank()) inputChunk.trim() else "$newUser ${inputChunk.trim()}"
    }
    if (!outputChunk.isNullOrBlank()) {
      // Flush previous completed user utterance to chat log when model starts replying
      if (newUser.isNotBlank() && cur.modelTranscript.isBlank()) {
        appendVoiceBubble(isUser = true, text = newUser)
        newUser = ""
      }
      newModel = if (newModel.isBlank()) outputChunk.trim() else "$newModel ${outputChunk.trim()}"
    }
    _liveVoiceState.value = cur.copy(
      userTranscript = newUser,
      modelTranscript = newModel,
    )
  }

  private fun appendVoiceBubble(isUser: Boolean, text: String) {
    val cleaned = text.trim()
    if (cleaned.isBlank()) return
    _messages.value = _messages.value + TutorChatMessage(
      id = UUID.randomUUID().toString(),
      isUser = isUser,
      text = cleaned,
      isVoiceTranscript = true,
    )
  }

  fun stopVoiceConversation() {
    voiceJob?.cancel()
    val cur = _liveVoiceState.value
    if (cur is LiveVoiceState.Active) {
      if (cur.userTranscript.isNotBlank()) appendVoiceBubble(isUser = true, text = cur.userTranscript)
      if (cur.modelTranscript.isNotBlank()) appendVoiceBubble(isUser = false, text = cur.modelTranscript)
    }
    _liveVoiceState.value = LiveVoiceState.Idle
    viewModelScope.launch {
      aiService.stopVoiceConversation()
    }
  }

  fun generateGame(
    topic: String,
    gradeLevel: Int,
    language: TutorLanguage,
    gameType: AiGameType,
  ) {
    if (!connectivityObserver.checkOnlineNow()) {
      _gameGeneratorState.value = GameGeneratorState.Error(OFFLINE_AI_WARNING)
      return
    }

    gameJob?.cancel()
    _gameGeneratorState.value = GameGeneratorState.Generating

    gameJob = viewModelScope.launch {
      try {
        val game = aiService.generateScienceGame(
          topic = topic,
          gradeLevel = gradeLevel,
          language = language,
          gameType = gameType,
        )
        _gameGeneratorState.value = GameGeneratorState.Ready(game)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Game generation failed", e)
        _gameGeneratorState.value = GameGeneratorState.Error(
          e.localizedMessage ?: "Unable to generate Science game right now."
        )
      }
    }
  }

  fun clearGeneratedGame() {
    gameJob?.cancel()
    _gameGeneratorState.value = GameGeneratorState.Idle
  }

  fun recordGeneratedGameCompletion(
    learnerId: Long,
    game: GeneratedScienceGame,
    correctCount: Int,
    totalCount: Int,
    xpEarned: Int,
  ) {
    viewModelScope.launch {
      runCatching {
        scienceRepository.completeActivitySession(
          learnerId = learnerId,
          lessonId = null,
          correctCount = correctCount,
          totalCount = totalCount.coerceAtLeast(1),
          xpEarned = xpEarned,
          activityTitle = game.title,
          activityCategory = "AI Game (${game.gameType.badge})",
          gradeLevel = game.gradeLevel,
        )
      }.onFailure {
        Log.e(TAG, "Failed to record AI game session", it)
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    textStreamJob?.cancel()
    voiceJob?.cancel()
    gameJob?.cancel()
    connectivityObserver.release()
  }

  private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}

fun buildQuestionTutorContext(
  grade: Int,
  lessonTitle: String,
  contentTopic: String,
  question: ScienceQuestion,
): QuestionTutorContext {
  val typeLabel = when (question.type) {
    QuestionType.MULTIPLE_CHOICE -> "Multiple Choice"
    QuestionType.TRUE_FALSE -> "True or False"
    QuestionType.MULTI_SELECT -> "Select All That Apply"
    QuestionType.MATCHING -> "Matching Type"
    QuestionType.SORT_BUCKETS -> "Sorting Activity"
    QuestionType.ORDER_STEPS -> "Sequence / Order Steps"
    QuestionType.HOTSPOT -> "Interactive Diagram"
    QuestionType.SLIDER -> "Measurement / Ruler"
    QuestionType.SIMULATION -> "Science Simulation"
    QuestionType.FILL_BLANK -> "Complete the Sentence"
  }
  val choices = when (question.type) {
    QuestionType.MULTIPLE_CHOICE ->
      question.options.mapIndexed { idx, opt -> "${'A' + idx}) $opt" }.joinToString(" | ")
    QuestionType.MULTI_SELECT ->
      question.items.mapIndexed { idx, item -> "${'A' + idx}) $item" }.joinToString(" | ")
    QuestionType.SIMULATION ->
      question.choices.mapIndexed { idx, opt -> "${'A' + idx}) $opt" }.joinToString(" | ")
    QuestionType.TRUE_FALSE -> "True | False"
    QuestionType.MATCHING ->
      "Left items: ${question.leftItems.joinToString(", ")} | Right options: ${question.rightItems.joinToString(", ")}"
    QuestionType.SORT_BUCKETS ->
      "Buckets: ${question.buckets.joinToString(", ")} | Cards: ${question.cards.joinToString(", ") { it.text }}"
    QuestionType.ORDER_STEPS ->
      "Steps to arrange: ${question.steps.joinToString(" | ")}"
    QuestionType.HOTSPOT ->
      "Hotspot labels (${question.sceneTitle}): ${question.hotspots.joinToString(", ")}"
    QuestionType.SLIDER ->
      "Measurement range: ${question.minValue} to ${question.maxValue} ${question.unit}"
    QuestionType.FILL_BLANK ->
      "Word bank: ${question.choices.joinToString(", ")}"
  }
  return QuestionTutorContext(
    questionId = question.id,
    grade = grade,
    lessonTitle = lessonTitle,
    contentTopic = contentTopic,
    questionTypeLabel = typeLabel,
    prompt = question.prompt,
    choicesSummary = choices,
  )
}

class AiAssistantViewModelFactory(
  private val scienceRepository: ScienceRepository,
  private val aiService: AiTutorService,
  private val connectivityObserver: ConnectivityObserver,
) : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(AiAssistantViewModel::class.java)) {
      @Suppress("UNCHECKED_CAST")
      return AiAssistantViewModel(scienceRepository, aiService, connectivityObserver) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}
