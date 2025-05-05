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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val eventList = remember { mutableStateListOf<String>() }
    val appUsageCHIPData = remember { mutableStateOf<Map<String, Triple<Long, Int, String>>>(emptyMap()) }
    val appSessions = remember { mutableStateListOf<MyAccessibilityService.AppSession>() }

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
                appUsageCHIPData.value = it
            }
        }
    }

    // Collect app session data from the appSessionFlow
    LaunchedEffect(Unit) {
        MyAccessibilityService.appSessionFlow.collectLatest { sessions ->
            appSessions.clear()
            appSessions.addAll(sessions)
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
                    val isClickedEvent = event.contains("Clicked") || event.contains("Long Clicked")
                    Text(
                        text = event,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isClickedEvent) Color.LightGray else Color.Transparent)
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Text(
            text = "App Usage Statistics",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (appUsageCHIPData.value.isEmpty()) {
            Text(text = "No app usage recorded yet. Switch between apps to track usage.")
        } else {
            // Sort by time spent (descending) to show the most used app at the top
            val sortedAppUsage = appUsageCHIPData.value.entries.sortedByDescending { it.value.first }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(sortedAppUsage) { entry ->
                    val packageName = entry.key
                    val (timeSpentMs, openCount, appName) = entry.value

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

                        // App Name, Time, and Open Count
                        Column {
                            Text(
                                text = appName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black
                            )
                            Text(
                                text = "Time: $timeText",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                            Text(
                                text = "Opened: $openCount times",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "App Session History",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (appSessions.isEmpty()) {
            Text(text = "No app sessions recorded yet. Switch between apps to track sessions.")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(appSessions.reversed()) { session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Fetch app icon
                        val iconBitmap = try {
                            val packageManager = context.packageManager
                            val appInfo = packageManager.getApplicationInfo(session.packageName, 0)
                            val drawable = packageManager.getApplicationIcon(appInfo)
                            drawable.toBitmap(48, 48) // Convert to Bitmap with 48dp size
                        } catch (e: PackageManager.NameNotFoundException) {
                            null
                        }

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

                        // Session Details
                        Column {
                            Text(
                                text = session.appName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black
                            )
                            Text(
                                text = "Opened: ${session.openedTime}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                            Text(
                                text = "Closed: ${session.closingTime ?: "Still Open"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                            if (session.closingTime != null) {
                                // Format session duration as "X hr Y mins" or "X minutes"
                                val sessionSeconds = session.sessionDurationMs / 1000
                                val hours = sessionSeconds / 3600
                                val minutes = (sessionSeconds % 3600) / 60
                                val durationText = when {
                                    hours > 0 -> "$hours hr $minutes mins"
                                    else -> "$minutes minutes"
                                }
                                Text(
                                    text = "Duration: $durationText",
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
}

// Extension function to convert Drawable to Bitmap
fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}