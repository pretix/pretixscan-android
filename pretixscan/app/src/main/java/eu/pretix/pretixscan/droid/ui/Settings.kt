package eu.pretix.pretixscan.droid.ui


import android.app.AlarmManager
import android.app.Fragment
import android.app.FragmentTransaction
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceFragment
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.TextView
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NavUtils
import eu.pretix.libpretixsync.Models
import eu.pretix.libpretixsync.db.ResourceLastModified
import eu.pretix.pretixscan.droid.AppConfig
import eu.pretix.pretixscan.droid.BuildConfig
import eu.pretix.pretixscan.droid.PretixScan
import eu.pretix.pretixscan.droid.R
import org.jetbrains.anko.alert
import org.jetbrains.anko.appcompat.v7.Appcompat
import org.jetbrains.anko.noButton
import org.jetbrains.anko.toast
import org.jetbrains.anko.yesButton
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader


class PinSettingsFragment : PreferenceFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addPreferencesFromResource(R.xml.preferences_pin)
    }
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
        findPreference("version")?.summary = BuildConfig.VERSION_NAME
        findPreference("full_resync")?.setOnPreferenceClickListener {
            (activity!!.application as PretixScan).data.delete(ResourceLastModified::class.java).get().value();
            toast("OK")
            return@setOnPreferenceClickListener true
        }
        findPreference("full_delete")?.setOnPreferenceClickListener {
            alert(Appcompat, R.string.full_delete_confirm) {
                yesButton {
                    val conf = AppConfig(activity)
                    conf.resetDeviceConfig()

                    activity.deleteDatabase(Models.DEFAULT.name)

                    val mStartActivity = Intent(activity, WelcomeActivity::class.java)
                    val mPendingIntentId = 123456
                    val mPendingIntent = PendingIntent.getActivity(activity, mPendingIntentId, mStartActivity, PendingIntent.FLAG_CANCEL_CURRENT)
                    val mgr = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, mPendingIntent)
                    System.exit(0)

                }
                noButton { }
            }.show()
            return@setOnPreferenceClickListener true
        }

        findPreference("pref_print_badges")?.setOnPreferenceChangeListener { preference, any ->
            if (any == true) {
                if (!isPackageInstalled("eu.pretix.pretixprint", activity.packageManager)
                        && !isPackageInstalled("eu.pretix.pretixprint.debug", activity.packageManager)
                        && !isPackageInstalled("de.silpion.bleuartcompanion", activity.packageManager)) {
                    alert(Appcompat, R.string.preference_badgeprint_install_pretixprint) {
                        yesButton {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=eu.pretix.pretixprint")))
                            } catch (anfe: android.content.ActivityNotFoundException) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=eu.pretix.pretixprint")))
                            }
                        }
                        noButton {}
                    }.show()
                    return@setOnPreferenceChangeListener false
                }
            }
            return@setOnPreferenceChangeListener true
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
        if (c.requiresPin("settings") && (!intent.hasExtra("pin") || !c.verifyPin(intent.getStringExtra("pin")))) {
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
        val transaction= fragmentManager.beginTransaction()
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

