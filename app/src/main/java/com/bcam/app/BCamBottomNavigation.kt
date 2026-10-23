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

/** Two destinations shared by BCam's full-screen Settings and About & updates pages. */
object BCamBottomNavigation {
    enum class Tab { SETTINGS, UPDATES }

    fun create(activity: Activity, selected: Tab): View {
        fun dp(n: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), activity.resources.displayMetrics
        ).toInt()
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun tab(destination: Tab, icon: String, label: String) {
            val active = destination == selected
            bar.addView(TextView(activity).apply {
                text = "$icon  $label"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFFB8C5D6.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(if (active) 0xFF345FAD.toInt() else 0xFF30343B.toInt())
                }
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
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        tab(Tab.SETTINGS, "⚙", "Settings")
        tab(Tab.UPDATES, "↻", "Updates")
        return bar
    }
}
