package eu.pretix.pretixscan.droid


import eu.pretix.libpretixsync.SentryInterface
import io.sentry.Sentry
import io.sentry.event.BreadcrumbBuilder


class AndroidSentryImplementation : SentryInterface {
    override fun addHttpBreadcrumb(url: String, method: String, statusCode: Int) {
        Sentry.getContext().recordBreadcrumb(
                BreadcrumbBuilder().setMessage("$method $url [$statusCode]").build()
        )
    }

    override fun addBreadcrumb(a: String, b: String) {
        Sentry.getContext().recordBreadcrumb(
                BreadcrumbBuilder().setMessage("$a $b").build()
        )
    }

    override fun captureException(t: Throwable) {
        Sentry.capture(t)
    }

    override fun captureException(t: Throwable, message: String) {
        Sentry.getContext().recordBreadcrumb(
                BreadcrumbBuilder().setMessage(message).build()
        )
        Sentry.capture(t)
    }
}
