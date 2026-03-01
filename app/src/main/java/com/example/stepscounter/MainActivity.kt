package com.example.stepscounter

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stepscounter.ui.theme.StepsCounterTheme

class MainActivity : ComponentActivity() {

    private val _stepService = mutableStateOf<StepCounterService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as StepCounterService.LocalBinder
            _stepService.value = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            _stepService.value = null
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, StepCounterService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            StepsCounterTheme {
                val service by _stepService
                val context = androidx.compose.ui.platform.LocalContext.current
                
                // Request Permissions and Start Service
                RequestPermissions(
                    onPermissionsGranted = {
                        val serviceIntent = Intent(context, StepCounterService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    }
                )
                
                // Main UI
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StepCounterScreen(
                        service = service,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun RequestPermissions(onPermissionsGranted: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissions = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.values.all { it }
        if (allGranted) {
            onPermissionsGranted()
        }
    }

    LaunchedEffect(Unit) {
        val notGranted = permissions.filter { 
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        if (notGranted.isNotEmpty()) {
            launcher.launch(notGranted.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepCounterScreen(service: StepCounterService?, modifier: Modifier = Modifier) {
    val stepData = service?.stepData?.collectAsState(initial = StepData(0, 10000))?.value 
        ?: StepData(0, 10000)
    
    var showDialog by remember { mutableStateOf(false) }

    // Vibrant background gradient
    val backgroundBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
        colors = listOf(
            Color(0xFF1E3C72), // Deep Navy
            Color(0xFF2A5298), // Bright Blue
            Color(0xFF00C9FF)  // Cyan
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Column(
             modifier = Modifier.fillMaxSize().padding(16.dp),
             horizontalAlignment = Alignment.CenterHorizontally,
             verticalArrangement = Arrangement.Top
        ) {
             Spacer(modifier = Modifier.height(64.dp))
             
             Text(
                 text = "Today's Steps",
                 style = MaterialTheme.typography.headlineLarge,
                 color = Color.White.copy(alpha = 0.9f),
                 fontWeight = FontWeight.Light,
                 modifier = Modifier.padding(bottom = 48.dp)
             )

             CircularProgress(
                 progress = if (stepData.dailyGoal > 0) stepData.dailySteps.toFloat() / stepData.dailyGoal else 0f,
                 steps = stepData.dailySteps,
                 goal = stepData.dailyGoal
             )
             
             Spacer(modifier = Modifier.height(32.dp))
             
             // Motivational Text
             val motivationalText = when {
                 stepData.dailySteps == 0 -> "Let's get moving! 🚶"
                 stepData.dailySteps >= stepData.dailyGoal -> "Goal Reached! Amazing! 🎉"
                 stepData.dailySteps.toFloat() / stepData.dailyGoal > 0.8f -> "Almost there! Keep it up! 🏃"
                 stepData.dailySteps.toFloat() / stepData.dailyGoal > 0.5f -> "Halfway there! Great job! 🙌"
                 else -> "Keep going! 💪"
             }
             
             Text(
                 text = motivationalText,
                 style = MaterialTheme.typography.titleLarge,
                 color = Color.White,
                 fontWeight = FontWeight.Medium
             )
             
             Spacer(modifier = Modifier.weight(1f))
             
             // Stylish Button
             ElevatedButton(
                 onClick = { showDialog = true },
                 colors = ButtonDefaults.elevatedButtonColors(
                     containerColor = Color.White,
                     contentColor = Color(0xFF1E3C72)
                 ),
                 elevation = ButtonDefaults.elevatedButtonElevation(
                     defaultElevation = 8.dp,
                     pressedElevation = 2.dp
                 ),
                 modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(56.dp),
                 shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
             ) {
                 Text(
                     "Set Daily Goal",
                     fontSize = 18.sp,
                     fontWeight = FontWeight.Bold
                 )
             }
             Spacer(modifier = Modifier.height(32.dp))
        }
    }
    
    if (showDialog) {
        SetGoalDialog(
            currentGoal = stepData.dailyGoal, 
            onDismiss = { showDialog = false },
            onConfirm = { newGoal ->
                service?.setDailyGoal(newGoal)
                showDialog = false
            }
        )
    }
}

@Composable
fun CircularProgress(progress: Float, steps: Int, goal: Int) {
    val animatedProgress = animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        label = "progress",
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 1000)
    ).value
    
    // Gradient for the progress arc
    val progressBrush = androidx.compose.ui.graphics.Brush.sweepGradient(
        colors = listOf(
            Color(0xFF00FF87), // Bright Green
            Color(0xFF60EFFF)  // Light Blue
        )
    )
    
    Box(contentAlignment = Alignment.Center) {
        // Outer glow/shadow frame
        Box(
            modifier = Modifier
                .size(280.dp)
                .background(
                    color = Color.White.copy(alpha = 0.1f),
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        )
        
        Canvas(modifier = Modifier.size(250.dp)) {
            // Background track (Subtle, semi-transparent white)
            drawArc(
                color = Color.White.copy(alpha = 0.15f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Round)
            )
            // Progress arc
            // Rotate the canvas so the sweep gradient starts at the top
            withTransform({
                rotate(degrees = -90f)
            }) {
                drawArc(
                    brush = progressBrush,
                    startAngle = 0f,
                    sweepAngle = 360 * animatedProgress,
                    useCenter = false,
                    style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$steps", 
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp),
                color = Color.White,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "/ $goal", 
                style = MaterialTheme.typography.headlineSmall, 
                color = Color.White.copy(alpha = 0.7f),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SetGoalDialog(currentGoal: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(currentGoal.toString()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Daily Goal", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { 
                    if (it.all { char -> char.isDigit() }) {
                        text = it 
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("Steps") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF1E3C72),
                    focusedLabelColor = Color(0xFF1E3C72),
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.DarkGray,
                    cursorColor = Color(0xFF1E3C72)
                )
            )
        },
        containerColor = Color.White,
        titleContentColor = Color(0xFF1E3C72),
        textContentColor = Color.DarkGray,
        confirmButton = {
            Button(
                onClick = { 
                    val newGoal = text.toIntOrNull()
                    if (newGoal != null && newGoal > 0) {
                        onConfirm(newGoal)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3C72))
            ) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
            ) {
                Text("Cancel")
            }
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    )
}