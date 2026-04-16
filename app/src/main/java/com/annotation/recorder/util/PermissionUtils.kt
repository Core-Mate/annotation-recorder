package com.annotation.recorder.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.annotation.recorder.accessibility.RecorderAccessibilityService

object PermissionUtils {
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, RecorderAccessibilityService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val enabledServices = manager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()

        val byManager = enabledServices.any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            expected.packageName == serviceInfo.packageName && expected.className == serviceInfo.name
        }
        if (byManager) return true

        val settingValue = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(settingValue) }
        splitter.forEach { entry ->
            val cn = ComponentName.unflattenFromString(entry)
            if (cn != null && cn.packageName == expected.packageName && cn.className == expected.className) {
                return true
            }
        }
        return false
    }
}
