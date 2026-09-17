package me.ayuilos.miffan.data.repository;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/** Minimal runner: AndroidJUnitRunner relies on shared AndroidX classes stripped in Release. */
public final class SshReleaseTestRunner extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : arguments;
        InstrumentationRegistry.registerInstance(this, this.arguments);
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            String selected = arguments.getString("class",
                SshAlgorithmRetentionTest.class.getName() + "," + SshKeyReleaseCompatibilityTest.class.getName());
            String[] names = selected.split(",");
            Class<?>[] tests = new Class<?>[names.length];
            for (int i = 0; i < names.length; i++) tests[i] = Class.forName(names[i]);
            Result result = new JUnitCore().run(tests);
            StringBuilder stream = new StringBuilder();
            for (Failure failure : result.getFailures()) stream.append(failure.getTrace()).append('\n');
            stream.append("Tests run: ").append(result.getRunCount())
                .append(", failures: ").append(result.getFailureCount()).append('\n');
            report.putString("stream", stream.toString());
            report.putInt("tests", result.getRunCount());
            report.putInt("failures", result.getFailureCount());
            finish(result.wasSuccessful() ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
        } catch (Throwable failure) {
            report.putString("stream", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, report);
        }
    }
}
