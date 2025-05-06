package com.example.accessibilityserviceapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
        // Request storage permission for CSV download
        requestStoragePermission()
    }

    private fun requestStoragePermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                100
            )
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val eventList = remember { mutableStateListOf<Pair<String, String>>() } // Pair<PackageName, Event>
    val selectedEvents = remember { mutableStateListOf<Pair<String, String>>() } // Selected events
    val appUsageData = remember { mutableStateOf<Map<String, Triple<Long, Int, String>>>(emptyMap()) }
    val appSessions = remember { mutableStateListOf<MyAccessibilityService.AppSession>() }

    // Collect events
    LaunchedEffect(Unit) {
        MyAccessibilityService.eventFlow.collectLatest { event ->
            event?.let {
                val packageName = it.substringBefore(" - ")
                eventList.add(Pair(packageName, it))
            }
        }
    }

    // Collect app usage data
    LaunchedEffect(Unit) {
        MyAccessibilityService.appUsageFlow.collectLatest { usageData ->
            usageData?.let {
                appUsageData.value = it
            }
        }
    }

    // Collect app session data
    LaunchedEffect(Unit) {
        MyAccessibilityService.appSessionFlow.collectLatest { sessions ->
            appSessions.clear()
            appSessions.addAll(sessions)
        }
    }

    // Helper function to format duration
    fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "$hours hours $minutes minutes"
            minutes > 0 -> "$minutes minutes"
            else -> "Less than 1 minute"
        }
    }

    // Helper function to validate and format time
    fun formatTime(time: String?, default: String = "Unknown"): String {
        if (time.isNullOrBlank()) return default
        try {
            val sdfInput = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val sdfOutput = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())
            val date = sdfInput.parse(time) ?: return default
            return sdfOutput.format(date)
        } catch (e: Exception) {
            return default
        }
    }

    // Helper function to summarize events
    fun summarizeEvents(events: List<Pair<String, String>>): String {
        if (events.isEmpty()) return "No events recorded"
        val clickCount = events.count { it.second.contains("Clicked") && !it.second.contains("Long Clicked") }
        val longClickCount = events.count { it.second.contains("Long Clicked") }
        val otherCount = events.size - clickCount - longClickCount
        val summary = mutableListOf<String>()
        if (clickCount > 0) summary.add("$clickCount click${if (clickCount > 1) "s" else ""}")
        if (longClickCount > 0) summary.add("$longClickCount long click${if (longClickCount > 1) "s" else ""}")
        if (otherCount > 0) summary.add("$otherCount other action${if (otherCount > 1) "s" else ""}")
        return summary.joinToString(", ")
    }

    // Data class to represent a table row
    data class TableRow(
        val packageName: String,
        val appName: String,
        val category: String, // App Activity
        val summary: String, // Summary for display
        val fullDetails: String, // Full details for expansion
        val events: List<Pair<String, String>>, // List of events for selection
        val totalTimeSpentMs: Long, // For sorting
        val iconBitmap: Bitmap? // App icon
    )

    // Combine all data into a list of TableRow
    val tableData = remember { mutableStateListOf<TableRow>() }
    LaunchedEffect(eventList, appUsageData.value, appSessions) {
        val packageNames = (eventList.map { it.first } + appUsageData.value.keys + appSessions.map { it.packageName }).distinct()
        val newTableData = mutableListOf<TableRow>()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        packageNames.forEach { packageName ->
            val appName = try {
                context.packageManager.getApplicationInfo(packageName, 0)
                    .let { context.packageManager.getApplicationLabel(it).toString() }
            } catch (e: PackageManager.NameNotFoundException) {
                "Unknown"
            }

            // Get app icon
            val iconBitmap: Bitmap? = try {
                val packageManager = context.packageManager
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val drawable = packageManager.getApplicationIcon(appInfo)
                drawable.toBitmap(48, 48)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }

            // Collect events
            val events = eventList.filter { it.first == packageName }
            val eventSummary = summarizeEvents(events)
            val eventDetails = events.map { event ->
                val eventTime = event.second.split(" - ")[0]
                val eventDetail = event.second.drop(event.second.indexOf(" - ") + 2)
                "Event at ${formatTime(eventTime)}: $eventDetail"
            }.joinToString("\n")

            // Collect usage
            val usageDetails = appUsageData.value[packageName]?.let { data ->
                val (timeSpentMs, openCount, _) = data
                val timeSpentText = formatDuration(timeSpentMs)
                "Usage: Total Time: $timeSpentText, Opened: $openCount times"
            } ?: "No usage recorded"
            val usageSummary = appUsageData.value[packageName]?.let { data ->
                val (timeSpentMs, openCount, _) = data
                val timeSpentText = formatDuration(timeSpentMs)
                "Used for $timeSpentText, opened $openCount times"
            } ?: "No usage recorded"

            // Collect sessions
            val sessions = appSessions.filter { it.packageName == packageName }
            val sessionDetails = sessions.map { session ->
                val openTime = formatTime(session.openedTime)
                val closeTime = if (session.closingTime != null) {
                    formatTime(session.closingTime)
                } else {
                    "Still Open"
                }
                val durationMs = if (session.closingTime == null) {
                    try {
                        val openDate = sdf.parse(session.openedTime)
                        if (openDate != null) {
                            System.currentTimeMillis() - openDate.time
                        } else {
                            session.sessionDurationMs
                        }
                    } catch (e: Exception) {
                        session.sessionDurationMs
                    }
                } else {
                    session.sessionDurationMs
                }
                val durationText = formatDuration(durationMs)
                "Session: Opened: $openTime, Closed: $closeTime, Duration: $durationText"
            }.joinToString("\n")
            val sessionSummary = if (sessions.isNotEmpty()) {
                val totalDurationMs = sessions.sumOf { session ->
                    if (session.closingTime == null) {
                        try {
                            val openDate = sdf.parse(session.openedTime)
                            if (openDate != null) System.currentTimeMillis() - openDate.time else session.sessionDurationMs
                        } catch (e: Exception) {
                            session.sessionDurationMs
                        }
                    } else {
                        session.sessionDurationMs
                    }
                }
                val avgDurationText = formatDuration(totalDurationMs / sessions.size.coerceAtLeast(1))
                "${sessions.size} session${if (sessions.size > 1) "s" else ""}, avg. $avgDurationText"
            } else {
                "No sessions recorded"
            }

            // Combine summary and details
            val summary = listOf(
                "Events: $eventSummary",
                "Usage: $usageSummary",
                "Sessions: $sessionSummary"
            ).joinToString("\n")
            val fullDetails = listOf(eventDetails, usageDetails, sessionDetails).filter { it.isNotEmpty() }.joinToString("\n\n")
            val totalTimeSpentMs = appUsageData.value[packageName]?.first ?: 0L

            if (summary.isNotEmpty()) {
                newTableData.add(
                    TableRow(
                        packageName = packageName,
                        appName = appName,
                        category = "App Activity",
                        summary = summary,
                        fullDetails = fullDetails,
                        events = events,
                        totalTimeSpentMs = totalTimeSpentMs,
                        iconBitmap = iconBitmap
                    )
                )
            }
        }

        tableData.clear()
        tableData.addAll(newTableData.sortedByDescending { it.totalTimeSpentMs })
    }

    // Generate CSV content
    fun generateCsvContent(): String {
        val csvBuilder = StringBuilder()
        // CSV Header
        csvBuilder.append("Package Name,App Name,Category,Events,Usage,Sessions\n")

        tableData.forEach { row ->
            val hasSelectedEvents = row.events.any { selectedEvents.contains(it) }
            if (row.events.isEmpty() || hasSelectedEvents) {
                val eventSummary = summarizeEvents(row.events).replace("\"", "\"\"").replace("\n", "\\n")
                val usageSummary = appUsageData.value[row.packageName]?.let { data ->
                    val (timeSpentMs, openCount, _) = data
                    val timeSpentText = formatDuration(timeSpentMs)
                    "Total Time: $timeSpentText, Opened: $openCount times"
                } ?: "No usage recorded"
                val sessionSummary = appSessions.filter { it.packageName == row.packageName }.let { sessions ->
                    if (sessions.isNotEmpty()) {
                        val totalDurationMs = sessions.sumOf { session ->
                            if (session.closingTime == null) {
                                try {
                                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                    val openDate = sdf.parse(session.openedTime)
                                    if (openDate != null) System.currentTimeMillis() - openDate.time else session.sessionDurationMs
                                } catch (e: Exception) {
                                    session.sessionDurationMs
                                }
                            } else {
                                session.sessionDurationMs
                            }
                        }
                        val avgDurationText = formatDuration(totalDurationMs / sessions.size.coerceAtLeast(1))
                        "${sessions.size} sessions, avg. $avgDurationText"
                    } else {
                        "No sessions recorded"
                    }
                }
                csvBuilder.append(
                    "\"${row.packageName}\",\"${row.appName}\",${row.category},\"$eventSummary\",\"$usageSummary\",\"$sessionSummary\"\n"
                )
            }
        }

        return csvBuilder.toString()
    }

    // Save CSV to Downloads
    fun saveCsvFile() {
        try {
            val fileName = "App_Usage_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            FileOutputStream(file).use { output ->
                output.write(generateCsvContent().toByteArray())
            }
            android.widget.Toast.makeText(context, "CSV saved to Downloads: $fileName", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Error saving CSV: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
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
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        }) {
            Text(text = "Enable Accessibility Service", fontSize = 16.sp)
        }

        Button(onClick = {
            saveCsvFile()
        }) {
            Text(text = "Download Usage Report", fontSize = 16.sp)
        }

        Text(
            text = "App Usage Analysis",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp
        )

        if (tableData.isEmpty()) {
            Text(
                text = "No usage data yet. Enable the accessibility service and use some apps.",
                fontSize = 16.sp,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF0F0F0))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Select",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(0.5f)
                        )
                        Text(
                            text = "App",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1.5f)
                        )
                        Text(
                            text = "Category",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(0.7f)
                        )
                        Text(
                            text = "Activity Summary",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(2f)
                        )
                    }
                }

                // Data rows
                items(tableData) { row ->
                    val showDetails = remember { mutableStateOf(false) }
                    val hasClickedEvent = row.events.any { it.second.contains("Clicked") || it.second.contains("Long Clicked") }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (hasClickedEvent) Color(0xFFEEEEEE) else Color.Transparent)
                            .clickable {
                                if (row.events.isNotEmpty()) {
                                    // Toggle selection
                                    if (row.events.all { selectedEvents.contains(it) }) {
                                        row.events.forEach { selectedEvents.remove(it) }
                                    } else {
                                        row.events.forEach { if (!selectedEvents.contains(it)) selectedEvents.add(it) }
                                    }
                                }
                                showDetails.value = !showDetails.value
                            }
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = row.events.isNotEmpty() && row.events.all { selectedEvents.contains(it) },
                                onCheckedChange = { checked ->
                                    if (row.events.isNotEmpty()) {
                                        if (checked) {
                                            row.events.forEach { if (!selectedEvents.contains(it)) selectedEvents.add(it) }
                                        } else {
                                            row.events.forEach { selectedEvents.remove(it) }
                                        }
                                    }
                                },
                                enabled = row.events.isNotEmpty(),
                                modifier = Modifier.weight(0.5f)
                            )
                            Row(
                                modifier = Modifier.weight(1.5f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                row.iconBitmap?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "${row.appName} icon",
                                        modifier = Modifier
                                            .size(40.dp)
                                            .padding(end = 8.dp)
                                    )
                                }
                                Text(
                                    text = row.appName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                text = row.category,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(0.7f)
                            )
                            Text(
                                text = row.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp,
                                color = Color.Black,
                                modifier = Modifier.weight(2f)
                            )
                        }
                        if (showDetails.value && row.fullDetails.isNotEmpty()) {
                            Text(
                                text = row.fullDetails,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp,
                                color = Color.DarkGray,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, start = 48.dp)
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