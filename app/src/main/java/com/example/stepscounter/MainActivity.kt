package com.example.stepscounter

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.stepscounter.ui.pixel.LocalPixel
import com.example.stepscounter.ui.pixel.PixelButton
import com.example.stepscounter.ui.pixel.PixelColors
import com.example.stepscounter.ui.pixel.PixelPanel
import com.example.stepscounter.ui.pixel.PixelScene
import com.example.stepscounter.ui.pixel.PixelText
import com.example.stepscounter.ui.pixel.SCENE_MIN_ROWS
import com.example.stepscounter.ui.pixel.SegmentedBar
import com.example.stepscounter.ui.pixel.WeekChart
import com.example.stepscounter.ui.pixel.rememberPixelMetrics
import com.example.stepscounter.ui.pixel.weekDescription
import com.example.stepscounter.ui.theme.StepsCounterTheme
import kotlinx.coroutines.delay
import java.text.NumberFormat

private const val MAX_GOAL_DIGITS = 6

/** How long the walker keeps walking after the last new step. */
private const val WALK_LINGER_MS = 2_500L

class MainActivity : ComponentActivity() {

    /** Bumped on every resume so the screen re-checks permissions and settings changed elsewhere. */
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light status/navigation bar icons over the dark scene.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        StepStore.init(this)
        setContent {
            StepsCounterTheme {
                StepCounterScreen(resumeCount = resumeCount)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StepStore.refreshDay()
        resumeCount++
    }
}

private fun runtimePermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun StepCounterScreen(resumeCount: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val stepData by StepStore.data.collectAsState()
    val hasSensor = remember {
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }
    var hasActivityPermission by remember { mutableStateOf(StepCounterService.hasActivityPermission(context)) }
    var batteryUnrestricted by remember { mutableStateOf(true) }
    var autostartAllowed by remember { mutableStateOf<Boolean?>(true) }
    var autostartConfirmed by remember { mutableStateOf(BackgroundSetup.isAutostartConfirmed(context)) }
    var showGoalDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasActivityPermission = StepCounterService.hasActivityPermission(context)
        if (hasActivityPermission) StepCounterService.start(context)
    }

