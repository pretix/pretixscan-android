package eu.pretix.pretixscan.droid

import com.facebook.stetho.okhttp3.StethoInterceptor

import eu.pretix.libpretixsync.api.HttpClientFactory
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import javax.security.cert.CertificateException

class AndroidHttpClientFactory : HttpClientFactory {
    override fun buildClient(ignore_ssl: Boolean): OkHttpClient {
        val builder = if (BuildConfig.DEBUG) {
            OkHttpClient.Builder()
                    .addNetworkInterceptor(StethoInterceptor())
        } else {
            OkHttpClient.Builder()
        }

        builder.connectTimeout(30, TimeUnit.SECONDS)
        builder.readTimeout(30, TimeUnit.SECONDS)
        builder.writeTimeout(30, TimeUnit.SECONDS)

        if (ignore_ssl) {
            val trustAllCerts = arrayOf<X509TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return emptyArray()
                }

                @Throws(CertificateException::class)
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                }

                @Throws(CertificateException::class)
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                }

                fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, wat: String) {
                    // Called reflectively by
                    // https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/net/http/X509TrustManagerExtensions.java#64
                }
            })

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.getSocketFactory()

            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0])
            builder.hostnameVerifier(HostnameVerifier { hostname, session -> true })
        }

        return builder.build()
    }
}
