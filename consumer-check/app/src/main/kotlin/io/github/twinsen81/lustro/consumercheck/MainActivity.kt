package io.github.twinsen81.lustro.consumercheck

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Keeps the app in the foreground, where Lustro's server listens, so the build can be checked on a device. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Lustro consumer check" })
    }
}
