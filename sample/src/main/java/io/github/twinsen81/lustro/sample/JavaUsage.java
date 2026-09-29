package io.github.twinsen81.lustro.sample;

import io.github.twinsen81.lustro.DebugConfig;
import io.github.twinsen81.lustro.DebugResponse;
import io.github.twinsen81.lustro.Headers;
import io.github.twinsen81.lustro.network.DefaultRedactor;
import io.github.twinsen81.lustro.network.NetworkClassifierKt;
import io.github.twinsen81.lustro.network.NetworkDebugTab;
import okhttp3.OkHttpClient;

/**
 * Java interop compile test for the Lustro public API.
 *
 * <p>This class only needs to COMPILE. It exercises {@code @JvmStatic} factories
 * and {@code @JvmOverloads} overloads from Java to prove the public API is usable
 * without Kotlin-only call conventions. It is compiled by both the debug variant
 * (against {@code :lustro}) and the release variant (against {@code :lustro-noop}).
 */
public final class JavaUsage {
    private JavaUsage() {
    }

    /** Touches the public API surface from Java. */
    public static void exercise() {
        // @JvmStatic builder + value object.
        DebugConfig config = DebugConfig.builder()
                .serverPort(8080)
                .build();

        // @JvmStatic @JvmOverloads SAFE factory (no capturePlatformHttp, no
        // opt-in) — call with no optional args, then the leading positional one.
        NetworkDebugTab tab = NetworkDebugTab.create();
        NetworkDebugTab tabWithSender = NetworkDebugTab.create((OkHttpClient) null);

        // The experimental @ExperimentalPlatformCapture overload (with the
        // required capturePlatformHttp flag) is exercised from Kotlin in
        // LustroBootstrap.start (capturePlatformHttp = true); from Java the safe
        // overload above is the documented entry point.
        NetworkDebugTab tabWithFlag = tabWithSender;

        // A capture filter as a Java lambda, through the SAM conversion. It is the
        // last parameter, so Java passes the ones before it too.
        NetworkDebugTab filtered = NetworkDebugTab.create(
                null,
                NetworkClassifierKt.getNoOpNetworkClassifier(),
                DefaultRedactor.INSTANCE,
                null,
                request -> !request.getUrl().contains("/analytics/"));

        // @JvmStatic / @JvmField factories on the api facades.
        Headers headers = Headers.of();
        DebugResponse response = DebugResponse.ok("{}");

        // Keep the locals "used" so this compiles cleanly under -Werror-style lint.
        if (config == null || tab == null || tabWithSender == null
                || tabWithFlag == null || filtered == null || headers == null || response == null) {
            throw new IllegalStateException("unreachable");
        }
    }
}
