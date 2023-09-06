package eu.pretix.pretixscan.droid

import android.content.Context
import com.facebook.flipper.core.FlipperClient
import com.facebook.flipper.plugins.crashreporter.CrashReporterPlugin
import com.facebook.flipper.plugins.databases.DatabasesFlipperPlugin
import com.facebook.flipper.plugins.inspector.DescriptorMapping
import com.facebook.flipper.plugins.inspector.InspectorFlipperPlugin
import com.facebook.flipper.plugins.navigation.NavigationFlipperPlugin
import com.facebook.flipper.plugins.network.FlipperOkhttpInterceptor
import com.facebook.flipper.plugins.network.NetworkFlipperPlugin
import com.facebook.flipper.plugins.sharedpreferences.SharedPreferencesFlipperPlugin
import okhttp3.Interceptor


object FlipperInitializer {
    fun initFlipperPlugins(context: Context, client: FlipperClient): IntializationResult {
        val descriptorMapping = DescriptorMapping.withDefaults()
        val networkPlugin = NetworkFlipperPlugin()
        val interceptor = FlipperOkhttpInterceptor(networkPlugin, true)
        client.addPlugin(InspectorFlipperPlugin(context, descriptorMapping))
        client.addPlugin(networkPlugin)
        client.addPlugin(CrashReporterPlugin.getInstance())
        client.addPlugin(DatabasesFlipperPlugin(context))
        client.addPlugin(NavigationFlipperPlugin.getInstance())
        client.addPlugin(SharedPreferencesFlipperPlugin(context))
        client.start()

        return object : IntializationResult {
            override val interceptor: Interceptor?
                get() = interceptor
        }
    }

    interface IntializationResult {
        val interceptor: Interceptor?
    }
}
