package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.data.model.AiGameType
import com.example.data.model.GameRoundItem
import com.example.data.model.GeneratedScienceGame
import com.example.ui.theme.CoralError
import com.example.ui.theme.LeafSecondary
import com.example.ui.theme.SkyPrimary
import com.example.ui.theme.SunGold
import com.example.ui.theme.VioletAccent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

private enum class GameShellStage {
  TITLE,
  PLAYING,
  PAUSED,
  FINISHED,
}

@Composable
fun AiGameScreen(
  game: GeneratedScienceGame,
  onRecordResult: (correctCount: Int, totalCount: Int, xpEarned: Int) -> Unit,
  onExit: () -> Unit,
) {
  var stage by remember(game) { mutableStateOf(GameShellStage.TITLE) }
  var finalCorrect by remember(game) { mutableIntStateOf(0) }
  var finalTotal by remember(game) { mutableIntStateOf(1) }
  var finalScore by remember(game) { mutableIntStateOf(0) }
  var gameRunSeed by remember(game) { mutableIntStateOf(0) }
  var recordedRunSeed by remember(game) { mutableIntStateOf(-1) }

  BackHandler {
    when (stage) {
      GameShellStage.PLAYING -> stage = GameShellStage.PAUSED
      GameShellStage.PAUSED -> stage = GameShellStage.PLAYING
      else -> onExit()
    }
  }

  fun finishGame(correct: Int, total: Int, score: Int) {
    finalCorrect = correct
    finalTotal = total.coerceAtLeast(1)
    finalScore = score
    stage = GameShellStage.FINISHED
    if (recordedRunSeed != gameRunSeed) {
      recordedRunSeed = gameRunSeed
      val xp = (correct * 15 + 20).coerceAtLeast(20)
      onRecordResult(correct, finalTotal, xp)
    }
  }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    containerColor = Color(0xFF0B1120),
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .background(
          Brush.verticalGradient(
            listOf(Color(0xFF0F172A), Color(0xFF1E1B4B), Color(0xFF0B1120))
          )
        ),
      contentAlignment = Alignment.Center,
    ) {
      when (stage) {
        GameShellStage.TITLE -> {
          GameTitleOverlay(
            game = game,
            onStart = { stage = GameShellStage.PLAYING },
            onExit = onExit,
          )
        }

        GameShellStage.PLAYING, GameShellStage.PAUSED -> {
          val isPaused = stage == GameShellStage.PAUSED
          when (game.gameType) {
            AiGameType.MEMORY_FLIP -> {
              MemoryFlipPlayfield(
                game = game,
                runSeed = gameRunSeed,
                isPaused = isPaused,
                onPause = { stage = GameShellStage.PAUSED },
                onComplete = { correct, total, score -> finishGame(correct, total, score) },
              )
            }
            AiGameType.ARCADE_CATCHER -> {
              CometCatcherPlayfield(
                game = game,
                runSeed = gameRunSeed,
                isPaused = isPaused,
                onPause = { stage = GameShellStage.PAUSED },
                onComplete = { correct, total, score -> finishGame(correct, total, score) },
              )
            }
            AiGameType.LAB_QUEST -> {
              LabQuestPlayfield(
                game = game,
                runSeed = gameRunSeed,
                isPaused = isPaused,
                onPause = { stage = GameShellStage.PAUSED },
                onComplete = { correct, total, score -> finishGame(correct, total, score) },
              )
            }
          }

          if (isPaused) {
            AlertDialog(
              onDismissRequest = { stage = GameShellStage.PLAYING },
              title = { Text("Game Paused") },
              text = { Text("Take a breather or resume ${game.title}.") },
              confirmButton = {
                Button(onClick = { stage = GameShellStage.PLAYING }) {
                  Text("Resume")
                }
              },
              dismissButton = {
                OutlinedButton(onClick = onExit) {
                  Text("Exit Game")
                }
              },
            )
          }
        }

        GameShellStage.FINISHED -> {
          GameSummaryOverlay(
            game = game,
            correct = finalCorrect,
            total = finalTotal,
            score = finalScore,
            onReplay = {
              gameRunSeed += 1
              stage = GameShellStage.PLAYING
            },
            onExit = onExit,
          )
        }
      }
    }
  }
}

