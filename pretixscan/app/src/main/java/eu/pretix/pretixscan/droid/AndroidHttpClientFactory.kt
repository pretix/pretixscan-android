package eu.pretix.pretixscan.droid

import com.facebook.stetho.okhttp3.StethoInterceptor

import eu.pretix.libpretixsync.api.HttpClientFactory
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
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
            })

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.getSocketFactory()

            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0])
            builder.hostnameVerifier(object : HostnameVerifier {
                override fun verify(hostname: String, session: SSLSession): Boolean {
                    return true
                }
            })
        }

        return builder.build()
    }
}
