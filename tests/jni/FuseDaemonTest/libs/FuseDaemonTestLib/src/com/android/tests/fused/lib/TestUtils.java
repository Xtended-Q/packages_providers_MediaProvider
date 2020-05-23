/**
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tests.fused.lib;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.tests.fused.lib.RedactionTestHelper.EXIF_METADATA_QUERY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.TestApp;
import com.android.cts.install.lib.Uninstall;

import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * General helper functions for FuseDaemon tests.
 */
public class TestUtils {
    static final String TAG = "FuseDaemonTest";

    public static final String QUERY_TYPE = "com.android.tests.fused.queryType";
    public static final String INTENT_EXTRA_PATH = "com.android.tests.fused.path";
    public static final String INTENT_EXCEPTION = "com.android.tests.fused.exception";
    public static final String CREATE_FILE_QUERY = "com.android.tests.fused.createfile";
    public static final String DELETE_FILE_QUERY = "com.android.tests.fused.deletefile";
    public static final String OPEN_FILE_FOR_READ_QUERY = "com.android.tests.fused.openfile_read";
    public static final String OPEN_FILE_FOR_WRITE_QUERY = "com.android.tests.fused.openfile_write";
    public static final String CAN_READ_WRITE_QUERY = "com.android.tests.fused.can_read_and_write";
    public static final String READDIR_QUERY = "com.android.tests.fused.readdir";

    public static final String STR_DATA1 = "Just some random text";
    public static final String STR_DATA2 = "More arbitrary stuff";

    public static final byte[] BYTES_DATA1 = STR_DATA1.getBytes();
    public static final byte[] BYTES_DATA2 = STR_DATA2.getBytes();

