package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.AppDatabase
import com.example.data.local.CurriculumLoader
import com.example.data.model.GameGeneratorState
import com.example.data.repository.AiScienceRepository
import com.example.data.repository.ScienceRepository
import com.example.ui.components.AddLearnerDialog
import com.example.ui.components.AiGameCreatorDialog
import com.example.ui.components.AiTutorSheet
import com.example.ui.components.LearnerSwitcherDialog
import com.example.ui.components.PdfReportDialog
import com.example.ui.screens.ActivityScreen
import com.example.ui.screens.AiGameScreen
import com.example.ui.screens.ChallengeScreen
import com.example.ui.screens.LearnScreen
import com.example.ui.screens.ProgressScreen
import com.example.ui.theme.BagacayScienceTheme
import com.example.ui.viewmodel.AiAssistantViewModel
import com.example.ui.viewmodel.AiAssistantViewModelFactory
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.MainViewModelFactory
import com.example.util.NetworkConnectivityMonitor
import com.example.util.TextToSpeechHelper
import com.example.util.configureAppCheck

private enum class TopTab(
  val route: String,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
) {
  LEARN("learn", "Learn", Icons.Rounded.MenuBook, Icons.Outlined.MenuBook),
  CHALLENGE("challenge", "Challenge", Icons.Rounded.Explore, Icons.Outlined.Explore),
  PROGRESS("progress", "Progress", Icons.Rounded.EmojiEvents, Icons.Outlined.EmojiEvents),
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Configure Firebase App Check before rendering Compose UI or creating ViewModels.
    configureAppCheck(this, intent)
    enableEdgeToEdge()

    val db = AppDatabase.getInstance(applicationContext)
    val repo = ScienceRepository(CurriculumLoader(applicationContext), db.progressDao())
    val factory = MainViewModelFactory(repo)
    val connectivityMonitor = NetworkConnectivityMonitor(applicationContext)
    val aiRepository = AiScienceRepository(applicationContext, connectivityMonitor)
    val aiFactory = AiAssistantViewModelFactory(repo, aiRepository, connectivityMonitor)

    setContent {
      BagacayScienceTheme {
        BagacayScienceApp(factory = factory, aiFactory = aiFactory)
      }
    }
  }
}

