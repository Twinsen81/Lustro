package io.github.twinsen81.lustro.consumercheck;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import androidx.work.Configuration;
import io.github.twinsen81.lustro.Lustro;
import io.github.twinsen81.lustro.logs.LogsDebugTab;
import io.github.twinsen81.lustro.network.NetworkDebugTab;
import io.github.twinsen81.lustro.prefs.PrefsDebugTab;
import io.github.twinsen81.lustro.prefs.PrefsFile;
import io.github.twinsen81.lustro.prefs.ValuePolicy;
import io.github.twinsen81.lustro.sqlite.SqliteDb;
import io.github.twinsen81.lustro.sqlite.SqliteDebugTab;
import io.github.twinsen81.lustro.sqlite.SqliteValuePolicy;
import io.github.twinsen81.lustro.work.WorkDebugTab;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.OkHttpClient;

/**
 * The registration of every tab from Java, which the app never calls: it compiles
 * the factories as a Java app calls them, against `lustro` and the plugins in debug
 * and against their no-op halves in release. The app has no work-analytics, so an
 * overload whose parameter is a work-analytics type fails here, as it would in such
 * an app.
 */
final class JavaSetup {
    private JavaSetup() {
    }

    static Lustro build(Application app, SQLiteOpenHelper helper) {
        List<String> names = Collections.singletonList("sync");
        WorkDebugTab work = WorkDebugTab.create(app, names);
        WorkDebugTab.create(app);
        WorkDebugTab.create(app, names, 64 * 1024);
        work.configure(new Configuration.Builder()).build();
        LogsDebugTab.create(64 * 1024);
        PrefsDebugTab.create(app);
        return Lustro.builder(app)
                .addTab(NetworkDebugTab.create(new OkHttpClient()))
                .addTab(LogsDebugTab.create())
                .addTab(PrefsDebugTab.create(app, Collections.singletonList(
                        new PrefsFile(app.getPackageName() + "_preferences",
                                app.getSharedPreferences(app.getPackageName() + "_preferences", Context.MODE_PRIVATE),
                                ValuePolicy.VISIBLE))))
                .addTab(SqliteDebugTab.create(Arrays.asList(
                        SqliteDb.framework("app", true, Collections.emptyMap(), SqliteValuePolicy.MASK_SENSITIVE,
                                () -> Log.i("ConsumerCheck", "the tab wrote to app"),
                                helper::getWritableDatabase),
                        SqliteDb.framework("plain", helper::getReadableDatabase),
                        SqliteDb.file("cache", app.getDatabasePath("cache.db"))), app))
                .addTab(work)
                .build();
    }
}
