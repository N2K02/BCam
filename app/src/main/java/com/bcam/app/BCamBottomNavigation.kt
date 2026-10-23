package com.bcam.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.PopupMenu

/** MRadio-style persistent bottom navigation: icon indicator above a text label. */
object BCamBottomNavigation {
    enum class Tab { SETTINGS, UPDATES }

    fun create(activity: Activity, selected: Tab): View {
        fun dp(n: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), activity.resources.displayMetrics
        ).toInt()
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF222326.toInt())
        }
        fun tab(destination: Tab, symbol: String, label: String) {
            val active = destination == selected
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = label
                setOnClickListener {
                    if (!active) {
                        val target = if (destination == Tab.SETTINGS) SettingsActivity::class.java
                            else UpdatesActivity::class.java
                        activity.startActivity(Intent(activity, target).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                        activity.finish()
                    }
                }
            }
            item.addView(TextView(activity).apply {
                text = symbol
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(if (active) 0xFFE0E8FF.toInt() else 0xFFBEC0C8.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (active) 0xFF405B8B.toInt() else 0xFF222326.toInt())
                }
            }, LinearLayout.LayoutParams(dp(68), dp(34)))
            item.addView(TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFFBEC0C8.toInt())
            }, LinearLayout.LayoutParams(-1, dp(24)))
            bar.addView(item, LinearLayout.LayoutParams(0, dp(64), 1f))
        }
        tab(Tab.SETTINGS, "⚙", "Settings")
        tab(Tab.UPDATES, "↻", "Updates")
        val more = TextView(activity).apply {
            text = "⋮  More"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFFBEC0C8.toInt())
            isClickable = true
            isFocusable = true
            contentDescription = "More options"
            setOnClickListener { anchor ->
                PopupMenu(activity, anchor).apply {
                    menu.add("Exit without saving settings").setOnMenuItemClickListener {
                        if (RecordService.isRunning) {
                            activity.startService(Intent(activity, RecordService::class.java)
                                .setAction(RecordService.ACTION_STOP))
                        }
                        activity.finishAndRemoveTask()
                        true
                    }
                    show()
                }
            }
        }
        bar.addView(more, LinearLayout.LayoutParams(dp(88), dp(64)))
        return bar
    }
}
