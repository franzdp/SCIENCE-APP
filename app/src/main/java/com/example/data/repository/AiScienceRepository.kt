package com.example.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.data.model.AiGameType
import com.example.data.model.GameMemoryPair
import com.example.data.model.GameRoundItem
import com.example.data.model.GeneratedScienceGame
import com.example.data.model.QuestionTutorContext
import com.example.data.model.TutorLanguage
import com.example.util.ConnectivityObserver
import com.example.util.ensureFirebaseAppInitialized
import com.google.firebase.Firebase
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Transcription
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "AiScienceRepository"
private const val GENERAL_MODEL = "gemini-3.8-flash"
private const val PRIMARY_LIVE_MODEL = "gemini-3.1-flash-live-preview"
private const val FALLBACK_LIVE_MODEL_25 = "gemini-2.5-flash-native-audio-preview-12-2025"
private const val FALLBACK_LIVE_MODEL_25_OLD = "gemini-2.5-flash-native-audio-preview-09-2025"

const val OFFLINE_AI_WARNING =
  "Internet connectivity is required to talk with the AI Tutor or generate Science games. You can continue all offline lessons and activities!"

interface AiTutorService {
  fun streamTutorExplanation(
    userMessage: String,
    language: TutorLanguage,
    questionContext: QuestionTutorContext?,
    recentHistory: List<Pair<Boolean, String>>,
  ): Flow<String>

  suspend fun generateScienceGame(
    topic: String,
    gradeLevel: Int,
    language: TutorLanguage,
    gameType: AiGameType,
  ): GeneratedScienceGame

  suspend fun startVoiceConversation(
    language: TutorLanguage,
    questionContext: QuestionTutorContext?,
    onTranscription: (input: String?, output: String?) -> Unit,
  ): String

  suspend fun sendTextToVoiceSession(text: String)

  suspend fun stopVoiceConversation()
}