    // Root of external storage
    public static final File EXTERNAL_STORAGE_DIR = Environment.getExternalStorageDirectory();
    // Default top-level directories
    public static final File ALARMS_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_ALARMS);
    public static final File ANDROID_DIR = new File(EXTERNAL_STORAGE_DIR,
            "Android");
    public static final File AUDIOBOOKS_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_AUDIOBOOKS);
    public static final File DCIM_DIR = new File(EXTERNAL_STORAGE_DIR, Environment.DIRECTORY_DCIM);
    public static final File DOCUMENTS_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_DOCUMENTS);
    public static final File DOWNLOAD_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_DOWNLOADS);
    public static final File MUSIC_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_MUSIC);
    public static final File MOVIES_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_MOVIES);
    public static final File NOTIFICATIONS_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_NOTIFICATIONS);
    public static final File PICTURES_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_PICTURES);
    public static final File PODCASTS_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_PODCASTS);
    public static final File RINGTONES_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_RINGTONES);

    public static final File[] DEFAULT_TOP_LEVEL_DIRS = new File [] { ALARMS_DIR, ANDROID_DIR,
            AUDIOBOOKS_DIR, DCIM_DIR, DOCUMENTS_DIR, DOWNLOAD_DIR, MUSIC_DIR, MOVIES_DIR,
            NOTIFICATIONS_DIR, PICTURES_DIR, PODCASTS_DIR, RINGTONES_DIR};

    public static final File ANDROID_DATA_DIR = new File(ANDROID_DIR, "data");
    public static final File ANDROID_MEDIA_DIR = new File(ANDROID_DIR, "media");

    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final long POLLING_SLEEP_MILLIS = 100;

    /**
     * Creates the top level default directories.
     *
     * <p>Those are usually created by MediaProvider, but some naughty tests might delete them
     * and not restore them afterwards. so we make sure we create them before we make any
     * assumptions about their existence.
     */
    public static void setupDefaultDirectories() {
        for (File dir : DEFAULT_TOP_LEVEL_DIRS) {
            dir.mkdir();
        }
    }

    /**
     * Grants {@link Manifest.permission#GRANT_RUNTIME_PERMISSIONS} to the given package.
     */
    public static void grantPermission(String packageName, String permission) {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS");
        try {
            uiAutomation.grantRuntimePermission(packageName, permission);
            // Wait for OP_READ_EXTERNAL_STORAGE to get updated.
            SystemClock.sleep(1000);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Revokes {@link Manifest.permission#GRANT_RUNTIME_PERMISSIONS} from the given package.
     */
    public static void revokePermission(String packageName, String permission) {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity("android.permission.REVOKE_RUNTIME_PERMISSIONS");
        try {
            uiAutomation.revokeRuntimePermission(packageName, permission);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    public static void adoptShellPermissionIdentity(String... permissions) {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(permissions);
    }

    public static void dropShellPermissionIdentity() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    public static String executeShellCommand(String cmd) throws Exception {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (FileInputStream output = new FileInputStream (uiAutomation.executeShellCommand(cmd)
                .getFileDescriptor())) {
            return new String(ByteStreams.toByteArray(output));
        }
    }

    /**
     * Makes the given {@code testApp} list the content of the given directory and returns the
     * result as an {@link ArrayList}
     */
    public static ArrayList<String> listAs(TestApp testApp, String dirPath)
            throws Exception {
        return getContentsFromTestApp(testApp, dirPath, READDIR_QUERY);
    }

    /**
     * Returns {@code true} iff the given {@code path} exists and is readable and
     * writable for for {@code testApp}.
     */
    public static boolean canReadAndWriteAs(TestApp testApp, String path)
            throws Exception {
        return getResultFromTestApp(testApp, path, CAN_READ_WRITE_QUERY);
    }

    /**
     * Makes the given {@code testApp} read the EXIF metadata from the given file and returns the
     * result as an {@link HashMap}
     */
    public static HashMap<String, String> readExifMetadataFromTestApp(TestApp testApp,
            String filePath) throws Exception {
        HashMap<String, String> res =
                getMetadataFromTestApp(testApp, filePath, EXIF_METADATA_QUERY);
        return res;
    }

    /**
     * Makes the given {@code testApp} create a file.
     *
     * <p>This method drops shell permission identity.
     */
    public static boolean createFileAs(TestApp testApp, String path) throws Exception {
        return getResultFromTestApp(testApp, path, CREATE_FILE_QUERY);
    }

    /**
     * Makes the given {@code testApp} delete a file.
     *
     * <p>This method drops shell permission identity.
     */
    public static boolean deleteFileAs(TestApp testApp, String path) throws Exception {
        return getResultFromTestApp(testApp, path, DELETE_FILE_QUERY);
    }

    /**
     * Makes the given {@code testApp} delete a file. Doesn't throw in case of failure.
     */
    public static boolean deleteFileAsNoThrow(TestApp testApp, String path) {
        try {
            return deleteFileAs(testApp, path);
        } catch (Exception e) {
            Log.e(TAG, "Error occurred while deleting file: " + path
                    + " on behalf of app: " + testApp, e);
            return false;
        }
    }

    /**
     * Makes the given {@code testApp} open {@code file} for read or write.
     *
     * <p>This method drops shell permission identity.
     */
    public static boolean openFileAs(TestApp testApp, File file, boolean forWrite)
            throws Exception {
        return openFileAs(testApp, file.getAbsolutePath(), forWrite);
    }

    /**
     * Makes the given {@code testApp} open a file for read or write.
     *
     * <p>This method drops shell permission identity.
     */
    public static boolean openFileAs(TestApp testApp, String path, boolean forWrite)
            throws Exception {
        return getResultFromTestApp(testApp, path,
                forWrite ? OPEN_FILE_FOR_WRITE_QUERY : OPEN_FILE_FOR_READ_QUERY);
    }

    /**
     * Installs a {@link TestApp} without storage permissions.
     */
    public static void installApp(TestApp testApp) throws Exception {
        installApp(testApp, /* grantStoragePermission */ false);
    }

    /**
     * Installs a {@link TestApp} with storage permissions.
     */
    public static void installAppWithStoragePermissions(TestApp testApp) throws Exception {
        installApp(testApp, /* grantStoragePermission */ true);
    }

    /**
     * Installs a {@link TestApp} and may grant it storage permissions.
     */
    public static void installApp(TestApp testApp, boolean grantStoragePermission)
            throws Exception {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            final String packageName = testApp.getPackageName();
            uiAutomation.adoptShellPermissionIdentity(Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES);
            if (InstallUtils.getInstalledVersion(packageName) != -1) {
                Uninstall.packages(packageName);
            }
            Install.single(testApp).commit();
            assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(1);
            if (grantStoragePermission) {
                grantPermission(packageName, Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Uninstalls a {@link TestApp}.
     */
    public static void uninstallApp(TestApp testApp) throws Exception {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            final String packageName = testApp.getPackageName();
            uiAutomation.adoptShellPermissionIdentity(Manifest.permission.DELETE_PACKAGES);

            Uninstall.packages(packageName);
            assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(-1);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Uninstalls a {@link TestApp}. Doesn't throw in case of failure.
     */
    public static void uninstallAppNoThrow(TestApp testApp) {
        try {
            uninstallApp(testApp);
        } catch (Exception e) {
            Log.e(TAG, "Exception occurred while uninstalling app: " + testApp, e);
        }
    }

    public static ContentResolver getContentResolver() {
        return getContext().getContentResolver();
    }

    /**
     * Queries {@link ContentResolver} for a file and returns the corresponding {@link Uri} for its
     * entry in the database. Returns {@code null} if file doesn't exist in the database.
     */
    @Nullable
    public static Uri getFileUri(@NonNull File file) {
        final Uri contentUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        final int id = getFileRowIdFromDatabase(file);
        return id == -1 ? null : ContentUris.withAppendedId(contentUri, id);
    }

    /**
     * Queries {@link ContentResolver} for a file and returns the corresponding row ID for its
     * entry in the database.
     */
    public static int getFileRowIdFromDatabase(@NonNull File file) {
        int id  = -1;
        try (Cursor c = queryFile(file, MediaStore.MediaColumns._ID)) {
            if (c.moveToFirst()) {
                id = c.getInt(0);
            }
        }
        return id;
    }

    /**
     * Queries {@link ContentResolver} for a file and returns the corresponding owner package name
     * for its entry in the database.
     */
    @Nullable
    public static String getFileOwnerPackageFromDatabase(@NonNull File file) {
        String ownerPackage = null;
        try (Cursor c = queryFile(file, MediaStore.MediaColumns.OWNER_PACKAGE_NAME)) {
            if (c.moveToFirst()) {
                ownerPackage = c.getString(0);
            }
        }
        return ownerPackage;
    }

    @NonNull
    public static Cursor queryVideoFile(File file, String... projection) {
        return queryFile(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, file, projection);
    }

    @NonNull
    public static Cursor queryImageFile(File file, String... projection) {
        return queryFile(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, file, projection);
    }

    /**
     * Queries {@link ContentResolver} for a file and returns the corresponding mime type for its
     * entry in the database.
     */
    @NonNull
    public static String getFileMimeTypeFromDatabase(@NonNull File file) {
        String mimeType = "";
        try (Cursor c = queryFile(file, MediaStore.MediaColumns.MIME_TYPE)) {
            if(c.moveToFirst()) {
                mimeType = c.getString(0);
            }
        }
        return mimeType;
    }

    /**
     * Sets {@link AppOpsManager#MODE_ALLOWED} for the given {@code ops} and the given {@code uid}.
     *
     * <p>This method drops shell permission identity.
     */
    public static void allowAppOpsToUid(int uid, @NonNull String... ops) {
        setAppOpsModeForUid(uid, AppOpsManager.MODE_ALLOWED, ops);
    }

    /**
     * Sets {@link AppOpsManager#MODE_ERRORED} for the given {@code ops} and the given {@code uid}.
     *
     * <p>This method drops shell permission identity.
     */
    public static void denyAppOpsToUid(int uid, @NonNull String... ops) {
        setAppOpsModeForUid(uid, AppOpsManager.MODE_ERRORED, ops);
    }

    /**
     * Deletes the given file through {@link ContentResolver} and {@link MediaStore} APIs,
     * and asserts that the file was successfully deleted from the database.
     */
    public static void deleteWithMediaProvider(@NonNull File file) {
        assertThat(getContentResolver().delete(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                /*where*/MediaStore.MediaColumns.DATA + " = ?",
                /*selectionArgs*/new String[] { file.getPath() }))
                .isEqualTo(1);
    }

    /**
     * Deletes db rows and files corresponding to uri through {@link ContentResolver} and
     * {@link MediaStore} APIs.
     */
    public static void deleteWithMediaProviderNoThrow(Uri... uris) {
        for (Uri uri : uris) {
            if (uri == null) continue;

            try {
                getContentResolver().delete(uri, Bundle.EMPTY);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Renames the given file through {@link ContentResolver} and {@link MediaStore} APIs,
     * and asserts that the file was updated in the database.
     */
    public static void updateDisplayNameWithMediaProvider(String relativePath,
            String oldDisplayName, String newDisplayName) {
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " = ? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
        String[] selectionArgs = { relativePath + '/', oldDisplayName };
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA};

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName);

        try (final Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs,
                null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
            cursor.moveToFirst();
            int id = cursor.getInt(cursor.getColumnIndex(MediaStore.MediaColumns._ID));
            String data = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DATA));
            Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            Log.i(TAG, "Uri: " + uri + ". Data: " + data);
            assertThat(getContentResolver().update(uri, values, selection, selectionArgs))
                    .isEqualTo(1);
        }
    }

    /**
     * Opens the given file through {@link ContentResolver} and {@link MediaStore} APIs.
     */
    @NonNull
    public static ParcelFileDescriptor openWithMediaProvider(@NonNull File file, String mode)
            throws Exception {
        final Uri fileUri = getFileUri(file);
        assertThat(fileUri).isNotNull();
        Log.i(TAG, "Uri: " + fileUri + ". Data: " + file.getPath());
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(fileUri, mode);
        assertThat(pfd).isNotNull();
        return pfd;
    }

    public static <T extends Exception> void assertThrows(Class<T> clazz, Operation<Exception> r)
            throws Exception {
        assertThrows(clazz, "", r);
    }

    public static <T extends Exception> void assertThrows(Class<T> clazz, String errMsg,
            Operation<Exception> r) throws Exception {
        try {
            r.run();
            fail("Expected " + clazz + " to be thrown");
        } catch (Exception e) {
            if (!clazz.isAssignableFrom(e.getClass()) || !e.getMessage().contains(errMsg)) {
                Log.e(TAG, "Expected " + clazz + " exception with error message: " + errMsg, e);
                throw e;
            }
        }
    }

    /**
     * A functional interface representing an operation that takes no arguments,
     * returns no arguments and might throw an {@link Exception} of any kind.
     */
    @FunctionalInterface
    public interface Operation<T extends Exception> {
        /**
         * This is the method that gets called for any object that implements this interface.
         */
        void run() throws T;
    }

    /**
     * Deletes the given file. If the file is a directory, then deletes all of it's children (files
     * or directories) recursively.
     */
    public static boolean deleteRecursively(@NonNull File path) {
        if (path.isDirectory()) {
            for (File child : path.listFiles()) {
                if (!deleteRecursively(child)) {
                    return false;
                }
            }
        }
        return path.delete();
    }

    public static void assertCanRenameFile(File oldFile, File newFile) {
        assertThat(oldFile.renameTo(newFile)).isTrue();
        assertThat(oldFile.exists()).isFalse();
        assertThat(newFile.exists()).isTrue();
        assertThat(getFileRowIdFromDatabase(oldFile)).isEqualTo(-1);
        assertThat(getFileRowIdFromDatabase(newFile)).isNotEqualTo(-1);
    }

    public static void assertCantRenameFile(File oldFile, File newFile) {
        final int rowId = getFileRowIdFromDatabase(oldFile);
        assertThat(oldFile.renameTo(newFile)).isFalse();
        assertThat(oldFile.exists()).isTrue();
        assertThat(getFileRowIdFromDatabase(oldFile)).isEqualTo(rowId);
    }

    public static void assertCanRenameDirectory(File oldDirectory, File newDirectory,
            @Nullable File[] oldFilesList, @Nullable File[] newFilesList) {
        assertThat(oldDirectory.renameTo(newDirectory)).isTrue();
        assertThat(oldDirectory.exists()).isFalse();
        assertThat(newDirectory.exists()).isTrue();
        for (File file  : oldFilesList != null ? oldFilesList : new File[0]) {
            assertThat(file.exists()).isFalse();
            assertThat(getFileRowIdFromDatabase(file)).isEqualTo(-1);
        }
        for (File file : newFilesList != null ? newFilesList : new File[0]) {
            assertThat(file.exists()).isTrue();
            assertThat(getFileRowIdFromDatabase(file)).isNotEqualTo(-1);
        };
    }

    public static void assertCantRenameDirectory(File oldDirectory, File newDirectory,
            @Nullable File[] oldFilesList) {
        assertThat(oldDirectory.renameTo(newDirectory)).isFalse();
        assertThat(oldDirectory.exists()).isTrue();
        for (File file  : oldFilesList != null ? oldFilesList : new File[0]) {
            assertThat(file.exists()).isTrue();
            assertThat(getFileRowIdFromDatabase(file)).isNotEqualTo(-1);
        }
    }

    public static boolean canOpen(File file, boolean forWrite) {
        if (forWrite) {
            try (FileOutputStream fis = new FileOutputStream(file)) {
                return true;
            } catch (IOException expected) {
                return false;
            }
        } else {
            try (FileInputStream fis = new FileInputStream(file)) {
                return true;
            } catch (IOException expected) {
                return false;
            }
        }
    }

    public static void pollForExternalStorageState() throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if(Environment.getExternalStorageState(Environment.getExternalStorageDirectory())
                    .equals(Environment.MEDIA_MOUNTED)) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        fail("Timed out while waiting for ExternalStorageState to be MEDIA_MOUNTED");
    }

    public static void pollForPermission(String perm, boolean granted) throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if (granted == checkPermissionAndAppOp(perm)) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        fail("Timed out while waiting for permission " + perm + " to be "
                + (granted ? "granted" : "revoked"));
    }

    /**
     * Asserts the entire content of the file equals exactly {@code expectedContent}.
     */
    public static void assertFileContent(File file, byte[] expectedContent) throws IOException {
        try (final FileInputStream fis = new FileInputStream(file)) {
            assertInputStreamContent(fis, expectedContent);
        }
    }

    /**
     * Asserts the entire content of the file equals exactly {@code expectedContent}.
     * <p>Sets {@code fd} to beginning of file first.
     */
    public static void assertFileContent(FileDescriptor fd, byte[] expectedContent)
            throws IOException, ErrnoException {
        Os.lseek(fd, 0, OsConstants.SEEK_SET);
        try (final FileInputStream fis = new FileInputStream(fd)) {
            assertInputStreamContent(fis, expectedContent);
        }
    }

    private static void assertInputStreamContent(InputStream in, byte[] expectedContent)
            throws IOException {
        assertThat(ByteStreams.toByteArray(in)).isEqualTo(expectedContent);
    }

    /**
     * Checks if the given {@code permission} is granted and corresponding AppOp is MODE_ALLOWED.
     */
    private static boolean checkPermissionAndAppOp(String permission) {
        final int pid  = Os.getpid();
        final int uid = Os.getuid();
        final Context context = getContext();
        final String packageName = context.getPackageName();
        if (context.checkPermission(permission, pid, uid) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        final String op = AppOpsManager.permissionToOp(permission);
        // No AppOp associated with the given permission, skip AppOp check.
        if (op == null) return true;

        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        try {
            appOps.checkPackage(uid, packageName);
        } catch (SecurityException e) {
            return false;
        }

        return appOps.unsafeCheckOpNoThrow(op, uid, packageName) == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * <p>This method drops shell permission identity.
     */
    private static void forceStopApp(String packageName) throws Exception {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity(Manifest.permission.FORCE_STOP_PACKAGES);

            getContext().getSystemService(ActivityManager.class).forceStopPackage(packageName);
            Thread.sleep(1000);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * <p>This method drops shell permission identity.
     */
    private static void sendIntentToTestApp(TestApp testApp, String dirPath, String actionName,
            BroadcastReceiver broadcastReceiver, CountDownLatch latch) throws Exception {

        final String packageName = testApp.getPackageName();
        forceStopApp(packageName);
        // Register broadcast receiver
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(actionName);
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        getContext().registerReceiver(broadcastReceiver, intentFilter);

        // Launch the test app.
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setPackage(packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(QUERY_TYPE, actionName);
        intent.putExtra(INTENT_EXTRA_PATH, dirPath);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        getContext().startActivity(intent);
        latch.await();
        getContext().unregisterReceiver(broadcastReceiver);
    }

    /**
     * Gets images/video metadata from a test app.
     *
     * <p>This method drops shell permission identity.
     */
    private static HashMap<String, String> getMetadataFromTestApp(TestApp testApp, String dirPath,
            String actionName) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final HashMap<String, String> appOutputList = new HashMap<>();
        final Exception[] exception = new Exception[1];
        exception[0] = null;
        final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.hasExtra(INTENT_EXCEPTION)) {
                    exception[0] = (Exception)(intent.getExtras().get(INTENT_EXCEPTION));
                } else if(intent.hasExtra(actionName)) {
                    HashMap<String, String> res =
                            (HashMap<String, String>) intent.getExtras().get(actionName);
                    appOutputList.putAll(res);
                }
                latch.countDown();
            }
        };
        sendIntentToTestApp(testApp, dirPath, actionName, broadcastReceiver, latch);
        if (exception[0] != null) throw exception[0];
        return appOutputList;
    }

    /**
     * <p>This method drops shell permission identity.
     */
    private static ArrayList<String> getContentsFromTestApp(TestApp testApp, String dirPath,
            String actionName) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final ArrayList<String> appOutputList = new ArrayList<String>();
        final Exception[] exception = new Exception[1];
        exception[0] = null;
        final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.hasExtra(INTENT_EXCEPTION)) {
                    exception[0] = (Exception)(intent.getSerializableExtra(INTENT_EXCEPTION));
                } else if(intent.hasExtra(actionName)) {
                    appOutputList.addAll(intent.getStringArrayListExtra(actionName));
                }
                latch.countDown();
            }
        };

        sendIntentToTestApp(testApp, dirPath, actionName, broadcastReceiver, latch);
        if (exception[0] != null) throw exception[0];
        return appOutputList;
    }

    /**
     * <p>This method drops shell permission identity.
     */
    private static boolean getResultFromTestApp(TestApp testApp, String dirPath,
            String actionName) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] appOutput = new boolean[1];
        final Exception[] exception = new Exception[1];
        exception[0] = null;
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.hasExtra(INTENT_EXCEPTION)) {
                    exception[0] = (Exception)(intent.getSerializableExtra(INTENT_EXCEPTION));
                } else if(intent.hasExtra(actionName)) {
                    appOutput[0] = intent.getBooleanExtra(actionName, false);
                }
                latch.countDown();
            }
        };

        sendIntentToTestApp(testApp, dirPath, actionName, broadcastReceiver, latch);
        if (exception[0] != null) throw exception[0];
        return appOutput[0];
    }

    /**
     * Sets {@code mode} for the given {@code ops} and the given {@code uid}.
     *
     * <p>This method drops shell permission identity.
     */
    private static void setAppOpsModeForUid(int uid, int mode, @NonNull String... ops) {
        adoptShellPermissionIdentity(null);
        try {
            for (String op : ops) {
                getContext().getSystemService(AppOpsManager.class)
                        .setUidMode(op, uid, mode);
            }
        } finally {
            dropShellPermissionIdentity();
        }
    }

    @NonNull
    private static Cursor queryFile(@NonNull File file,
            String... projection) {
        return queryFile(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                file, projection);
    }

    @NonNull
    private static Cursor queryFile(@NonNull Uri uri, @NonNull File file,
            String... projection) {
        Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.DATA + " = ?");
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[] { file.getAbsolutePath() });
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);

        final Cursor c = getContentResolver().query(uri, projection, queryArgs, null);
        assertThat(c).isNotNull();
        return c;
    }

    /**
     * Asserts that {@code dir} is a directory and that it doesn't contain any of
     * {@code unexpectedContent}
     */
    public static void assertDirectoryDoesNotContain(@NonNull File dir, File... unexpectedContent) {
        assertThat(dir.isDirectory()).isTrue();
        assertThat(Arrays.asList(dir.listFiles())).containsNoneIn(unexpectedContent);
    }

    /**
     * Asserts that {@code dir} is a directory and that it contains all of {@code expectedContent}
     */
    public static void assertDirectoryContains(@NonNull File dir, File... expectedContent) {
        assertThat(dir.isDirectory()).isTrue();
        assertThat(Arrays.asList(dir.listFiles())).containsAllIn(expectedContent);
    }
}
