package eu.pretix.pretixscan.droid.ui


import android.app.Fragment
import android.app.FragmentTransaction
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceFragment
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.TextView
import androidx.annotation.RawRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NavUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.pretix.libpretixsync.db.ResourceSyncStatus
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import splitties.toast.toast

class PinSettingsFragment : PreferenceFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addPreferencesFromResource(R.xml.preferences_pin)
    }
}


class AutoOfflineListPreference : ListPreference {
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
            super(context, attrs, defStyleAttr, defStyleRes)

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) :
            super(context, attrs, defStyleAttr)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context?) : super(context)

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


class SettingsFragment : PreferenceFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addPreferencesFromResource(R.xml.preferences)

        findPreference("licenses").setOnPreferenceClickListener {
            asset_dialog(R.raw.about, R.string.settings_label_licenses)
            return@setOnPreferenceClickListener true
        }
        val conf = AppConfig(activity)
        findPreference("pref_scan_offline")?.isEnabled = !conf.proxyMode
        findPreference("pref_auto_offline")?.isEnabled = !conf.proxyMode
        findPreference("pref_auto_switch")?.isEnabled = conf.eventSelection.size == 1
        findPreference("pref_sync_orders")?.isEnabled = !conf.proxyMode
        findPreference("version")?.summary = BuildConfig.VERSION_NAME
        findPreference("full_resync")?.setOnPreferenceClickListener {
            // First, delete ResourceSyncStatus. This way the system forgets which data was already
            // pulled and will pull all lists completely instead of using If-Modified-Since or
            // ?modified_since= mechanisms
            (activity.application as PretixScan).data.delete(ResourceSyncStatus::class.java).get()
                .value();

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
        findPreference("pref_scan_offline")?.setOnPreferenceChangeListener { preference, newValue ->
            (activity.application as PretixScan).connectivityHelper.resetHistory()
            true
        }
        findPreference("full_delete")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(activity).apply {
                setMessage(R.string.full_delete_confirm)
                setPositiveButton(R.string.yes) { dialog, _ ->
                    dialog.dismiss()
                    wipeApp(activity!!)
                }
                setNegativeButton(R.string.no) { dialog, _ -> dialog.cancel() }
            }.create().show()
            return@setOnPreferenceClickListener true
        }

        findPreference("pref_print_badges")?.setOnPreferenceChangeListener { preference, any ->
            if (any == true) {
                if (!isPackageInstalled("eu.pretix.pretixprint", activity.packageManager)
                    && !isPackageInstalled("eu.pretix.pretixprint.debug", activity.packageManager)
                    && !isPackageInstalled("de.silpion.bleuartcompanion", activity.packageManager)
                ) {
                    MaterialAlertDialogBuilder(activity).apply {
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

        findPreference("datawedge_install")?.isEnabled =
            DataWedgeHelper(activity).isInstalled || Build.BRAND == "Zebra"
        findPreference("datawedge_install")?.setOnPreferenceClickListener {
            DataWedgeHelper(activity).install(true)
            toast("OK")
            true
        }

    }

    private fun asset_dialog(@RawRes htmlRes: Int, @StringRes title: Int) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_about, null, false)
        val dialog = AlertDialog.Builder(activity)
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

class SettingsActivity : AppCompatPreferenceActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupActionBar()

        val c = AppConfig(this)
        if (c.requiresPin("settings") && (!intent.hasExtra("pin") || !c.verifyPin(
                intent.getStringExtra(
                    "pin"
                )!!
            ))
        ) {
            // Protect against external calls
            finish();
            return
        }

        // Display the fragment as the main content.
        fragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    /**
     * Set up the [android.app.ActionBar], if the API is available.
     */
    private fun setupActionBar() {
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onBackPressed() {
        if (fragmentManager.backStackEntryCount == 0) {
            NavUtils.navigateUpFromSameTask(this)
        } else {
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Respond to the action bar's Up/Home button
            android.R.id.home -> {
                if (fragmentManager.backStackEntryCount == 0) {
                    NavUtils.navigateUpFromSameTask(this)
                } else {
                    fragmentManager.popBackStack()
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun isValidFragment(fragmentName: String): Boolean {
        return fragmentName.contains("SettingsFragment")
    }


    override fun onPreferenceStartFragment(caller: PreferenceFragment?, pref: Preference): Boolean {
        val f: Fragment = Fragment.instantiate(this, pref.fragment, pref.extras)
        val transaction = fragmentManager.beginTransaction()
        transaction.replace(android.R.id.content, f)
        if (pref.titleRes != 0) {
            transaction.setBreadCrumbTitle(pref.titleRes)
        } else if (pref.title != null) {
            transaction.setBreadCrumbTitle(pref.title)
        }
        transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
        transaction.addToBackStack(":android:prefs")
        transaction.commitAllowingStateLoss()
        return true
    }

    /*
    override fun onPreferenceStartFragment(caller: PreferenceFragment, pref: Preference): Boolean {
        // Instantiate the new Fragment
        val args = pref.extras
        val fragment = fragment.instantiate(
                classLoader,
                pref.fragment)
        fragment.arguments = args
        fragment.setTargetFragment(caller, 0)
        // Replace the existing Fragment with the new Fragment
        fragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .addToBackStack(null)
                .commit()
        return true
    }
     */
}

