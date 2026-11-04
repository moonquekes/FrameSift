package cn.devcxl.photosync.album

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import cn.devcxl.photosync.R
import cn.devcxl.photosync.databinding.DialogBurstSettingsBinding
import cn.devcxl.photosync.databinding.DialogExifSettingsBinding
import cn.devcxl.photosync.databinding.ScreenAlbumSettingsBinding

/** One full-screen settings surface with swipeable tabs and persistent controls. */
internal class AlbumSettingsMenu(private val activity: ComponentActivity, private val preferences: AlbumPreferences) {
    fun show(
        initialPage: Int = 0, exifChanged: () -> Unit = {}, burstChanged: (BurstSettings) -> Unit = {}
    ) {
        val binding = ScreenAlbumSettingsBinding.inflate(activity.layoutInflater)
        val dialog = Dialog(activity, R.style.Theme_PhotoSync)
        binding.settingsPages.adapter = Pages(listOf(burstPage(burstChanged), exifPage(exifChanged)))
        binding.settingsPages.offscreenPageLimit = 1
        fun select(position: Int) {
            binding.settingsBurstTab.isSelected = position == 0
            binding.settingsExifTab.isSelected = position == 1
        }
        binding.settingsBurstTab.setOnClickListener { binding.settingsPages.currentItem = 0 }
        binding.settingsExifTab.setOnClickListener { binding.settingsPages.currentItem = 1 }
        binding.settingsPages.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { select(position) }
        })
        binding.settingsPages.setCurrentItem(initialPage, false)
        select(initialPage)
        binding.settingsBack.setOnClickListener { dialog.dismiss() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        dialog.setContentView(binding.root)
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            AlbumSystemBars.show(this)
        }
    }

    private fun burstPage(changed: (BurstSettings) -> Unit): View {
        val binding = DialogBurstSettingsBinding.inflate(activity.layoutInflater)
        val current = preferences.readBurst()
        binding.burstGap.progress = current.maxGapSeconds
        binding.burstMinimum.progress = current.minimumPhotos - 2
        binding.burstConsecutive.isChecked = current.requireConsecutive
        fun render() {
            binding.burstGapLabel.text = activity.getString(R.string.gallery_burst_gap, binding.burstGap.progress)
            binding.burstMinimumLabel.text = activity.getString(
                R.string.gallery_burst_minimum, binding.burstMinimum.progress + 2)
        }
        fun save() {
            val settings = BurstSettings(binding.burstGap.progress, binding.burstMinimum.progress + 2,
                binding.burstConsecutive.isChecked)
            preferences.saveBurst(settings)
            changed(settings)
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { render() }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) { save() }
        }
        binding.burstGap.setOnSeekBarChangeListener(listener)
        binding.burstMinimum.setOnSeekBarChangeListener(listener)
        binding.burstConsecutive.setOnCheckedChangeListener { _, _ -> save() }
        val reset = TextView(activity).apply {
            setText(R.string.gallery_settings_defaults); textSize = 14f
            setTextColor(activity.getColor(R.color.gallery_accent))
            minHeight = dp(56); gravity = android.view.Gravity.CENTER_VERTICAL
            setOnClickListener {
                binding.burstGap.progress = 1; binding.burstMinimum.progress = 1
                binding.burstConsecutive.isChecked = true
                render(); save()
            }
        }
        binding.root.addView(reset)
        render()
        return ScrollView(activity).apply { addView(binding.root) }
    }

    private fun exifPage(changed: () -> Unit): View {
        val binding = DialogExifSettingsBinding.inflate(activity.layoutInflater)
        binding.exifEnabled.isChecked = preferences.exifEnabled
        binding.exifEnabled.setOnCheckedChangeListener { _, value -> preferences.exifEnabled = value; changed() }
        val selected = preferences.exifFields
        ExifField.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { field ->
                val check = CheckBox(activity).apply {
                    setText(exifLabel(field)); isChecked = field in selected; textSize = 15f
                    setTextColor(activity.getColor(R.color.gallery_text)); minHeight = dp(60)
                    setOnCheckedChangeListener { _, checked ->
                        preferences.exifFields = if (checked) preferences.exifFields + field else preferences.exifFields - field
                        changed()
                    }
                }
                row.addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (pair.size == 1) row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            binding.exifFields.addView(row)
        }
        return binding.root
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private class Pages(private val pages: List<View>) : RecyclerView.Adapter<Pages.Holder>() {
        class Holder(val container: android.widget.FrameLayout) : RecyclerView.ViewHolder(container)
        override fun getItemCount() = pages.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(android.widget.FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages[position]
            (page.parent as? ViewGroup)?.removeView(page)
            holder.container.removeAllViews()
            holder.container.addView(page, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
}