@OptIn(PublicPreviewAPI::class)
class AiScienceRepository(
  private val appContext: Context,
  private val connectivityObserver: ConnectivityObserver,
) : AiTutorService {

  private fun aiClient(): FirebaseAI {
    val app = ensureFirebaseAppInitialized(appContext)
      ?: throw IllegalStateException(
        "Firebase configuration is missing for this build. Please rebuild or ensure google-services.json is included."
      )
    return Firebase.ai(app = app, backend = GenerativeBackend.googleAI())
  }

  private val textTutorModel by lazy {
    aiClient().generativeModel(
      modelName = GENERAL_MODEL,
      generationConfig = generationConfig {
        temperature = 0.65f
      },
    )
  }

  private val quizGameSchema by lazy {
    Schema.obj(
      properties = mapOf(
        "title" to Schema.string(description = "Fun kid-friendly Science game title"),
        "missionBrief" to Schema.string(description = "Short 1-2 sentence mission briefing for the learner"),
        "rounds" to Schema.array(
          description = "6 engaging Science game rounds appropriate for the grade level",
          items = Schema.obj(
            properties = mapOf(
              "question" to Schema.string(description = "Clear Science challenge prompt"),
              "options" to Schema.array(
                items = Schema.string(),
                description = "Exactly 4 concise answer choices",
              ),
              "correctIndex" to Schema.integer(description = "Zero-based index (0 to 3) of the correct option"),
              "hint" to Schema.string(description = "Helpful clue without spoiling the answer"),
              "explanation" to Schema.string(description = "Brief explanation of why the correct option is right"),
            ),
          ),
        ),
      ),
    )
  }

  private val memoryGameSchema by lazy {
    Schema.obj(
      properties = mapOf(
        "title" to Schema.string(description = "Fun kid-friendly Memory Flip Science game title"),
        "missionBrief" to Schema.string(description = "Short 1-2 sentence mission briefing"),
        "pairs" to Schema.array(
          description = "6 matching Science concept pairs (12 cards total)",
          items = Schema.obj(
            properties = mapOf(
              "term" to Schema.string(description = "Short Science term or object (2-4 words max)"),
              "matchMeaning" to Schema.string(description = "Short matching meaning, function, or example (3-7 words max)"),
              "clue" to Schema.string(description = "Helpful clue connecting term and meaning"),
            ),
          ),
        ),
      ),
    )
  }

  private val quizGameModel by lazy {
    aiClient().generativeModel(
      modelName = GENERAL_MODEL,
      generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = quizGameSchema
        temperature = 0.7f
      },
    )
  }

  private val memoryGameModel by lazy {
    aiClient().generativeModel(
      modelName = GENERAL_MODEL,
      generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = memoryGameSchema
        temperature = 0.7f
      },
    )
  }

  private var activeLiveSession: LiveSession? = null

  override fun streamTutorExplanation(
    userMessage: String,
    language: TutorLanguage,
    questionContext: QuestionTutorContext?,
    recentHistory: List<Pair<Boolean, String>>,
  ): Flow<String> = flow {
    if (!connectivityObserver.checkOnlineNow()) {
      throw IllegalStateException(OFFLINE_AI_WARNING)
    }

    val systemPrompt = buildTutorSystemInstruction(language, questionContext)
    val modelWithInstruction = aiClient().generativeModel(
      modelName = GENERAL_MODEL,
      generationConfig = generationConfig { temperature = 0.65f },
      systemInstruction = content { text(systemPrompt) },
    )

    val historyContents = recentHistory.takeLast(6).map { (isUser, msg) ->
      content(role = if (isUser) "user" else "model") { text(msg) }
    }
    val chat = modelWithInstruction.startChat(history = historyContents)

    val accumulated = StringBuilder()
    try {
      withTimeout(60_000L) {
        chat.sendMessageStream(userMessage).collect { chunk ->
          val part = chunk.text.orEmpty()
          if (part.isNotEmpty()) {
            accumulated.append(part)
            emit(accumulated.toString())
          }
        }
      }
      if (accumulated.isBlank()) {
        throw IllegalStateException("The AI Tutor returned an empty response. Please try again.")
      }
    } catch (e: TimeoutCancellationException) {
      Log.e(TAG, "Tutor streaming timed out", e)
      throw IllegalStateException("The AI Tutor took too long to respond. Please check your connection and try again.")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Tutor streaming failed", e)
      throw enhanceAiException(e)
    }
  }

  override suspend fun generateScienceGame(
    topic: String,
    gradeLevel: Int,
    language: TutorLanguage,
    gameType: AiGameType,
  ): GeneratedScienceGame {
    if (!connectivityObserver.checkOnlineNow()) {
      throw IllegalStateException(OFFLINE_AI_WARNING)
    }

    val cleanTopic = topic.trim().ifBlank { "General Grade $gradeLevel Science" }
    val langTarget = when (language) {
      TutorLanguage.AUTO -> "English (with helpful Tagalog, Hiligaynon, or Kinaray-a flavor only if appropriate)"
      TutorLanguage.TAGALOG -> "Tagalog (Filipino)"
      TutorLanguage.HILIGAYNON -> "Hiligaynon (Ilonggo)"
      TutorLanguage.KINARAY_A -> "Kinaray-a (Antique)"
      TutorLanguage.ENGLISH -> "Simple English"
    }

    return try {
      withTimeout(60_000L) {
        if (gameType == AiGameType.MEMORY_FLIP) {
          val prompt = """
            Create a Grade $gradeLevel Science Memory Card Matching Game about "$cleanTopic" for Bagacay Elementary School learners.
            Write all game text (title, missionBrief, terms, matchMeanings, and clues) in $langTarget.
            Generate exactly 6 distinct, scientifically accurate pairs suitable for Grade $gradeLevel learners.
            Keep each "term" short (1-4 words) and each "matchMeaning" concise (3-8 words) so cards remain readable on mobile.
          """.trimIndent()

          val response = memoryGameModel.generateContent(prompt)
          val rawJson = response.text ?: throw IllegalStateException("Model returned empty game data.")
          val json = JSONObject(rawJson)
          val pairsArr = json.getJSONArray("pairs")
          val pairs = (0 until pairsArr.length()).map { idx ->
            val obj = pairsArr.getJSONObject(idx)
            GameMemoryPair(
              term = obj.getString("term"),
              matchMeaning = obj.getString("matchMeaning"),
              clue = obj.getString("clue"),
            )
          }.take(6)

          if (pairs.size < 4) {
            throw IllegalStateException("Generated game had too few pairs. Please try again.")
          }

          GeneratedScienceGame(
            title = json.getString("title"),
            topic = cleanTopic,
            gradeLevel = gradeLevel,
            language = language,
            gameType = gameType,
            missionBrief = json.getString("missionBrief"),
            memoryPairs = pairs,
          )
        } else {
          val modeLabel = if (gameType == AiGameType.ARCADE_CATCHER) {
            "fast-paced Comet Catcher arcade quiz (short punchy questions and short 1-4 word options)"
          } else {
            "Lab Explorer Quest adventure (clear scenario questions with 4 distinct options)"
          }
          val prompt = """
            Create a Grade $gradeLevel Science $modeLabel about "$cleanTopic" for Bagacay Elementary School learners.
            Write all game text (title, missionBrief, questions, options, hints, and explanations) in $langTarget.
            Generate exactly 6 scientifically accurate rounds with 4 choices per round.
            Ensure correctIndex is strictly 0, 1, 2, or 3 matching the right option.
          """.trimIndent()

          val response = quizGameModel.generateContent(prompt)
          val rawJson = response.text ?: throw IllegalStateException("Model returned empty game data.")
          val json = JSONObject(rawJson)
          val roundsArr = json.getJSONArray("rounds")
          val rounds = (0 until roundsArr.length()).mapNotNull { idx ->
            val obj = roundsArr.getJSONObject(idx)
            val optsArr = obj.getJSONArray("options")
            val opts = (0 until optsArr.length()).map { oIdx -> optsArr.getString(oIdx) }
            if (opts.size < 2) return@mapNotNull null
            val safeIdx = obj.optInt("correctIndex", 0).coerceIn(0, opts.lastIndex)
            GameRoundItem(
              question = obj.getString("question"),
              options = opts,
              correctIndex = safeIdx,
              hint = obj.getString("hint"),
              explanation = obj.getString("explanation"),
            )
          }.take(6)

          if (rounds.isEmpty()) {
            throw IllegalStateException("Generated game had no valid rounds. Please try again.")
          }

          GeneratedScienceGame(
            title = json.getString("title"),
            topic = cleanTopic,
            gradeLevel = gradeLevel,
            language = language,
            gameType = gameType,
            missionBrief = json.getString("missionBrief"),
            rounds = rounds,
          )
        }
      }
    } catch (e: TimeoutCancellationException) {
      Log.e(TAG, "Game generation timed out", e)
      throw IllegalStateException("Game generation timed out after 60 seconds. Please try again.")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Failed to generate Science game", e)
      throw enhanceAiException(e)
    }
  }

  @SuppressLint("MissingPermission")
  override suspend fun startVoiceConversation(
    language: TutorLanguage,
    questionContext: QuestionTutorContext?,
    onTranscription: (input: String?, output: String?) -> Unit,
  ): String {
    if (!connectivityObserver.checkOnlineNow()) {
      throw IllegalStateException(OFFLINE_AI_WARNING)
    }

    stopVoiceConversation()

    val systemInstructionContent: Content = content {
      text(buildVoiceSystemInstruction(language, questionContext))
    }

    val candidates = listOf(
      PRIMARY_LIVE_MODEL,
      FALLBACK_LIVE_MODEL_25,
      FALLBACK_LIVE_MODEL_25_OLD,
    )

    var lastError: Exception? = null
    for (modelName in candidates) {
      var session: LiveSession? = null
      try {
        val liveModel = aiClient().liveModel(
          modelName = modelName,
          generationConfig = liveGenerationConfig {
            responseModality = ResponseModality.AUDIO
            speechConfig = SpeechConfig(voice = Voice("Aoede"))
            inputAudioTranscription = AudioTranscriptionConfig()
            outputAudioTranscription = AudioTranscriptionConfig()
          },
          systemInstruction = systemInstructionContent,
        )

        session = withTimeout(30_000L) {
          liveModel.connect()
        }

        activeLiveSession = session
        session.startAudioConversation(
          functionCallHandler = null,
          transcriptHandler = { input: Transcription?, output: Transcription? ->
            onTranscription(input?.text, output?.text)
          },
          enableInterruptions = true,
        )

        // Kick off a brief spoken welcome or context confirmation so the learner knows the tutor is ready
        val greetingPrompt = if (questionContext != null) {
          "Briefly greet the learner warmly in 1-2 short sentences and let them know you can help explain this Grade ${questionContext.grade} Science question about ${questionContext.contentTopic} in English, Tagalog, Hiligaynon, or Kinaray-a without giving away the answer right away. Invite their question."
        } else {
          "Briefly greet the Bagacay Elementary School learner warmly in 1-2 short sentences and ask what Science topic they want to talk about in English, Tagalog, Hiligaynon, or Kinaray-a."
        }
        runCatching {
          session.send(
            content = content("user") { text(greetingPrompt) },
            turnComplete = true,
          )
        }
        return modelName
      } catch (e: CancellationException) {
        runCatching { session?.close() }
        throw e
      } catch (e: Exception) {
        runCatching { session?.close() }
        activeLiveSession = null
        lastError = e
        Log.w(TAG, "Live API connection failed for $modelName: ${e.message}", e)
        val msg = (e.message ?: "").lowercase()
        val isAuthOrAppCheckFatal =
          msg.contains("app check") ||
            msg.contains("403") ||
            msg.contains("permission")
        if (isAuthOrAppCheckFatal || modelName == candidates.last()) {
          Log.e(TAG, "Aborting Live API connection", e)
          throw enhanceAiException(e)
        }
      }
    }

    throw enhanceAiException(lastError ?: IllegalStateException("Unable to start live voice conversation."))
  }

  private fun enhanceAiException(e: Exception): Exception {
    val msg = e.message.orEmpty()
    val configIssue = com.example.util.appCheckConfigIssue(appContext)
    if (msg.contains("app check", ignoreCase = true) || msg.contains("403") || configIssue != null) {
      val detail = listOfNotNull(msg.takeIf { it.isNotBlank() }, configIssue).joinToString(" | ")
      return IllegalStateException(
        "Firebase App Check rejected this build ($detail). For a sideloaded release APK, verify app/src/main/res/values/firebase_applet_config.xml is included and register reCAPTCHA Enterprise under App Check in Firebase Console for com.aistudio.aralscience.k7m4px (or test the AI Studio debug preview).",
        e,
      )
    }
    if (msg.contains("closed by the server", ignoreCase = true)) {
      return IllegalStateException(
        "Live voice channel was closed by the server ($msg). Check internet connectivity and ensure Firebase App Check (reCAPTCHA Enterprise for release APK) is enabled for this app.",
        e,
      )
    }
    return e
  }

  override suspend fun sendTextToVoiceSession(text: String) {
    if (!connectivityObserver.checkOnlineNow()) {
      throw IllegalStateException(OFFLINE_AI_WARNING)
    }
    val session = activeLiveSession ?: return
    session.send(
      content = content("user") { text(text) },
      turnComplete = true,
    )
  }

  override suspend fun stopVoiceConversation() {
    val session = activeLiveSession ?: return
    activeLiveSession = null
    runCatching { session.stopAudioConversation() }
      .onFailure { Log.w(TAG, "stopAudioConversation warning: ${it.message}") }
    runCatching { session.close() }
      .onFailure { Log.w(TAG, "LiveSession close warning: ${it.message}") }
  }

  private fun buildTutorSystemInstruction(
    language: TutorLanguage,
    questionContext: QuestionTutorContext?,
  ): String {
    val base = StringBuilder().apply {
      appendLine("You are Teacher Kiko, a warm, patient, and encouraging Science Tutor for Grade 3 to Grade 6 learners at Bagacay Elementary School (Culasi, Antique, Philippines).")
      appendLine("MULTILINGUAL REQUIREMENT:")
      appendLine("- You fluently understand AND speak/write Tagalog (Filipino), Hiligaynon (Ilonggo), Kinaray-a (Antique/Culasi dialect), and English.")
      appendLine("- Language preference: ${language.promptInstruction}")
      appendLine("- Examples of natural greetings/phrases if speaking Kinaray-a: 'Maayong adlaw!', 'Amo dya...', 'Pamangkot lang!', 'Manami nga pamangkot!'")
      appendLine("- Examples of natural phrases if speaking Hiligaynon: 'Maayong adlaw!', 'Amo ini...', 'Pamangkot lang!', 'Nami gid nga pamangkot!'")
      appendLine("- Examples of natural phrases if speaking Tagalog: 'Magandang araw!', 'Ganito iyan...', 'Magaling na tanong!'")
      appendLine("- Keep explanations clear, age-appropriate (Grades 3-6), concise (2 to 5 short sentences unless they ask for more), and relatable to everyday Filipino life.")
    }

    if (questionContext != null) {
      base.appendLine()
      base.appendLine("ACTIVE QUESTION CONTEXT (the learner is about to answer this item):")
      base.appendLine("- Grade: Grade ${questionContext.grade}")
      base.appendLine("- Lesson: ${questionContext.lessonTitle} (${questionContext.contentTopic})")
      base.appendLine("- Activity Type: ${questionContext.questionTypeLabel}")
      base.appendLine("- Question: ${questionContext.prompt}")
      if (questionContext.choicesSummary.isNotBlank()) {
        base.appendLine("- Shown Options/Items: ${questionContext.choicesSummary}")
      }
      base.appendLine()
      base.appendLine("TUTORING RULE FOR ACTIVE QUESTION:")
      base.appendLine("- Explain what the question is asking and clarify any unfamiliar English Science terms in the learner's chosen language (Tagalog, Hiligaynon, Kinaray-a, or English).")
      base.appendLine("- Give a clear concept explanation, real-world clue, or step-by-step reasoning so the learner truly understands.")
      base.appendLine("- DO NOT immediately tell them the final answer letter/choice right away; help them think through it first!")
    }
    return base.toString()
  }

  private fun buildVoiceSystemInstruction(
    language: TutorLanguage,
    questionContext: QuestionTutorContext?,
  ): String {
    return buildTutorSystemInstruction(language, questionContext) + "\n" +
      "VOICE CONVERSATION STYLE: Speak naturally, clearly, and warmly at a kid-friendly pace. Keep spoken replies focused (under 30 seconds) so the learner can respond easily by voice."
  }
}
