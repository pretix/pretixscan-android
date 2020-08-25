package eu.pretix.pretixscan.droid

import android.content.Context
import com.facebook.flipper.core.FlipperClient
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit


object FlipperInitializer {
    fun initFlipperPlugins(context: Context, client: FlipperClient): IntializationResult {
        return object : IntializationResult {
            override val interceptor: Interceptor?
                get() = null
        }
    }

    interface IntializationResult {
        val interceptor: Interceptor?
    }
}