@Composable
private fun GameTitleOverlay(
  game: GeneratedScienceGame,
  onStart: () -> Unit,
  onExit: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(28.dp),
    color = Color(0xFF1E293B),
    border = BorderStroke(1.5.dp, SkyPrimary.copy(alpha = 0.55f)),
    modifier = Modifier
      .widthIn(max = 500.dp)
      .fillMaxWidth()
      .padding(20.dp),
  ) {
    Column(
      modifier = Modifier.padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = VioletAccent.copy(alpha = 0.22f),
        modifier = Modifier.size(64.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(Icons.Rounded.RocketLaunch, contentDescription = null, tint = SunGold, modifier = Modifier.size(34.dp))
        }
      }
      Text(
        text = "${game.gameType.badge.uppercase()} • GRADE ${game.gradeLevel} • ${game.language.displayName.uppercase()}",
        style = MaterialTheme.typography.labelSmall,
        color = SkyPrimary,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = game.title,
        style = MaterialTheme.typography.headlineSmall,
        color = Color.White,
        textAlign = TextAlign.Center,
      )
      Text(
        text = game.missionBrief,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFCBD5E1),
        textAlign = TextAlign.Center,
      )
      Text(
        text = "Tap answers quickly • Use Hints anytime • Earn Science XP",
        style = MaterialTheme.typography.labelSmall,
        color = SunGold,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(4.dp))
      Button(
        onClick = onStart,
        colors = ButtonDefaults.buttonColors(containerColor = SunGold, contentColor = Color(0xFF422006)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("ai_game_start_button"),
      ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Start Mission", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      }
      OutlinedButton(
        onClick = onExit,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF64748B)),
        modifier = Modifier.fillMaxWidth().height(48.dp),
      ) {
        Text("Back to Challenges", color = Color.White)
      }
    }
  }
}

