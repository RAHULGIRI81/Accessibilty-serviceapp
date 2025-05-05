package com.example.accessibilityserviceapp

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val eventList = mutableStateListOf<String>()
    private val appUsageData = mutableStateOf<Map<String, Triple<Long, Int, String>>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen(eventList = eventList, appUsageData = appUsageData)
            }
        }
    }
}

@Composable
fun MainScreen(eventList: MutableList<String>, appUsageData: MutableState<Map<String, Triple<Long, Int, String>>>) {
    val context = LocalContext.current

    // Collect events from the eventFlow
    LaunchedEffect(Unit) {
        MyAccessibilityService.eventFlow.collectLatest { event ->
            event?.let {
                eventList.add(it)
            }
        }
    }

    // Collect app usage data from the appUsageFlow
    LaunchedEffect(Unit) {
        MyAccessibilityService.appUsageFlow.collectLatest { usageData ->
            usageData?.let {
                appUsageData.value = it
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = {
            // Open Accessibility Settings to enable the service
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        }) {
            Text(text = "Enable Accessibility Service")
        }

        Text(
            text = "Monitored Events",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (eventList.isEmpty()) {
            Text(text = "No events captured yet. Interact with the device after enabling the service.")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(eventList) { event ->
                    val isClickedEvent = event.contains("Clicked")
                    Text(
                        text = event,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isClickedEvent) Color.LightGray else Color.Transparent)
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                    )
                }
            }
        }

        Text(
            text = "App Usage Statistics",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (appUsageData.value.isEmpty()) {
            Text(text = "No app usage recorded yet. Switch between apps to track usage.")
        } else {
            // Sort by time spent (descending) to show the most used app at the top
            val sortedAppUsage = appUsageData.value.entries.sortedByDescending { it.value.first }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(sortedAppUsage) { entry ->
                    val packageName = entry.key
                    val (timeSpentMs, _, appName) = entry.value

                    // Format time spent as "X hr Y mins" or "X minutes"
                    val timeSpentSeconds = timeSpentMs / 1000
                    val hours = timeSpentSeconds / 3600
                    val minutes = (timeSpentSeconds % 3600) / 60
                    val timeText = when {
                        hours > 0 -> "$hours hr $minutes mins"
                        else -> "$minutes minutes"
                    }

                    // Fetch app icon
                    val iconBitmap = try {
                        val packageManager = context.packageManager
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        val drawable = packageManager.getApplicationIcon(appInfo)
                        drawable.toBitmap(48, 48) // Convert to Bitmap with 48dp size
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // App Icon
                        if (iconBitmap != null) {
                            Image(
                                bitmap = iconBitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(end = 16.dp)
                            )
                        } else {
                            // Placeholder if icon not found
                            Text(
                                text = "?", // Fallback placeholder
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(end = 16.dp),
                                fontSize = 24.sp
                            )
                        }

                        // App Name and Time
                        Column {
                            Text(
                                text = appName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black
                            )
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

// Extension function to convert Drawable to Bitmap
fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}