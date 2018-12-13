package eu.pretix.pretixscan.droid

import com.facebook.stetho.okhttp3.StethoInterceptor

import eu.pretix.libpretixsync.api.HttpClientFactory
import eu.pretix.pretixscan.droid.BuildConfig
import okhttp3.OkHttpClient

class AndroidHttpClientFactory : HttpClientFactory {
    override fun buildClient(): OkHttpClient {
        return if (BuildConfig.DEBUG) {
            OkHttpClient.Builder()
                    .addNetworkInterceptor(StethoInterceptor())
                    .build()
        } else {
            OkHttpClient.Builder()
                    .build()
        }
    }
}
