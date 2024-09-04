package eu.pretix.pretixscan.droid


import eu.pretix.libpretixsync.SentryInterface
import io.sentry.Breadcrumb
import io.sentry.Sentry


class AndroidSentryImplementation : SentryInterface {
    override fun addHttpBreadcrumb(url: String, method: String, statusCode: Int) {
        val breadcrumb = Breadcrumb().apply {
            category = "http"
            message = "$method $url [$statusCode]"
        }
        Sentry.addBreadcrumb(breadcrumb)
    }

    override fun addBreadcrumb(a: String, b: String) {
        val breadcrumb = Breadcrumb().apply {
            message = "$a $b"
        }
        Sentry.addBreadcrumb(breadcrumb)
    }

    override fun captureException(t: Throwable) {
        Sentry.captureException(t)
    }

    override fun captureException(t: Throwable, message: String) {
        Sentry.captureMessage(message)
        Sentry.captureException(t)
    }
}
