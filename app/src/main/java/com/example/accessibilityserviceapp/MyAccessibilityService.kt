package com.example.accessibilityserviceapp

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MyAccessibilityService : AccessibilityService() {

    companion object {
        // StateFlow to emit events (click/focus) to the UI
        val eventFlow = MutableStateFlow<String?>(null)
        // StateFlow to emit app usage data (time spent, open counts, app name)
        val appUsageFlow = MutableStateFlow<Map<String, Triple<Long, Int, String>>>(emptyMap())
    }

    private var lastPackageName: String? = null
    private var lastSwitchTime: Long = 0
    private val appUsageTimes = mutableMapOf<String, Long>() // Package name -> total time spent (ms)
    private val appOpenCounts = mutableMapOf<String, Int>() // Package name -> number of times opened
    private val appNames = mutableMapOf<String, String>() // Package name -> app name
    private var lastResetDate: String = getCurrentDate() // Store the date of the last reset

    private fun getCurrentDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun resetDailyCountsIfNeeded() {
        val currentDate = getCurrentDate()
        if (currentDate != lastResetDate) {
            appOpenCounts.clear() // Reset open counts daily
            lastResetDate = currentDate
            Log.d("AccessibilityService", "Daily counts reset on $currentDate")
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val packageManager: PackageManager = packageManager
            val appInfo: ApplicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val appLabel = packageManager.getApplicationLabel(appInfo).toString()
            // Clean up the app name (remove unexpected suffixes, trim spaces)
            appLabel.trim().replace(Regex("(?i)\\s*(premium|pro|lite|free)$"), "")
        } catch (e: PackageManager.NameNotFoundException) {
            packageName // Fallback to package name if app name not found
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            // Check if we need to reset counts for a new day
            resetDailyCountsIfNeeded()

            when (it.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    // Handle Clicked and Focused events
                    val eventText = when (it.eventType) {
                        AccessibilityEvent.TYPE_VIEW_CLICKED -> "Clicked"
                        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "Focused"
                        else -> return@let
                    }

                    val packageName = it.packageName?.toString() ?: "Unknown"
                    val contentDescription = it.contentDescription?.toString() ?: "No description"
                    val eventTime = getCurrentTime() // Use current device time for real-time updates

                    // Get more details about the clicked element using AccessibilityNodeInfo
                    val source: AccessibilityNodeInfo? = it.source
                    val clickDetails = if (eventText == "Clicked" && source != null) {
                        val viewId = source.viewIdResourceName?.split("/")?.lastOrNull() ?: "Unknown ID"
                        val text = source.text?.toString() ?: ""
                        val details = if (text.isNotEmpty()) "Text: $text" else "ID: $viewId"
                        "($details in ${appNames[packageName] ?: packageName})"
                    } else {
                        "(Package: $packageName)"
                    }

                    // Create a formatted event message
                    val eventMessage = "$eventTime - $eventText: $contentDescription $clickDetails"

                    // Log to Logcat
                    Log.d("AccessibilityService", eventMessage)

                    // Emit the event to the eventFlow
                    eventFlow.value = eventMessage

                    // Clean up the source node to prevent memory leaks
                    source?.recycle()
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // Handle app switches to track time spent and open counts
                    val packageName = it.packageName?.toString() ?: return
                    val currentTime = it.eventTime

                    // Update time spent in the previous app
                    if (lastPackageName != null && lastSwitchTime != 0L) {
                        val timeSpent = currentTime - lastSwitchTime
                        appUsageTimes[lastPackageName!!] = (appUsageTimes[lastPackageName!!] ?: 0L) + timeSpent
                        Log.d("AccessibilityService", "Time spent in $lastPackageName: $timeSpent ms")
                    }

                    // Increment open count for the new app
                    if (packageName != lastPackageName) {
                        appOpenCounts[packageName] = (appOpenCounts[packageName] ?: 0) + 1
                        Log.d("AccessibilityService", "$packageName opened ${appOpenCounts[packageName]} times today")
                    }

                    // Cache the app name
                    if (!appNames.containsKey(packageName)) {
                        appNames[packageName] = getAppName(packageName)
                    }

                    // Update the current app and timestamp
                    lastPackageName = packageName
                    lastSwitchTime = currentTime

                    // Emit the updated app usage data (time spent, open counts, app name)
                    appUsageFlow.value = appUsageTimes.mapValues { entry ->
                        val timeSpent = entry.value
                        val openCount = appOpenCounts[entry.key] ?: 0
                        val appName = appNames[entry.key] ?: entry.key
                        Triple(timeSpent, openCount, appName)
                    }
                }
                else -> {
                    Log.d("AccessibilityService", "Unhandled event type: ${it.eventType}")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d("AccessibilityService", "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AccessibilityService", "Service connected")
        lastSwitchTime = System.currentTimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Update time spent in the last app before the service is destroyed
        if (lastPackageName != null && lastSwitchTime != 0L) {
            val timeSpent = System.currentTimeMillis() - lastSwitchTime
            appUsageTimes[lastPackageName!!] = (appUsageTimes[lastPackageName!!] ?: 0L) + timeSpent
            appUsageFlow.value = appUsageTimes.mapValues { entry ->
                val timeSpent = entry.value
                val openCount = appOpenCounts[entry.key] ?: 0
                val appName = appNames[entry.key] ?: entry.key
                Triple(timeSpent, openCount, appName)
            }
        }
    }
}