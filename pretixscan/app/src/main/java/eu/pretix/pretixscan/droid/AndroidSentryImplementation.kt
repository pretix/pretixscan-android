package eu.pretix.pretixscan.droid


import eu.pretix.libpretixsync.SentryInterface
import io.sentry.Sentry


class AndroidSentryImplementation : SentryInterface {
    override fun addHttpBreadcrumb(url: String, method: String, statusCode: Int) {
        Sentry.addBreadcrumb("$method $url [$statusCode]")
    }

    override fun addBreadcrumb(a: String, b: String) {
        Sentry.addBreadcrumb("$a $b")
    }

    override fun captureException(t: Throwable) {
        Sentry.captureException(t)
    }

    override fun captureException(t: Throwable, message: String) {
        Sentry.addBreadcrumb(message)
        Sentry.captureException(t)
    }
}
