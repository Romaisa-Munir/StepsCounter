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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

    Column(
         modifier = modifier.fillMaxSize(),
         horizontalAlignment = Alignment.CenterHorizontally,
         verticalArrangement = Arrangement.Center
    ) {
         Text(
             text = "Today's Steps",
             style = MaterialTheme.typography.headlineMedium,
             modifier = Modifier.padding(bottom = 32.dp)
         )

         CircularProgress(
             progress = if (stepData.dailyGoal > 0) stepData.dailySteps.toFloat() / stepData.dailyGoal else 0f,
             steps = stepData.dailySteps,
             goal = stepData.dailyGoal
         )
         
         Spacer(modifier = Modifier.height(48.dp))
         
         Button(onClick = { showDialog = true }) {
             Text("Set Daily Goal")
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
        label = "progress"
    ).value
    
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(250.dp)) {
            // Background track
            drawArc(
                color = Color.LightGray.copy(alpha = 0.3f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 20.dp.toPx(), cap = StrokeCap.Round)
            )
            // Progress
            drawArc(
                color = Color(0xFF4CAF50), // Nice Green
                startAngle = -90f,
                sweepAngle = 360 * animatedProgress,
                useCenter = false,
                style = Stroke(width = 20.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$steps", 
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "/ $goal", 
                style = MaterialTheme.typography.titleMedium, 
                color = Color.Gray
            )
        }
    }
}

@Composable
fun SetGoalDialog(currentGoal: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(currentGoal.toString()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Daily Goal") },
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
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { 
                val newGoal = text.toIntOrNull()
                if (newGoal != null && newGoal > 0) {
                    onConfirm(newGoal)
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}