@Composable
fun BagacayScienceApp(
  factory: MainViewModelFactory,
  aiFactory: AiAssistantViewModelFactory,
) {
  val viewModel: MainViewModel = viewModel(factory = factory)
  val aiViewModel: AiAssistantViewModel = viewModel(factory = aiFactory)
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val aiUiState by aiViewModel.uiState.collectAsStateWithLifecycle()
  val session by viewModel.sessionState.collectAsStateWithLifecycle()

  val context = LocalContext.current
  val ttsHelper = remember { TextToSpeechHelper(context) }
  DisposableEffect(Unit) {
    onDispose { ttsHelper.shutdown() }
  }

  var showLearnerSwitcher by remember { mutableStateOf(false) }
  var showAddLearner by remember { mutableStateOf(false) }
  var showMainPdfReport by remember { mutableStateOf(false) }
  var showAiTutorSheet by remember { mutableStateOf(false) }
  var showAiGameCreator by remember { mutableStateOf(false) }

  if (uiState.isLoading) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("Loading Science Explorer...", style = MaterialTheme.typography.bodyMedium)
      }
    }
    return
  }

  val activeGeneratedGame = (aiUiState.gameGeneratorState as? GameGeneratorState.Ready)?.game
  if (activeGeneratedGame != null) {
    AiGameScreen(
      game = activeGeneratedGame,
      onRecordResult = { correct, total, xp ->
        aiViewModel.recordGeneratedGameCompletion(
          learnerId = uiState.stats.id,
          game = activeGeneratedGame,
          correctCount = correct,
          totalCount = total,
          xpEarned = xp,
        )
      },
      onExit = {
        showAiGameCreator = false
        aiViewModel.clearGeneratedGame()
      },
    )
    return
  }

  val activeSession = session
  if (activeSession != null) {
    ActivityScreen(
      session = activeSession,
      uiState = uiState,
      isOnline = aiUiState.isOnline,
      ttsAutoRead = uiState.stats.ttsAutoRead,
      onSpeakText = { ttsHelper.speak(it) },
      onStopSpeech = { ttsHelper.stop() },
      onAskAiTutor = { qCtx ->
        aiViewModel.openTutor(qCtx)
        showAiTutorSheet = true
      },
      onLoadAllSnapshots = viewModel::loadAllLearnersReportSnapshots,
      viewModel = viewModel,
    )
    if (showAiTutorSheet) {
      AiTutorSheet(
        uiState = aiUiState,
        onSelectLanguage = aiViewModel::setLanguage,
        onSendMessage = aiViewModel::sendTutorMessage,
        onStartVoice = aiViewModel::startVoiceConversation,
        onStopVoice = aiViewModel::stopVoiceConversation,
        onDismiss = { showAiTutorSheet = false },
      )
    }
    return
  }

  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route ?: TopTab.LEARN.route

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useNavRail = maxWidth >= 600.dp

    Scaffold(
      contentWindowInsets = WindowInsets.safeDrawing,
      bottomBar = {
        if (!useNavRail) {
          NavigationBar {
            TopTab.entries.forEach { tab ->
              val selected = currentRoute == tab.route
              NavigationBarItem(
                selected = selected,
                onClick = {
                  if (!selected) {
                    navController.navigate(tab.route) {
                      popUpTo(TopTab.LEARN.route) { saveState = true }
                      launchSingleTop = true
                      restoreState = true
                    }
                  }
                },
                icon = {
                  Icon(
                    if (selected) tab.selectedIcon else tab.unselectedIcon,
                    contentDescription = tab.label,
                  )
                },
                label = { Text(tab.label) },
                modifier = Modifier.testTag("nav_tab_${tab.route}"),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        if (useNavRail) {
          NavigationRail {
            TopTab.entries.forEach { tab ->
              val selected = currentRoute == tab.route
              NavigationRailItem(
                selected = selected,
                onClick = {
                  if (!selected) {
                    navController.navigate(tab.route) {
                      popUpTo(TopTab.LEARN.route) { saveState = true }
                      launchSingleTop = true
                      restoreState = true
                    }
                  }
                },
                icon = {
                  Icon(
                    if (selected) tab.selectedIcon else tab.unselectedIcon,
                    contentDescription = tab.label,
                  )
                },
                label = { Text(tab.label) },
                modifier = Modifier.testTag("rail_tab_${tab.route}"),
              )
            }
          }
        }

        NavHost(
          navController = navController,
          startDestination = TopTab.LEARN.route,
          modifier = Modifier.weight(1f),
        ) {
          composable(TopTab.LEARN.route) {
            LearnScreen(
              uiState = uiState,
              isOnline = aiUiState.isOnline,
              onSelectGrade = viewModel::selectGrade,
              onSelectTerm = viewModel::selectTerm,
              onSearchChange = viewModel::updateSearchQuery,
              onStartLesson = viewModel::startLesson,
              onOpenLearnerSwitcher = { showLearnerSwitcher = true },
              onOpenAddLearner = { showAddLearner = true },
              onOpenProgressReport = { showMainPdfReport = true },
              onOpenAiVoiceTutor = {
                aiViewModel.openTutor(null)
                showAiTutorSheet = true
              },
              onOpenAiGameMaker = {
                aiViewModel.refreshConnectivity()
                showAiGameCreator = true
              },
            )
          }
          composable(TopTab.CHALLENGE.route) {
            ChallengeScreen(
              uiState = uiState,
              isOnline = aiUiState.isOnline,
              onStartChallenge = viewModel::startChallenge,
              onOpenLearnerSwitcher = { showLearnerSwitcher = true },
              onOpenAiGameMaker = {
                aiViewModel.refreshConnectivity()
                showAiGameCreator = true
              },
              onOpenAiVoiceTutor = {
                aiViewModel.openTutor(null)
                showAiTutorSheet = true
              },
            )
          }
          composable(TopTab.PROGRESS.route) {
            ProgressScreen(
              uiState = uiState,
              onUpdateProfile = viewModel::updateProfile,
              onOpenLearnerSwitcher = { showLearnerSwitcher = true },
              onOpenAddLearner = { showAddLearner = true },
              onToggleTtsAutoRead = viewModel::setTtsAutoRead,
              onResetProgress = viewModel::resetAllProgress,
              onLoadAllSnapshots = viewModel::loadAllLearnersReportSnapshots,
            )
          }
        }
      }
    }
  }

  if (showLearnerSwitcher) {
    LearnerSwitcherDialog(
      learners = uiState.learners,
      activeLearnerId = uiState.stats.id,
      learnerStarsMap = uiState.learnerStarsMap,
      onSwitchLearner = viewModel::switchLearner,
      onOpenAddLearner = { showAddLearner = true },
      onDeleteLearner = viewModel::deleteLearner,
      onDismiss = { showLearnerSwitcher = false },
    )
  }

  if (showAddLearner) {
    AddLearnerDialog(
      suggestedNumber = uiState.learners.size + 1,
      onCreateLearner = viewModel::addLearner,
      onDismiss = { showAddLearner = false },
    )
  }

  if (showMainPdfReport) {
    PdfReportDialog(
      uiState = uiState,
      onLoadAllSnapshots = viewModel::loadAllLearnersReportSnapshots,
      onDismiss = { showMainPdfReport = false },
    )
  }

  if (showAiTutorSheet) {
    AiTutorSheet(
      uiState = aiUiState,
      onSelectLanguage = aiViewModel::setLanguage,
      onSendMessage = aiViewModel::sendTutorMessage,
      onStartVoice = aiViewModel::startVoiceConversation,
      onStopVoice = aiViewModel::stopVoiceConversation,
      onDismiss = { showAiTutorSheet = false },
    )
  }

  if (showAiGameCreator) {
    AiGameCreatorDialog(
      isOnline = aiUiState.isOnline,
      defaultGrade = uiState.selectedGrade,
      generatorState = aiUiState.gameGeneratorState,
      onGenerate = { topic, grade, lang, mode ->
        aiViewModel.generateGame(topic, grade, lang, mode)
      },
      onDismiss = {
        showAiGameCreator = false
        if (aiUiState.gameGeneratorState !is GameGeneratorState.Ready) {
          aiViewModel.clearGeneratedGame()
        }
      },
    )
  }
}
