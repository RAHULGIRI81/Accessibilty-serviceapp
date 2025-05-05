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
        // StateFlow to emit events (click/focus/long click) to the UI
        val eventFlow = MutableStateFlow<String?>(null)
        // StateFlow to emit app usage data (time spent, open counts, app name)
        val appUsageFlow = MutableStateFlow<Map<String, Triple<Long, Int, String>>>(emptyMap())
        // StateFlow to emit app session data (package name, opened time, closing time, app name)
        val appSessionFlow = MutableStateFlow<List<AppSession>>(emptyList())
    }

    data class AppSession(
        val packageName: String,
        val appName: String,
        val openedTime: String, // Formatted as HH:mm:ss
        val closingTime: String?, // Formatted as HH:mm:ss, null if still open
        val sessionDurationMs: Long // Duration of the session in milliseconds
    )

    private var lastPackageName: String? = null
    private var lastOpenTime: Long = 0 // Time when the last app was opened (in milliseconds)
    private var lastAppOpenedTimeFormatted: String? = null // Formatted opened time (HH:mm:ss)
    private val appUsageTimes = mutableMapOf<String, Long>() // Package name -> total time spent (ms)
    private val appOpenCounts = mutableMapOf<String, Int>() // Package name -> number of times opened
    private val appNames = mutableMapOf<String, String>() // Package name -> app name
    private val appSessions = mutableListOf<AppSession>() // List of all app sessions
    private var lastResetDate: String = getCurrentDate() // Store the date of the last reset

    private fun getCurrentDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun formatTime(millis: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }

    private fun resetDailyCountsIfNeeded() {
        val currentDate = getCurrentDate()
        if (currentDate != lastResetDate) {
            appOpenCounts.clear() // Reset open counts daily
            appUsageTimes.clear() // Reset usage times daily
            appSessions.clear() // Clear session history daily
            appSessionFlow.value = appSessions // Emit cleared session list
            lastResetDate = currentDate
            Log.d("AccessibilityService", "Daily counts reset on $currentDate")
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val packageManager: PackageManager = packageManager
            val appInfo: ApplicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val appLabel = packageManager.getApplicationLabel(appInfo).toString()
            appLabel.trim().replace(Regex("(?i)\\s*(premium|pro|lite|free)$"), "")
        } catch (e: PackageManager.NameNotFoundException) {
            packageName // Fallback to package name if app name not found
        }
    }

    // Traverse the node tree to find clickable, focusable, or long-clickable elements
    private fun findInteractiveNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable || node.isFocusable || node.isLongClickable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val interactiveChild = findInteractiveNode(child)
            if (interactiveChild != null) {
                child?.recycle()
                return interactiveChild
            }
            child?.recycle()
        }
        return null
    }

    // Get the hierarchy of the node for more context
    private fun getNodeHierarchy(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null) return ""
        val indent = "  ".repeat(depth)
        val viewId = node.viewIdResourceName?.split("/")?.lastOrNull() ?: "Unknown ID"
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: "Unknown Class"
        val description = when {
            text.isNotEmpty() -> "Text: $text"
            else -> "ID: $viewId"
        }
        val currentNode = "$indent$className ($description)\n"
        var hierarchy = currentNode
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            hierarchy += getNodeHierarchy(child, depth + 1)
            child?.recycle()
        }
        return hierarchy
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            // Check if we need to reset counts for a new day
            resetDailyCountsIfNeeded()

            val packageName = it.packageName?.toString() ?: return
            // Skip system UI packages
            if (packageName.contains("com.android.systemui") || packageName.contains("launcher")) {
                Log.d("AccessibilityService", "Skipping system package: $packageName")
                return
            }

            // Cache the app name for all events
            if (!appNames.containsKey(packageName)) {
                appNames[packageName] = getAppName(packageName)
            }

            when (it.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    // Handle Clicked and Long Clicked events
                    val eventText = when (it.eventType) {
                        AccessibilityEvent.TYPE_VIEW_CLICKED -> "Clicked"
                        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "Long Clicked"
                        else -> return@let
                    }

                    val contentDescription = it.contentDescription?.toString() ?: "No description"
                    val eventTime = getCurrentTime()

                    // Use AccessibilityNodeInfo to get more details
                    var clickDetails = "(Package: $packageName)"
                    val source: AccessibilityNodeInfo? = it.source
                    val interactiveNode = findInteractiveNode(source)
                    if (interactiveNode != null) {
                        val viewId = interactiveNode.viewIdResourceName?.split("/")?.lastOrNull() ?: "Unknown ID"
                        val text = interactiveNode.text?.toString() ?: interactiveNode.contentDescription?.toString() ?: ""
                        val details = if (text.isNotEmpty()) "Text: $text" else "ID: $viewId"
                        clickDetails = "($details in ${appNames[packageName] ?: packageName})\nHierarchy:\n${getNodeHierarchy(interactiveNode)}"
                        Log.d("AccessibilityService", "Captured click: $details in $packageName")
                    } else {
                        // Fallback to root window if source node lacks details
                        val rootNode = rootInActiveWindow
                        val rootInteractiveNode = findInteractiveNode(rootNode)
                        if (rootInteractiveNode != null) {
                            val viewId = rootInteractiveNode.viewIdResourceName?.split("/")?.lastOrNull() ?: "Unknown ID"
                            val text = rootInteractiveNode.text?.toString() ?: rootInteractiveNode.contentDescription?.toString() ?: ""
                            val details = if (text.isNotEmpty()) "Text: $text" else "ID: $viewId"
                            clickDetails = "(Root Window - $details in ${appNames[packageName] ?: packageName})\nHierarchy:\n${getNodeHierarchy(rootInteractiveNode)}"
                            Log.d("AccessibilityService", "Captured click from root window: $details in $packageName")
                            rootInteractiveNode.recycle()
                        } else {
                            Log.d("AccessibilityService", "No interactive node found for event in $packageName, using contentDescription: $contentDescription")
                            clickDetails = "(${contentDescription} in ${appNames[packageName] ?: packageName})"
                        }
                        rootNode?.recycle()
                    }

                    // Create a formatted event message
                    val eventMessage = "$eventTime - $eventText: $contentDescription $clickDetails"

                    // Log to Logcat
                    Log.d("AccessibilityService", eventMessage)

                    // Emit the event to the eventFlow
                    eventFlow.value = eventMessage

                    // Clean up the source node to prevent memory leaks
                    interactiveNode?.recycle()
                    source?.recycle()

                    // Update app usage time for the current app
                    if (lastPackageName != null && lastOpenTime != 0L) {
                        val timeSpent = System.currentTimeMillis() - lastOpenTime
                        appUsageTimes[lastPackageName!!] = (appUsageTimes[lastPackageName!!] ?: 0L) + timeSpent

                        // Update the session with closing time
                        val closingTimeFormatted = formatTime(System.currentTimeMillis())
                        val sessionToUpdate = appSessions.lastOrNull { session ->
                            session.packageName == lastPackageName && session.closingTime == null
                        }
                        if (sessionToUpdate != null) {
                            val updatedSession = sessionToUpdate.copy(
                                closingTime = closingTimeFormatted,
                                sessionDurationMs = timeSpent
                            )
                            appSessions[appSessions.indexOf(sessionToUpdate)] = updatedSession
                            appSessionFlow.value = appSessions.toList()
                        }
                    }

                    // Increment open count and start a new session
                    if (packageName != lastPackageName) {
                        appOpenCounts[packageName] = (appOpenCounts[packageName] ?: 0) + 1
                        lastOpenTime = System.currentTimeMillis()
                        lastAppOpenedTimeFormatted = formatTime(lastOpenTime)
                        appSessions.add(
                            AppSession(
                                packageName = packageName,
                                appName = appNames[packageName] ?: packageName,
                                openedTime = lastAppOpenedTimeFormatted!!,
                                closingTime = null,
                                sessionDurationMs = 0L
                            )
                        )
                        appSessionFlow.value = appSessions.toList()
                        Log.d("AccessibilityService", "$packageName opened at $lastAppOpenedTimeFormatted (${appOpenCounts[packageName]} times today)")
                    }

                    // Update the current app
                    lastPackageName = packageName

                    // Emit updated app usage data
                    appUsageFlow.value = appUsageTimes.mapValues { entry ->
                        val timeSpent = entry.value
                        val openCount = appOpenCounts[entry.key] ?: 0
                        val appName = appNames[entry.key] ?: entry.key
                        Triple(timeSpent, openCount, appName)
                    }
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    // Handle Focused events
                    val eventTime = getCurrentTime()
                    val contentDescription = it.contentDescription?.toString() ?: "No description"

                    // Use AccessibilityNodeInfo to get more details
                    var focusDetails = "(Package: $packageName)"
                    val source: AccessibilityNodeInfo? = it.source
                    val interactiveNode = findInteractiveNode(source)
                    if (interactiveNode != null) {
                        val viewId = interactiveNode.viewIdResourceName?.split("/")?.lastOrNull() ?: "Unknown ID"
                        val text = interactiveNode.text?.toString() ?: interactiveNode.contentDescription?.toString() ?: ""
                        val details = if (text.isNotEmpty()) "Text: $text" else "ID: $viewId"
                        focusDetails = "($details in ${appNames[packageName] ?: packageName})\nHierarchy:\n${getNodeHierarchy(interactiveNode)}"
                        Log.d("AccessibilityService", "Captured focus: $details in $packageName")
                    } else {
                        // Fallback to root window if source node lacks details
                        val rootNode = rootInActiveWindow
                        val rootInteractiveNode = findInteractiveNode(rootNode)
                        if (rootInteractiveNode != null) {
                            val viewId = rootInteractiveNode.viewIdResourceName?.split("/")?.lastOrNull() ?: "Unknown ID"
                            val text = rootInteractiveNode.text?.toString() ?: rootInteractiveNode.contentDescription?.toString() ?: ""
                            val details = if (text.isNotEmpty()) "Text: $text" else "ID: $viewId"
                            focusDetails = "(Root Window - $details in ${appNames[packageName] ?: packageName})\nHierarchy:\n${getNodeHierarchy(rootInteractiveNode)}"
                            Log.d("AccessibilityService", "Captured focus from root window: $details in $packageName")
                            rootInteractiveNode.recycle()
                        } else {
                            Log.d("AccessibilityService", "No interactive node found for focus event in $packageName, using contentDescription: $contentDescription")
                            focusDetails = "(${contentDescription} in ${appNames[packageName] ?: packageName})"
                        }
                        rootNode?.recycle()
                    }

                    // Create a formatted event message
                    val eventMessage = "$eventTime - Focused: $contentDescription $focusDetails"

                    // Log to Logcat
                    Log.d("AccessibilityService", eventMessage)

                    // Emit the event to the eventFlow
                    eventFlow.value = eventMessage

                    // Clean up the source node to prevent memory leaks
                    interactiveNode?.recycle()
                    source?.recycle()
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // Handle app switches to track time spent and open counts
                    val currentTime = System.currentTimeMillis()

                    // Update time spent in the previous app
                    if (lastPackageName != null && lastOpenTime != 0L) {
                        val timeSpent = currentTime - lastOpenTime
                        appUsageTimes[lastPackageName!!] = (appUsageTimes[lastPackageName!!] ?: 0L) + timeSpent

                        // Update the session with closing time
                        val closingTimeFormatted = formatTime(currentTime)
                        val sessionToUpdate = appSessions.lastOrNull { session ->
                            session.packageName == lastPackageName && session.closingTime == null
                        }
                        if (sessionToUpdate != null) {
                            val updatedSession = sessionToUpdate.copy(
                                closingTime = closingTimeFormatted,
                                sessionDurationMs = timeSpent
                            )
                            appSessions[appSessions.indexOf(sessionToUpdate)] = updatedSession
                            appSessionFlow.value = appSessions.toList()
                            Log.d("AccessibilityService", "$lastPackageName closed at $closingTimeFormatted (Duration: $timeSpent ms)")
                        }
                    }

                    // Increment open count and start a new session for the new app
                    if (packageName != lastPackageName) {
                        appOpenCounts[packageName] = (appOpenCounts[packageName] ?: 0) + 1
                        lastOpenTime = currentTime
                        lastAppOpenedTimeFormatted = formatTime(lastOpenTime)
                        appSessions.add(
                            AppSession(
                                packageName = packageName,
                                appName = appNames[packageName] ?: packageName,
                                openedTime = lastAppOpenedTimeFormatted!!,
                                closingTime = null,
                                sessionDurationMs = 0L
                            )
                        )
                        appSessionFlow.value = appSessions.toList()
                        Log.d("AccessibilityService", "$packageName opened at $lastAppOpenedTimeFormatted (${appOpenCounts[packageName]} times today)")
                    }

                    // Update the current app
                    lastPackageName = packageName

                    // Emit the updated app usage data
                    appUsageFlow.value = appUsageTimes.mapValues { entry ->
                        val timeSpent = entry.value
                        val openCount = appOpenCounts[entry.key] ?: 0
                        val appName = appNames[entry.key] ?: entry.key
                        Triple(timeSpent, openCount, appName)
                    }
                }
                else -> {
                    Log.d("AccessibilityService", "Unhandled event type: ${it.eventType} in $packageName")
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
        lastOpenTime = System.currentTimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Update time spent in the last app before the service is destroyed
        if (lastPackageName != null && lastOpenTime != 0L) {
            val timeSpent = System.currentTimeMillis() - lastOpenTime
            appUsageTimes[lastPackageName!!] = (appUsageTimes[lastPackageName!!] ?: 0L) + timeSpent

            // Update the session with closing time
            val closingTimeFormatted = formatTime(System.currentTimeMillis())
            val sessionToUpdate = appSessions.lastOrNull { session ->
                session.packageName == lastPackageName && session.closingTime == null
            }
            if (sessionToUpdate != null) {
                val updatedSession = sessionToUpdate.copy(
                    closingTime = closingTimeFormatted,
                    sessionDurationMs = timeSpent
                )
                appSessions[appSessions.indexOf(sessionToUpdate)] = updatedSession
                appSessionFlow.value = appSessions.toList()
            }

            appUsageFlow.value = appUsageTimes.mapValues { entry ->
                val timeSpent = entry.value
                val openCount = appOpenCounts[entry.key] ?: 0
                val appName = appNames[entry.key] ?: entry.key
                Triple(timeSpent, openCount, appName)
            }
        }
    }
}