@Composable
private fun GameHudHeader(
  score: Int,
  combo: Int,
  lives: Int,
  progressLabel: String,
  onPause: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      for (i in 1..3) {
        Icon(
          imageVector = if (i <= lives) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
          contentDescription = null,
          tint = if (i <= lives) CoralError else Color(0xFF64748B),
          modifier = Modifier.size(20.dp),
        )
      }
      Spacer(Modifier.width(6.dp))
      Text(progressLabel, style = MaterialTheme.typography.labelMedium, color = Color(0xFF94A3B8))
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (combo >= 2) {
        Surface(color = SunGold.copy(alpha = 0.2f), shape = RoundedCornerShape(50)) {
          Text(
            text = "${combo}x",
            style = MaterialTheme.typography.labelMedium,
            color = SunGold,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }
      Surface(color = SkyPrimary.copy(alpha = 0.22f), shape = RoundedCornerShape(50)) {
        Text(
          text = "$score pts",
          style = MaterialTheme.typography.labelLarge,
          color = Color.White,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
      IconButton(onClick = onPause, modifier = Modifier.size(44.dp)) {
        Icon(Icons.Rounded.Pause, contentDescription = "Pause game", tint = Color.White)
      }
    }
  }
}

@Composable
private fun CometCatcherPlayfield(
  game: GeneratedScienceGame,
  runSeed: Int,
  isPaused: Boolean,
  onPause: () -> Unit,
  onComplete: (Int, Int, Int) -> Unit,
) {
  val rounds = game.rounds
  var roundIdx by remember(runSeed) { mutableIntStateOf(0) }
  var lives by remember(runSeed) { mutableIntStateOf(3) }
  var score by remember(runSeed) { mutableIntStateOf(0) }
  var combo by remember(runSeed) { mutableIntStateOf(0) }
  var correctCount by remember(runSeed) { mutableIntStateOf(0) }
  var showHint by remember(runSeed, roundIdx) { mutableStateOf(false) }
  var feedbackBanner by remember(runSeed, roundIdx) { mutableStateOf<Pair<Boolean, String>?>(null) }
  var simTime by remember(runSeed) { mutableFloatStateOf(0f) }
  var roundTimer by remember(runSeed, roundIdx) { mutableFloatStateOf(18f) }

  val currentRound: GameRoundItem? = rounds.getOrNull(roundIdx)

  // 60fps Canvas drift + countdown loop (clamped dt)
  LaunchedEffect(runSeed, roundIdx, isPaused, feedbackBanner != null) {
    if (isPaused || feedbackBanner != null || currentRound == null) return@LaunchedEffect
    var lastNanos = 0L
    while (true) {
      withFrameNanos { now ->
        if (lastNanos == 0L) lastNanos = now
        val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastNanos = now
        simTime += dt
        roundTimer = (roundTimer - dt).coerceAtLeast(0f)
      }
      if (roundTimer <= 0f) {
        lives -= 1
        combo = 0
        feedbackBanner = false to "Time up! ${currentRound.explanation}"
        break
      }
    }
  }

  LaunchedEffect(feedbackBanner) {
    if (feedbackBanner != null) {
      delay(1400L)
      val nextIdx = roundIdx + 1
      if (lives <= 0 || nextIdx >= rounds.size) {
        onComplete(correctCount, rounds.size, score)
      } else {
        roundIdx = nextIdx
      }
    }
  }

  if (currentRound == null) {
    onComplete(correctCount, rounds.size.coerceAtLeast(1), score)
    return
  }

  Column(modifier = Modifier.fillMaxSize().widthIn(max = 680.dp)) {
    GameHudHeader(
      score = score,
      combo = combo,
      lives = lives,
      progressLabel = "Wave ${roundIdx + 1}/${rounds.size}",
      onPause = onPause,
    )

    LinearProgressIndicator(
      progress = { (roundTimer / 18f).coerceIn(0f, 1f) },
      color = if (roundTimer < 5f) CoralError else SunGold,
      trackColor = Color(0xFF1E293B),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .height(7.dp)
        .clip(RoundedCornerShape(50)),
    )

    // Question prompt banner
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = Color(0xFF1E293B).copy(alpha = 0.92f),
      border = BorderStroke(1.dp, SkyPrimary.copy(alpha = 0.5f)),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(currentRound.question, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = if (showHint) "Hint: ${currentRound.hint}" else "Catch the right comet orb!",
            style = MaterialTheme.typography.bodySmall,
            color = if (showHint) SunGold else Color(0xFF94A3B8),
            modifier = Modifier.weight(1f),
          )
          Surface(
            onClick = { showHint = !showHint },
            shape = RoundedCornerShape(50),
            color = SunGold.copy(alpha = 0.18f),
          ) {
            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Rounded.Lightbulb, contentDescription = "Hint", tint = SunGold, modifier = Modifier.size(15.dp))
              Spacer(Modifier.width(4.dp))
              Text("Hint", style = MaterialTheme.typography.labelSmall, color = SunGold, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }

    // Animated starfield + orbital comet buttons
    BoxWithConstraints(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
      val fieldWidthPx = constraints.maxWidth.toFloat()
      val fieldHeightPx = constraints.maxHeight.toFloat()

      Canvas(modifier = Modifier.fillMaxSize()) {
        for (i in 0 until 24) {
          val sx = ((i * 73 + simTime * (12f + i % 5 * 6f)) % size.width)
          val sy = ((i * 97) % size.height)
          drawCircle(
            color = Color.White.copy(alpha = 0.25f + (i % 4) * 0.12f),
            radius = (1.5f + (i % 3)),
            center = Offset(sx, sy),
          )
        }
      }

      val cols = 2
      val cellW = fieldWidthPx / cols
      val cellH = fieldHeightPx / 2f
      val accentPalette = listOf(SkyPrimary, LeafSecondary, VioletAccent, SunGold)

      currentRound.options.forEachIndexed { idx, optText ->
        val col = idx % 2
        val row = idx / 2
        val phase = idx * 1.4f + simTime * 1.8f
        val bobX = (cos(phase.toDouble()) * 14.0).toFloat()
        val bobY = (sin((phase * 1.2f).toDouble()) * 16.0).toFloat()
        val xOffset = (col * cellW + cellW * 0.08f + bobX).roundToInt().coerceAtLeast(0)
        val yOffset = (row * cellH + cellH * 0.18f + bobY).roundToInt().coerceAtLeast(0)
        val orbColor = accentPalette[idx % accentPalette.size]

        Surface(
          onClick = {
            if (feedbackBanner != null || isPaused) return@Surface
            val isRight = idx == currentRound.correctIndex
            if (isRight) {
              combo += 1
              correctCount += 1
              score += 100 + combo * 25
              feedbackBanner = true to "Direct Hit! ${currentRound.explanation}"
            } else {
              combo = 0
              lives -= 1
              feedbackBanner = false to "Missed! ${currentRound.explanation}"
            }
          },
          shape = RoundedCornerShape(22.dp),
          color = orbColor.copy(alpha = 0.24f),
          border = BorderStroke(2.dp, orbColor),
          shadowElevation = 6.dp,
          modifier = Modifier
            .offset { IntOffset(xOffset, yOffset) }
            .widthIn(min = 120.dp, max = 175.dp)
            .testTag("comet_option_$idx"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(shape = CircleShape, color = orbColor, modifier = Modifier.size(22.dp)) {
              Box(contentAlignment = Alignment.Center) {
                Text("${'A' + idx}", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
              }
            }
            Spacer(Modifier.width(8.dp))
            Text(
              text = optText,
              style = MaterialTheme.typography.bodySmall,
              color = Color.White,
              fontWeight = FontWeight.SemiBold,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      feedbackBanner?.let { (isHit, msg) ->
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = if (isHit) LeafSecondary else CoralError,
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        ) {
          Text(
            text = msg,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(14.dp),
          )
        }
      }
    }
  }
}

private data class MemoryCardNode(
  val uid: Int,
  val pairIndex: Int,
  val label: String,
  val isTerm: Boolean,
)

@Composable
private fun MemoryFlipPlayfield(
  game: GeneratedScienceGame,
  runSeed: Int,
  isPaused: Boolean,
  onPause: () -> Unit,
  onComplete: (Int, Int, Int) -> Unit,
) {
  val pairs = game.memoryPairs
  val deck = remember(runSeed, pairs) {
    pairs.flatMapIndexed { idx, p ->
      listOf(
        MemoryCardNode(uid = idx * 2, pairIndex = idx, label = p.term, isTerm = true),
        MemoryCardNode(uid = idx * 2 + 1, pairIndex = idx, label = p.matchMeaning, isTerm = false),
      )
    }.shuffled()
  }

  var matchedPairs by remember(runSeed) { mutableStateOf(emptySet<Int>()) }
  var flippedUids by remember(runSeed) { mutableStateOf<List<Int>>(emptyList()) }
  var moves by remember(runSeed) { mutableIntStateOf(0) }
  var score by remember(runSeed) { mutableIntStateOf(0) }
  var lives by remember(runSeed) { mutableIntStateOf(3) }
  var activeClue by remember(runSeed) { mutableStateOf<String?>(null) }

  LaunchedEffect(flippedUids) {
    if (flippedUids.size == 2) {
      val first = deck.firstOrNull { it.uid == flippedUids[0] }
      val second = deck.firstOrNull { it.uid == flippedUids[1] }
      delay(650L)
      if (first != null && second != null && first.pairIndex == second.pairIndex) {
        matchedPairs = matchedPairs + first.pairIndex
        score += 120
        activeClue = "Matched: ${pairs[first.pairIndex].term} — ${pairs[first.pairIndex].clue}"
      } else {
        score = (score - 10).coerceAtLeast(0)
      }
      flippedUids = emptyList()
      if (matchedPairs.size >= pairs.size && pairs.isNotEmpty()) {
        delay(500L)
        onComplete(pairs.size, pairs.size, score + 100)
      }
    }
  }

  Column(modifier = Modifier.fillMaxSize().widthIn(max = 680.dp)) {
    GameHudHeader(
      score = score,
      combo = matchedPairs.size,
      lives = lives,
      progressLabel = "Matched ${matchedPairs.size}/${pairs.size} • Moves $moves",
      onPause = onPause,
    )

    activeClue?.let { clue ->
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = LeafSecondary.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, LeafSecondary),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
      ) {
        Text(
          text = clue,
          style = MaterialTheme.typography.bodySmall,
          color = Color.White,
          modifier = Modifier.padding(10.dp),
        )
      }
    }

    LazyVerticalGrid(
      columns = GridCells.Fixed(3),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .padding(16.dp),
    ) {
      items(deck, key = { it.uid }) { card ->
        val isMatched = card.pairIndex in matchedPairs
        val isFlipped = isMatched || card.uid in flippedUids
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = when {
            isMatched -> LeafSecondary.copy(alpha = 0.28f)
            isFlipped -> SkyPrimary.copy(alpha = 0.35f)
            else -> Color(0xFF1E293B)
          },
          border = BorderStroke(
            1.5.dp,
            when {
              isMatched -> LeafSecondary
              isFlipped -> SkyPrimary
              else -> Color(0xFF475569)
            },
          ),
          modifier = Modifier
            .height(104.dp)
            .clickable(enabled = !isPaused && !isFlipped && flippedUids.size < 2) {
              moves += 1
              flippedUids = flippedUids + card.uid
            }
            .testTag("memory_card_${card.uid}"),
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
            if (isFlipped) {
              Text(
                text = card.label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                fontWeight = if (card.isTerm) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
              )
            } else {
              Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = "Hidden card",
                tint = SunGold.copy(alpha = 0.7f),
                modifier = Modifier.size(28.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LabQuestPlayfield(
  game: GeneratedScienceGame,
  runSeed: Int,
  isPaused: Boolean,
  onPause: () -> Unit,
  onComplete: (Int, Int, Int) -> Unit,
) {
  val rounds = game.rounds
  var roundIdx by remember(runSeed) { mutableIntStateOf(0) }
  var lives by remember(runSeed) { mutableIntStateOf(3) }
  var score by remember(runSeed) { mutableIntStateOf(0) }
  var combo by remember(runSeed) { mutableIntStateOf(0) }
  var correctCount by remember(runSeed) { mutableIntStateOf(0) }
  var showHint by remember(runSeed, roundIdx) { mutableStateOf(false) }
  var selectedIdx by remember(runSeed, roundIdx) { mutableStateOf<Int?>(null) }

  val currentRound = rounds.getOrNull(roundIdx)
  if (currentRound == null) {
    onComplete(correctCount, rounds.size.coerceAtLeast(1), score)
    return
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .widthIn(max = 680.dp)
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    GameHudHeader(
      score = score,
      combo = combo,
      lives = lives,
      progressLabel = "Stage ${roundIdx + 1}/${rounds.size}",
      onPause = onPause,
    )

    LinearProgressIndicator(
      progress = { ((roundIdx + 1).toFloat() / rounds.size.coerceAtLeast(1)) },
      color = VioletAccent,
      trackColor = Color(0xFF1E293B),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .height(8.dp)
        .clip(RoundedCornerShape(50)),
    )

    Surface(
      shape = RoundedCornerShape(20.dp),
      color = Color(0xFF1E293B),
      border = BorderStroke(1.2.dp, VioletAccent.copy(alpha = 0.6f)),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(currentRound.question, style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (showHint) {
          Text("Clue: ${currentRound.hint}", style = MaterialTheme.typography.bodySmall, color = SunGold)
        } else {
          OutlinedButton(
            onClick = { showHint = true },
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, SunGold.copy(alpha = 0.5f)),
          ) {
            Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = SunGold, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Reveal Science Clue", color = SunGold, style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }

    Column(
      modifier = Modifier.padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      currentRound.options.forEachIndexed { idx, opt ->
        val answered = selectedIdx != null
        val isChosen = selectedIdx == idx
        val isCorrectOpt = idx == currentRound.correctIndex
        val bg = when {
          !answered -> Color(0xFF1E293B)
          isCorrectOpt -> LeafSecondary.copy(alpha = 0.28f)
          isChosen -> CoralError.copy(alpha = 0.28f)
          else -> Color(0xFF1E293B)
        }
        val border = when {
          !answered -> Color(0xFF475569)
          isCorrectOpt -> LeafSecondary
          isChosen -> CoralError
          else -> Color(0xFF334155)
        }
        Surface(
          onClick = {
            if (answered || isPaused) return@Surface
            selectedIdx = idx
            if (isCorrectOpt) {
              combo += 1
              correctCount += 1
              score += 100 + combo * 20
            } else {
              combo = 0
              lives -= 1
            }
          },
          shape = RoundedCornerShape(16.dp),
          color = bg,
          border = BorderStroke(1.5.dp, border),
          modifier = Modifier.fillMaxWidth().testTag("quest_option_$idx"),
        ) {
          Text(
            text = "${'A' + idx}) $opt",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.padding(14.dp),
          )
        }
      }

      if (selectedIdx != null) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = Color(0xFF0F172A),
          border = BorderStroke(1.dp, SkyPrimary.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = currentRound.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE2E8F0),
            modifier = Modifier.padding(12.dp),
          )
        }
        Button(
          onClick = {
            val next = roundIdx + 1
            if (lives <= 0 || next >= rounds.size) {
              onComplete(correctCount, rounds.size, score)
            } else {
              roundIdx = next
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, contentColor = Color.White),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
          Text(if (lives <= 0 || roundIdx >= rounds.lastIndex) "See Results" else "Next Stage")
        }
      }
    }
    Spacer(Modifier.height(16.dp))
  }
}

@Composable
private fun GameSummaryOverlay(
  game: GeneratedScienceGame,
  correct: Int,
  total: Int,
  score: Int,
  onReplay: () -> Unit,
  onExit: () -> Unit,
) {
  val pct = (correct * 100) / total.coerceAtLeast(1)
  val stars = when {
    pct >= 85 -> 3
    pct >= 60 -> 2
    pct >= 30 -> 1
    else -> 0
  }
  val xp = (correct * 15 + 20).coerceAtLeast(20)

  Surface(
    shape = RoundedCornerShape(28.dp),
    color = Color(0xFF1E293B),
    border = BorderStroke(1.5.dp, SunGold.copy(alpha = 0.65f)),
    modifier = Modifier
      .widthIn(max = 480.dp)
      .fillMaxWidth()
      .padding(20.dp),
  ) {
    Column(
      modifier = Modifier.padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in 1..3) {
          Icon(
            imageVector = if (s <= stars) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = null,
            tint = if (s <= stars) SunGold else Color(0xFF64748B),
            modifier = Modifier.size(44.dp),
          )
        }
      }
      Text(
        text = if (stars >= 2) "Mission Accomplished!" else "Good Effort, Explorer!",
        style = MaterialTheme.typography.headlineSmall,
        color = Color.White,
      )
      Text(
        text = game.title,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFCBD5E1),
        textAlign = TextAlign.Center,
      )
      Text(
        text = "Score: $score pts • $correct/$total ($pct%) • +$xp XP",
        style = MaterialTheme.typography.titleSmall,
        color = SunGold,
        fontWeight = FontWeight.Bold,
      )
      Button(
        onClick = onReplay,
        colors = ButtonDefaults.buttonColors(containerColor = LeafSecondary, contentColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(48.dp),
      ) {
        Icon(Icons.Rounded.Replay, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Play Again", fontWeight = FontWeight.Bold)
      }
      OutlinedButton(
        onClick = onExit,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF64748B)),
        modifier = Modifier.fillMaxWidth().height(48.dp),
      ) {
        Text("Back to Challenges", color = Color.White)
      }
    }
  }
}
