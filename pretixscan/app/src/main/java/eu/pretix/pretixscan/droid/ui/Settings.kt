package eu.pretix.pretixscan.droid.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NavUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import splitties.toast.toast

class PinSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_pin, rootKey)

        findPreference<EditTextPreference>("pref_pin")?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
    }
}


class AutoOfflineListPreference : ListPreference {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
            super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    override fun onClick() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_auto_offline_preference, null)
        val builder = MaterialAlertDialogBuilder(context).apply {
            setSingleChoiceItems(entries, getValueIndex()) { dialog, index ->
                if (callChangeListener(entryValues[index].toString())) {
                    setValueIndex(index)
                }
                dialog.dismiss()
            }
            setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            setTitle(title)
            setView(view)
        }

        val dialog = builder.create()
        dialog.show()
    }

    private fun getValueIndex() = entryValues.indexOf(value)
}


class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("licenses")?.setOnPreferenceClickListener {
            asset_dialog(R.raw.about, R.string.settings_label_licenses)
            return@setOnPreferenceClickListener true
        }
        val conf = AppConfig(requireActivity())
        findPreference<CheckBoxPreference>("pref_scan_offline")?.isEnabled = !conf.proxyMode
        findPreference<AutoOfflineListPreference>("pref_auto_offline")?.isEnabled = !conf.proxyMode
        findPreference<CheckBoxPreference>("pref_auto_switch")?.isEnabled = conf.eventSelection.size == 1
        findPreference<CheckBoxPreference>("pref_sync_orders")?.isEnabled = !conf.proxyMode
        findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
        findPreference<Preference>("device_name")?.summary = conf.deviceKnownName
        findPreference<Preference>("full_resync")?.setOnPreferenceClickListener {
            // First, delete ResourceSyncStatus. This way the system forgets which data was already
            // pulled and will pull all lists completely instead of using If-Modified-Since or
            // ?modified_since= mechanisms
            (requireActivity().application as PretixScan).db.compatQueries.truncateResourceSyncStatus()

            // To make sync faster, we only update records in the local database if their `json_data`
            // column is different than what we received from the server. However, if there was a
            // bug in deriving the local database content from the `json_data` field this is a problem
            // since the code path that might fix it will  never run. Therefore we include a `syncCycleId`
            // in every JSON body to artificially trigger a change. In a similar way, the database schema
            // version number is also included to trigger a change if we change the database schema.
            conf.syncCycleId = System.currentTimeMillis().toString()

            conf.lastSync = 0
            conf.lastDownload = 0
            toast("OK")
            return@setOnPreferenceClickListener true
        }
        findPreference<CheckBoxPreference>("pref_scan_offline")?.setOnPreferenceChangeListener { preference, newValue ->
            (requireActivity().application as PretixScan).connectivityHelper.resetHistory()
            true
        }
        findPreference<Preference>("full_delete")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setMessage(R.string.full_delete_confirm)
                setPositiveButton(R.string.yes) { dialog, _ ->
                    dialog.dismiss()
                    wipeApp(requireActivity(), true)
                }
                setNegativeButton(R.string.no) { dialog, _ -> dialog.cancel() }
            }.create().show()
            return@setOnPreferenceClickListener true
        }

        findPreference<CheckBoxPreference>("pref_print_badges")?.setOnPreferenceChangeListener { preference, any ->
            if (any == true) {
                if (!isPackageInstalled("eu.pretix.pretixprint", requireActivity().packageManager)
                    && !isPackageInstalled("eu.pretix.pretixprint.debug", requireActivity().packageManager)
                    && !isPackageInstalled("de.silpion.bleuartcompanion", requireActivity().packageManager)
                ) {
                    MaterialAlertDialogBuilder(requireContext()).apply {
                        setMessage(R.string.preference_badgeprint_install_pretixprint)
                        setPositiveButton(R.string.yes) { dialog, _ ->
                            dialog.dismiss()
                            try {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("market://details?id=eu.pretix.pretixprint")
                                    )
                                )
                            } catch (anfe: android.content.ActivityNotFoundException) {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://play.google.com/store/apps/details?id=eu.pretix.pretixprint")
                                    )
                                )
                            }
                        }
                        setNegativeButton(R.string.no) { dialog, _ -> dialog.cancel() }
                    }.create().show()
                    return@setOnPreferenceChangeListener false
                }
            }
            return@setOnPreferenceChangeListener true
        }

        findPreference<Preference>("datawedge_install")?.apply {
            isEnabled =
                DataWedgeHelper(requireActivity()).isInstalled || Build.BRAND == "Zebra"
            setOnPreferenceClickListener {
                DataWedgeHelper(requireActivity()).install(true)
                toast("OK")
                true
            }
        }
    }

    private fun asset_dialog(@RawRes htmlRes: Int, @StringRes title: Int) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_about, null, false)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.dismiss, null)
            .create()

        val textView = view.findViewById(R.id.aboutText) as TextView

        var text = ""

        val builder = StringBuilder()
        val fis: InputStream
        try {
            fis = resources.openRawResource(htmlRes)
            val reader = BufferedReader(InputStreamReader(fis, "utf-8"))
            while (true) {
                val line = reader.readLine()
                if (line != null) {
                    builder.append(line)
                } else {
                    break
                }
            }

            text = builder.toString()
            fis.close()
        } catch (e: IOException) {
            //Sentry.captureException(e)
            e.printStackTrace()
        }

        textView.text = Html.fromHtml(text)
        textView.movementMethod = LinkMovementMethod.getInstance()

        dialog.show()
    }
}

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generic_fragment)
        setSupportActionBar(findViewById(R.id.topAppBar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.content)
        ) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = insets.left,
                right = insets.right,
                top = 0, // handled by AppBar
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        val c = AppConfig(this)
        if (c.requiresPin("settings") && (!intent.hasExtra("pin") || !c.verifyPin(
                intent.getStringExtra(
                    "pin"
                )!!
            ))
        ) {
            // Protect against external calls
            finish()
            return
        }

        // Display the fragment as the main content.
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, SettingsFragment())
            .commit()
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount == 0) {
            NavUtils.navigateUpFromSameTask(this)
        } else {
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Respond to the action bar's Up/Home button
            android.R.id.home -> {
                if (supportFragmentManager.backStackEntryCount == 0) {
                    NavUtils.navigateUpFromSameTask(this)
                } else {
                    supportFragmentManager.popBackStack()
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}