    LaunchedEffect(Unit) {
        val missing = runtimePermissions().filterNot { context.isGranted(it) }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    // Opening the app also restarts tracking if the system killed it.
    LaunchedEffect(resumeCount) {
        hasActivityPermission = StepCounterService.hasActivityPermission(context)
        if (hasActivityPermission && hasSensor) StepCounterService.start(context)
        batteryUnrestricted = BackgroundSetup.isBatteryUnrestricted(context)
        autostartAllowed = BackgroundSetup.isAutostartAllowed(context)
    }

    // The walker walks while steps are coming in.
    var walking by remember { mutableStateOf(false) }
    var lastSteps by remember { mutableIntStateOf(stepData.dailySteps) }
    LaunchedEffect(stepData.dailySteps) {
        val stepped = stepData.dailySteps > lastSteps
        lastSteps = stepData.dailySteps
        if (stepped) {
            walking = true
            delay(WALK_LINGER_MS)
            walking = false
        }
    }

    val autostartDone = autostartAllowed == true || (autostartAllowed == null && autostartConfirmed)
    val backgroundSetupNeeded = !batteryUnrestricted || !autostartDone

    val format = remember { NumberFormat.getIntegerInstance() }
    val steps = stepData.dailySteps
    val goal = stepData.dailyGoal.coerceAtLeast(1)
    val fraction = steps.toFloat() / goal
    val motivation = stringResource(
        when {
            steps == 0 -> R.string.motivation_start
            fraction >= 1f -> R.string.motivation_done
            fraction > 0.8f -> R.string.motivation_almost
            fraction > 0.5f -> R.string.motivation_half
            else -> R.string.motivation_keep_going
        },
    )

    val pixelMetrics = rememberPixelMetrics()
    CompositionLocalProvider(LocalPixel provides pixelMetrics) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(PixelColors.Night),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val viewportHeight = constraints.maxHeight
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SceneAbovePanel(
                        viewportHeight = viewportHeight,
                        minSceneHeight = pixelMetrics.unit * SCENE_MIN_ROWS,
                        scene = {
                            PixelScene(
                                steps = steps,
                                goal = goal,
                                walking = walking,
                                todayLabel = stringResource(R.string.todays_steps),
                                countText = format.format(steps),
                                goalText = stringResource(R.string.goal_of, format.format(goal)),
                                motivation = motivation,
                                description = stringResource(R.string.a11y_scene, format.format(steps), format.format(goal), motivation),
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                        panel = {
                            Column(
                                modifier = Modifier
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                    .padding(horizontal = 20.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PixelText(stringResource(R.string.goal_label), PixelColors.Muted)
                                    Spacer(Modifier.weight(1f))
                                    PixelText(
                                        stringResource(R.string.goal_percent, (fraction * 100).toInt()),
                                        if (fraction >= 1f) PixelColors.Gold else PixelColors.Amber,
                                    )
                                }
                                SegmentedBar(fraction)

                                Spacer(Modifier.height(8.dp))
                                PixelText(stringResource(R.string.this_week), PixelColors.Muted)
                                WeekChart(
                                    days = stepData.week,
                                    goal = goal,
                                    description = stringResource(R.string.a11y_week, weekDescription(stepData.week, format)),
                                )
                                if (stepData.week.isNotEmpty()) {
                                    Row {
                                        PixelText(
                                            stringResource(R.string.week_average, format.format(stepData.week.sumOf { it.steps } / stepData.week.size)),
                                            PixelColors.Sand,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        PixelText(
                                            stringResource(R.string.week_best, format.format(stepData.week.maxOf { it.steps })),
                                            PixelColors.Sand,
                                        )
                                    }
                                }

                                when {
                                    !hasSensor -> NoticeCard(
                                        title = stringResource(R.string.no_sensor_title),
                                        body = stringResource(R.string.no_sensor_body),
                                    )

                                    !hasActivityPermission -> NoticeCard(
                                        title = stringResource(R.string.permission_title),
                                        body = stringResource(R.string.permission_body),
                                    ) {
                                        PixelButton(
                                            stringResource(R.string.permission_action),
                                            onClick = {
                                                val activity = context.findActivity()
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && activity != null &&
                                                    activity.shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)
                                                ) {
                                                    permissionLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                                                } else {
                                                    // Denied for good: only the settings screen can change it now.
                                                    BackgroundSetup.openAppDetails(context)
                                                }
                                            },
                                        )
                                    }

                                    backgroundSetupNeeded -> NoticeCard(
                                        title = stringResource(R.string.background_title),
                                        body = stringResource(R.string.background_body),
                                    ) {
                                        SetupRow(
                                            label = stringResource(R.string.background_battery),
                                            done = batteryUnrestricted,
                                            actionLabel = stringResource(R.string.background_allow),
                                            onAction = { BackgroundSetup.requestUnrestrictedBattery(context) },
                                        )
                                        if (BackgroundSetup.isXiaomi) {
                                            SetupRow(
                                                label = stringResource(R.string.background_autostart),
                                                done = autostartDone,
                                                actionLabel = stringResource(R.string.background_turn_on),
                                                onAction = { BackgroundSetup.openAutostartSettings(context) },
                                            )
                                            // Only needed when the Autostart state can't be read on this phone.
                                            if (autostartAllowed == null && !autostartConfirmed) {
                                                TextButton(
                                                    onClick = {
                                                        BackgroundSetup.confirmAutostart(context)
                                                        autostartConfirmed = true
                                                    },
                                                    colors = ButtonDefaults.textButtonColors(contentColor = PixelColors.Sand),
                                                    modifier = Modifier.align(Alignment.End),
                                                ) {
                                                    Text(stringResource(R.string.background_autostart_done))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }

            PixelButton(
                text = stringResource(R.string.set_daily_goal),
                onClick = { showGoalDialog = true },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 4.dp, bottom = 12.dp),
            )
        }

        if (showGoalDialog) {
            SetGoalDialog(
                currentGoal = stepData.dailyGoal,
                onDismiss = { showGoalDialog = false },
                onConfirm = { newGoal ->
                    StepStore.setDailyGoal(newGoal)
                    showGoalDialog = false
                },
            )
        }
    }
}

/**
 * Gives [scene] all the height that [panel] leaves in the viewport, but never less than
 * [minSceneHeight]; when they don't fit, the page scrolls instead.
 */
@Composable
private fun SceneAbovePanel(
    viewportHeight: Int,
    minSceneHeight: Int,
    scene: @Composable () -> Unit,
    panel: @Composable () -> Unit,
) {
    Layout(content = { scene(); panel() }) { measurables, constraints ->
        val width = constraints.maxWidth
        val panelPlaceable = measurables[1].measure(Constraints(minWidth = width, maxWidth = width))
        val sceneHeight = maxOf(minSceneHeight, viewportHeight - panelPlaceable.height)
        val scenePlaceable = measurables[0].measure(Constraints.fixed(width, sceneHeight))
        layout(width, sceneHeight + panelPlaceable.height) {
            scenePlaceable.place(0, 0)
            panelPlaceable.place(0, sceneHeight)
        }
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    content: @Composable () -> Unit = {},
) {
    PixelPanel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = PixelColors.Cream)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = PixelColors.Sand)
        content()
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean, actionLabel: String, onAction: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        PixelText(
            text = if (done) "✓" else "•",
            color = if (done) PixelColors.Gold else PixelColors.Sand,
            contentDescription = stringResource(if (done) R.string.a11y_done else R.string.a11y_not_done),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = PixelColors.Cream, modifier = Modifier.weight(1f))
        if (!done) PixelButton(actionLabel, onAction, fill = PixelColors.Amber)
    }
}

@Composable
fun SetGoalDialog(currentGoal: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(currentGoal.toString()) }
    val newGoal = text.toIntOrNull()?.takeIf { it > 0 }

    Dialog(onDismissRequest = onDismiss) {
        PixelPanel(Modifier.fillMaxWidth()) {
            PixelText(stringResource(R.string.goal_dialog_title), PixelColors.Cream)
            OutlinedTextField(
                value = text,
                onValueChange = {
                    if (it.length <= MAX_GOAL_DIGITS && it.all { char -> char.isDigit() }) {
                        text = it
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.goal_dialog_label)) },
                singleLine = true,
                shape = RectangleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PixelColors.Cream,
                    unfocusedTextColor = PixelColors.Cream,
                    focusedContainerColor = PixelColors.Night,
                    unfocusedContainerColor = PixelColors.Night,
                    focusedBorderColor = PixelColors.Amber,
                    unfocusedBorderColor = PixelColors.PanelBorder,
                    focusedLabelColor = PixelColors.Amber,
                    unfocusedLabelColor = PixelColors.Sand,
                    cursorColor = PixelColors.Amber,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PixelButton(
                    stringResource(R.string.cancel),
                    onClick = onDismiss,
                    fill = PixelColors.Track,
                    textColor = PixelColors.Cream,
                    shadow = PixelColors.Night,
                )
                PixelButton(
                    stringResource(R.string.save),
                    onClick = { newGoal?.let(onConfirm) },
                    fill = if (newGoal != null) PixelColors.Amber else PixelColors.Track,
                    textColor = if (newGoal != null) PixelColors.Night else PixelColors.Muted,
                )
            }
        }
    }
}
