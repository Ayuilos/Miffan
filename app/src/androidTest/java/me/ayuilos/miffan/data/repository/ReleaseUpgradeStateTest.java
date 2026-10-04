package me.ayuilos.miffan.data.repository;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

/** Seed an installed previous Release, then verify the same state after an in-place upgrade. */
public class ReleaseUpgradeStateTest {
    private static final String ID = "c3a686e8-6af6-46bb-a405-e3a90bd8d944";
    private static final String TITLE = "Miffan release upgrade fixture";

    @Test public void preservesConversationPreferenceAndWorkspaceFile() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File database = context.getDatabasePath("rikka_hub");
        assertTrue("Launch the previous production app before seeding", database.isFile());
        File marker = new File(context.getFilesDir(), "release-upgrade-workspace/reference.txt");
        boolean seed = "seed".equals(InstrumentationRegistry.getArguments().getString("phase"));
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            if (seed) {
                ContentValues row = new ContentValues();
                row.put("id", ID);
                row.put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e");
                row.put("title", TITLE);
                row.put("nodes", "[]");
                row.put("create_at", System.currentTimeMillis());
                row.put("update_at", System.currentTimeMillis());
                row.put("suggestions", "[]");
                row.put("is_pinned", 1);
                row.put("workspace_cwd", "release-upgrade-workspace");
                assertTrue(db.insertWithOnConflict("ConversationEntity", null, row, SQLiteDatabase.CONFLICT_REPLACE) != -1);
                assertTrue(marker.getParentFile().mkdirs() || marker.getParentFile().isDirectory());
                Files.write(marker.toPath(), "synthetic workspace content".getBytes(StandardCharsets.UTF_8));
                assertTrue(context.getSharedPreferences("release-upgrade-fixture", Context.MODE_PRIVATE)
                    .edit().putString("setting", "credential-free").commit());
            }
            try (Cursor cursor = db.rawQuery("SELECT title, is_pinned, workspace_cwd FROM ConversationEntity WHERE id = ?", new String[]{ID})) {
                assertTrue("Existing conversation survives", cursor.moveToFirst());
                assertEquals(TITLE, cursor.getString(0));
                assertEquals(1, cursor.getInt(1));
                assertEquals("release-upgrade-workspace", cursor.getString(2));
            }
            assertEquals("credential-free", context.getSharedPreferences("release-upgrade-fixture", Context.MODE_PRIVATE).getString("setting", null));
            assertEquals("synthetic workspace content", new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
        }
    }
}
