package com.example.data.model

enum class TutorLanguage(
  val displayName: String,
  val nativeBadge: String,
  val promptInstruction: String,
) {
  AUTO(
    displayName = "Auto",
    nativeBadge = "EN • Tagalog • Hiligaynon • Kinaray-a",
    promptInstruction =
      "Listen and read carefully. Understand English, Tagalog (Filipino), Hiligaynon (Ilonggo), and Kinaray-a (especially Antique/Culasi Kinaray-a). " +
        "Automatically match whichever language the learner uses (or code-switch gently with simple Filipino/Hiligaynon/Kinaray-a explanations if they ask in mixed language).",
  ),
  TAGALOG(
    displayName = "Tagalog",
    nativeBadge = "Filipino / Tagalog",
    promptInstruction =
      "RESPOND IN TAGALOG (FILIPINO). Understand Tagalog, English, Hiligaynon, and Kinaray-a, and explain clearly in friendly, encouraging Grade 3–6 Tagalog with relatable Filipino everyday examples.",
  ),
  HILIGAYNON(
    displayName = "Hiligaynon",
    nativeBadge = "Ilonggo / Hiligaynon",
    promptInstruction =
      "RESPOND IN HILIGAYNON (ILONGGO). Understand Hiligaynon, Kinaray-a, Tagalog, and English, and explain clearly in warm, natural, easy-to-understand Hiligaynon for Grade 3–6 learners.",
  ),
  KINARAY_A(
    displayName = "Kinaray-a",
    nativeBadge = "Kinaray-a (Antique)",
    promptInstruction =
      "RESPOND IN KINARAY-A (such as spoken in Culasi, Antique). Understand Kinaray-a, Hiligaynon, Tagalog, and English, and reply in natural, warm, kid-friendly Kinaray-a with simple local examples.",
  ),
  ENGLISH(
    displayName = "English",
    nativeBadge = "Simple English",
    promptInstruction =
      "RESPOND IN SIMPLE, ENCOURAGING ENGLISH suitable for Grade 3–6 learners, while still understanding any Tagalog, Hiligaynon, or Kinaray-a questions they ask.",
  ),
}

data class QuestionTutorContext(
  val questionId: String,
  val grade: Int,
  val lessonTitle: String,
  val contentTopic: String,
  val questionTypeLabel: String,
  val prompt: String,
  val choicesSummary: String,
)

data class TutorChatMessage(
  val id: String,
  val isUser: Boolean,
  val text: String,
  val isVoiceTranscript: Boolean = false,
  val isStreaming: Boolean = false,
)

sealed interface LiveVoiceState {
  data object Idle : LiveVoiceState
  data object Connecting : LiveVoiceState
  data class Active(
    val modelName: String,
    val userTranscript: String = "",
    val modelTranscript: String = "",
  ) : LiveVoiceState
  data class Error(val message: String) : LiveVoiceState
}

enum class AiGameType(
  val title: String,
  val subtitle: String,
  val badge: String,
) {
  ARCADE_CATCHER(
    title = "Science Comet Catcher",
    subtitle = "Tap the matching Science comet orb before time runs out!",
    badge = "Arcade Lab",
  ),
  MEMORY_FLIP(
    title = "Concept Match Flip",
    subtitle = "Flip cards and pair Science concepts in your chosen language!",
    badge = "Memory Board",
  ),
  LAB_QUEST(
    title = "Lab Explorer Quest",
    subtitle = "Solve Science clues to clear each mystery lab stage!",
    badge = "Adventure",
  ),
}

data class GameRoundItem(
  val question: String,
  val options: List<String>,
  val correctIndex: Int,
  val hint: String,
  val explanation: String,
)

data class GameMemoryPair(
  val term: String,
  val matchMeaning: String,
  val clue: String,
)

data class GeneratedScienceGame(
  val title: String,
  val topic: String,
  val gradeLevel: Int,
  val language: TutorLanguage,
  val gameType: AiGameType,
  val missionBrief: String,
  val rounds: List<GameRoundItem> = emptyList(),
  val memoryPairs: List<GameMemoryPair> = emptyList(),
)

sealed interface GameGeneratorState {
  data object Idle : GameGeneratorState
  data object Generating : GameGeneratorState
  data class Ready(val game: GeneratedScienceGame) : GameGeneratorState
  data class Error(val message: String) : GameGeneratorState
}
