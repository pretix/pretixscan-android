package eu.pretix.pretixscan.droid

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class AndroidUserAgentInterceptor : Interceptor {
    private val ua = "${okhttp3.internal.userAgent} ${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"

    override fun intercept(chain: Interceptor.Chain): Response {
        val req: Request = chain.request()
        val uaReq: Request = req.newBuilder()
            .header("User-Agent", ua)
            .build()
        return chain.proceed(uaReq)
    }
}