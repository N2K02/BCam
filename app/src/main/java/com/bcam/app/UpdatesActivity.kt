package com.bcam.app

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Dedicated full-screen About & updates page, separate from recording settings. */
class UpdatesActivity : Activity() {
    private var updater: UpdateSection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "About & updates"
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val section = UpdateSection(this)
        updater = section
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            addView(Button(this@UpdatesActivity).apply {
                text = "← Back to settings"
                isAllCaps = false
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            addView(TextView(this@UpdatesActivity).apply {
                text = "About & updates"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setPadding(0, dp(12), 0, dp(12))
            })
            addView(TextView(this@UpdatesActivity).apply {
                text = "BCam"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            addView(TextView(this@UpdatesActivity).apply {
                text = "Version " + AppUpdater.installedVersionName(this@UpdatesActivity)
                setPadding(0, dp(8), 0, dp(16))
            })
            addView(section.view)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        if (intent.getBooleanExtra(SettingsActivity.EXTRA_CHECK_UPDATES, false)) section.check()
    }

    override fun onResume() {
        super.onResume()
        updater?.onResume()
    }

    override fun onDestroy() {
        updater?.close()
        super.onDestroy()
    }
}
