package biz.bokhorst.xprivacy;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

@SuppressWarnings("deprecation")
@SuppressLint({ "DefaultLocale", "WorldReadableFiles" })
public class PrivacyProvider extends ContentProvider {
	public static final String AUTHORITY = "biz.bokhorst.xprivacy.provider";
	public static final String PREF_RESTRICTION = AUTHORITY;
	public static final String PREF_USAGE = AUTHORITY + ".usage";
	public static final String PREF_SETTINGS = AUTHORITY + ".settings";
	public static final String PATH_RESTRICTION = "restriction";
	public static final String PATH_USAGE = "usage";
	public static final String PATH_SETTINGS = "settings";
	public static final Uri URI_RESTRICTION = Uri.parse("content://" + AUTHORITY + "/" + PATH_RESTRICTION);
	public static final Uri URI_USAGE = Uri.parse("content://" + AUTHORITY + "/" + PATH_USAGE);
	public static final Uri URI_SETTING = Uri.parse("content://" + AUTHORITY + "/" + PATH_SETTINGS);

	public static final String COL_UID = "Uid";
	public static final String COL_RESTRICTION = "Restriction";
	public static final String COL_RESTRICTED = "Restricted";
	public static final String COL_METHOD = "Method";
	public static final String COL_USED = "Used";
	public static final String COL_SETTING = "Setting";
	public static final String COL_VALUE = "Value";

	private static final UriMatcher sUriMatcher;
	private static final int TYPE_RESTRICTION = 1;
	private static final int TYPE_USAGE = 2;
	private static final int TYPE_SETTING = 3;

	private static Object mFallbackLock = new Object();
	private static int mFallbackRestrictionsUid = 0;
	private static long mFallbackRestrictionsTime = 0;
	private static long mFallbackSettingsTime = 0;
	private static SharedPreferencesEx mFallbackRestrictions = null;
	private static SharedPreferencesEx mFallbackSettings = null;

	private static ExecutorService mExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

	static {
		sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
		sUriMatcher.addURI(AUTHORITY, PATH_RESTRICTION, TYPE_RESTRICTION);
		sUriMatcher.addURI(AUTHORITY, PATH_USAGE, TYPE_USAGE);
		sUriMatcher.addURI(AUTHORITY, PATH_SETTINGS, TYPE_SETTING);
	}

	@Override
	public boolean onCreate() {
		try {
			writeMetaData();
			convertRestrictions();
			convertSettings();
			fixFilePermissions();
		} catch (Throwable ex) {
			Util.bug(null, ex);
		}
		return true;
	}

	@Override
	public String getType(Uri uri) {
		if (sUriMatcher.match(uri) == TYPE_RESTRICTION)
			return String.format("vnd.android.cursor.dir/%s.%s", AUTHORITY, PATH_RESTRICTION);
		else if (sUriMatcher.match(uri) == TYPE_USAGE)
			return String.format("vnd.android.cursor.dir/%s.%s", AUTHORITY, PATH_USAGE);
		else if (sUriMatcher.match(uri) == TYPE_SETTING)
			return String.format("vnd.android.cursor.dir/%s.%s", AUTHORITY, PATH_SETTINGS);
		throw new IllegalArgumentException();
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		if (sUriMatcher.match(uri) == TYPE_RESTRICTION && selectionArgs != null && selectionArgs.length >= 2) {
			// Get arguments
			final String restrictionName = selection;
			final int uid = Integer.parseInt(selectionArgs[0]);
			boolean usage = Boolean.parseBoolean(selectionArgs[1]);
			final String methodName = (selectionArgs.length >= 3 ? selectionArgs[2] : null);

			return queryRestrictions(uid, restrictionName, methodName, usage);
		} else if (sUriMatcher.match(uri) == TYPE_USAGE && selectionArgs != null && selectionArgs.length >= 1) {
			// Return usage
			List<String> listRestriction;
			if (selection == null)
				listRestriction = PrivacyManager.getRestrictions(true);
			else {
				listRestriction = new ArrayList<String>();
				listRestriction.add(selection);
			}
			int uid = Integer.parseInt(selectionArgs[0]);
			String methodName = (selectionArgs.length >= 2 ? selectionArgs[1] : null);

			return queryUsage(uid, listRestriction, methodName);
		} else if (sUriMatcher.match(uri) == TYPE_SETTING && selectionArgs == null)
			return querySettings(selection);

		throw new IllegalArgumentException(uri.toString());
	}

	private Cursor queryRestrictions(final int uid, final String restrictionName, final String methodName, boolean usage) {
		MatrixCursor cursor = new MatrixCursor(new String[] { COL_UID, COL_RESTRICTION, COL_METHOD, COL_RESTRICTED });

		// Build restriction list
		List<String> listRestrictionName;
		if (restrictionName == null)
			listRestrictionName = PrivacyManager.getRestrictions(true);
		else {
			listRestrictionName = new ArrayList<String>();
			listRestrictionName.add(restrictionName);
		}

		if (uid == 0) {
			// Process applications
			PackageManager pm = getContext().getPackageManager();
			for (ApplicationInfo appInfo : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
				SharedPreferences prefs = getContext().getSharedPreferences(PREF_RESTRICTION + "." + appInfo.uid,
						Context.MODE_WORLD_READABLE);

				// Process restrictions
				for (String eRestrictionName : listRestrictionName)
					if (getRestricted(eRestrictionName, null, prefs)) {
						// Category
						cursor.addRow(new Object[] { appInfo.uid, eRestrictionName, null, true });

						// Exceptions
						for (PrivacyManager.MethodDescription md : PrivacyManager.getMethods(eRestrictionName)) {
							boolean restricted = getRestricted(eRestrictionName, md.getMethodName(), prefs);
							if (!restricted || PrivacyManager.isDangerousMethod(eRestrictionName, md.getMethodName()))
								cursor.addRow(new Object[] { appInfo.uid, eRestrictionName, md.getMethodName(),
										restricted });
						}
					}
			}
		} else {
			SharedPreferences prefs = getContext().getSharedPreferences(PREF_RESTRICTION + "." + uid,
					Context.MODE_WORLD_READABLE);

			// Process restrictions
			boolean restricted = false;
			for (String eRestrictionName : listRestrictionName) {
				boolean eRestricted = getRestricted(eRestrictionName, methodName, prefs);
				cursor.addRow(new Object[] { uid, eRestrictionName, methodName, Boolean.toString(eRestricted) });
				restricted = restricted || eRestricted;
			}

			// Update usage data
			if (usage && restrictionName != null && methodName != null) {
				final boolean isRestricted = restricted;
				mExecutor.execute(new Runnable() {
					public void run() {
						long timeStamp = new Date().getTime();
						updateUsage(uid, restrictionName, methodName, isRestricted, timeStamp);
					}
				});
			}
		}

		return cursor;
	}

	private static boolean getRestricted(String restrictionName, String methodName, SharedPreferences prefs) {
		// Check for restriction
		boolean restricted = prefs.getBoolean(getRestrictionPref(restrictionName), false);

		// Check for exception
		if (restricted && methodName != null)
			if (prefs.getBoolean(getExceptionPref(restrictionName, methodName), false))
				restricted = false;

		return restricted;
	}

	private Cursor queryUsage(int uid, List<String> listRestriction, String methodName) {
		MatrixCursor cursor = new MatrixCursor(new String[] { COL_UID, COL_RESTRICTION, COL_METHOD, COL_RESTRICTED,
				COL_USED });
		if (uid == 0) {
			// All
			for (String restrictionName : PrivacyManager.getRestrictions(true)) {
				SharedPreferences prefs = getContext().getSharedPreferences(PREF_USAGE + "." + restrictionName,
						Context.MODE_PRIVATE);
				for (String prefName : prefs.getAll().keySet())
					if (prefName.startsWith(COL_USED)) {
						String[] prefParts = prefName.split("\\.");
						int rUid = Integer.parseInt(prefParts[1]);
						String rMethodName = prefParts[2];
						getUsage(rUid, restrictionName, rMethodName, cursor);
					}
			}
		} else {
			// Selected restrictions/methods
			for (String restrictionName : listRestriction)
				if (methodName == null)
					for (PrivacyManager.MethodDescription md : PrivacyManager.getMethods(restrictionName))
						getUsage(uid, restrictionName, md.getMethodName(), cursor);
				else
					getUsage(uid, restrictionName, methodName, cursor);
		}
		return cursor;
	}

	private void getUsage(int uid, String restrictionName, String methodName, MatrixCursor cursor) {
		SharedPreferences prefs = getContext().getSharedPreferences(PREF_USAGE + "." + restrictionName,
				Context.MODE_PRIVATE);
		String values = prefs.getString(getUsagePref(uid, methodName), null);
		if (values != null) {
			String[] value = values.split(":");
			long timeStamp = Long.parseLong(value[0]);
			boolean restricted = Boolean.parseBoolean(value[1]);
			cursor.addRow(new Object[] { uid, restrictionName, methodName, restricted, timeStamp });
		}
	}

	private Cursor querySettings(String name) {
		SharedPreferences prefs = getContext().getSharedPreferences(PREF_SETTINGS, Context.MODE_WORLD_READABLE);
		MatrixCursor cursor = new MatrixCursor(new String[] { COL_SETTING, COL_VALUE });
		if (name == null)
			for (String settingKey : prefs.getAll().keySet())
				try {
					cursor.addRow(new Object[] { getSettingName(settingKey), prefs.getString(settingKey, null) });
				} catch (Throwable ex) {
					// Legacy boolean
				}
		else
			cursor.addRow(new Object[] { name, prefs.getString(getSettingPref(name), null) });
		return cursor;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		// Check access
		enforcePermission();

		throw new IllegalArgumentException(uri.toString());
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		if (sUriMatcher.match(uri) == TYPE_RESTRICTION) {
			// Check access
			enforcePermission();

			// Get arguments
			String restrictionName = selection;
			int uid = values.getAsInteger(COL_UID);
			String methodName = values.getAsString(COL_METHOD);
			boolean restricted = Boolean.parseBoolean(values.getAsString(COL_RESTRICTED));
			updateRestriction(uid, restrictionName, methodName, !restricted);

			return 1; // rows
		} else if (sUriMatcher.match(uri) == TYPE_USAGE) {
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

			// Get arguments
			int uid = values.getAsInteger(COL_UID);
			String restrictionName = values.getAsString(PrivacyProvider.COL_RESTRICTION);
			String methodName = values.getAsString(COL_METHOD);
			boolean restricted = false;
			if (values.containsKey(PrivacyProvider.COL_RESTRICTED))
				restricted = values.getAsBoolean(PrivacyProvider.COL_RESTRICTED);
			long timeStamp = values.getAsLong(PrivacyProvider.COL_USED);
			Util.log(null, Log.INFO,
					String.format("Update usage data %d/%s/%s=%b", uid, restrictionName, methodName, restricted));

			// Update usage data
			if (methodName != null)
				updateUsage(uid, restrictionName, methodName, restricted, timeStamp);

			return 1;
		} else if (sUriMatcher.match(uri) == TYPE_SETTING) {
			// Check access
			enforcePermission();

			// Get arguments
			String settingName = selection;
			String value = values.getAsString(COL_VALUE);

			// Update setting
			updateSetting(settingName, value);

			return 1;
		}

		throw new IllegalArgumentException(uri.toString());
	}

	private void updateRestriction(int uid, String restrictionName, String methodName, boolean allowed) {
		// Update restriction
		SharedPreferences prefs = getContext().getSharedPreferences(PREF_RESTRICTION + "." + uid,
				Context.MODE_WORLD_READABLE);
		SharedPreferences.Editor editor = prefs.edit();
		if (methodName == null || !allowed)
			editor.putBoolean(getRestrictionPref(restrictionName), !allowed);
		if (methodName != null)
			editor.putBoolean(getExceptionPref(restrictionName, methodName), allowed);
		editor.apply();
		setPrefFileReadable(PREF_RESTRICTION, uid);
	}

	private void updateUsage(final int uid, final String restrictionName, final String methodName,
			final boolean restricted, long timeStamp) {
		SharedPreferences prefs = getContext().getSharedPreferences(PREF_USAGE + "." + restrictionName,
				Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();
		String prefName = getUsagePref(uid, methodName);
		String prefValue = String.format("%d:%b", timeStamp, restricted);
		editor.remove(prefName);
		editor.putString(prefName, prefValue);
		editor.apply();
	}

	private void updateSetting(String name, String value) {
		SharedPreferences prefs = getContext().getSharedPreferences(PREF_SETTINGS, Context.MODE_WORLD_READABLE);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(getSettingPref(name), value);
		editor.apply();
		setPrefFileReadable(PREF_SETTINGS);
	}

	@Override
	public int delete(Uri uri, String where, String[] selectionArgs) {
		// Check access
		enforcePermission();

		if (sUriMatcher.match(uri) == TYPE_RESTRICTION) {
			int uid = Integer.parseInt(selectionArgs[0]);
			return deleteRestrictions(uid);
		} else if (sUriMatcher.match(uri) == TYPE_USAGE) {
			int uid = Integer.parseInt(selectionArgs[0]);
			return deleteUsage(uid);
		} else if (sUriMatcher.match(uri) == TYPE_SETTING && selectionArgs == null) {
			return deleteSettings();
		}

		throw new IllegalArgumentException(uri.toString());
	}

	private int deleteRestrictions(int uid) {
		int rows = 0;
		SharedPreferences prefs = getContext().getSharedPreferences(PREF_RESTRICTION + "." + uid,
				Context.MODE_WORLD_READABLE);
		SharedPreferences.Editor editor = prefs.edit();
		for (String pref : prefs.getAll().keySet()) {
			Util.log(null, Log.INFO, "Removed restriction=" + pref + " uid=" + uid);
			editor.remove(pref);
			rows++;
		}
		editor.apply();
		setPrefFileReadable(PREF_RESTRICTION, uid);

		return rows;
	}

	private int deleteUsage(int uid) {
		int rows = 0;
		String sUid = Integer.toString(uid);
		for (String restrictionName : PrivacyManager.getRestrictions(true)) {
			SharedPreferences prefs = getContext().getSharedPreferences(PREF_USAGE + "." + restrictionName,
					Context.MODE_PRIVATE);
			SharedPreferences.Editor editor = prefs.edit();
			for (String pref : prefs.getAll().keySet()) {
				String[] component = pref.split("\\.");
				if (component.length >= 2 && component[1].equals(sUid)) {
					Util.log(null, Log.INFO, "Removed usage=" + pref + " uid=" + uid);
					editor.remove(pref);
					rows++;
				}
			}
			editor.apply();
		}

		return rows;
	}

	private int deleteSettings() {
		int rows = 0;
		SharedPreferences prefs = getContext().getSharedPreferences(PREF_SETTINGS, Context.MODE_WORLD_READABLE);
		SharedPreferences.Editor editor = prefs.edit();
		for (String pref : prefs.getAll().keySet()) {
			rows++;
			editor.remove(pref);
			Util.log(null, Log.INFO, "Removed setting=" + pref);
		}
		editor.apply();
		setPrefFileReadable(PREF_SETTINGS);
		return rows;
	}

	// The following methods are used as fallback, when:
	// - there is no context (Java threads)
	// - the content provider cannot be queried (PackageManagerService)

	public static boolean getRestrictedFallback(XHook hook, int uid, String restrictionName, String methodName) {
		try {
			long now = new Date().getTime();
			String name = getPrefFileName(PREF_RESTRICTION, uid);
			File file = new File(name);
			File backup = new File(name + ".bak");
			if (file.canRead() && !backup.exists())
				synchronized (mFallbackLock) {
					if (mFallbackRestrictions == null || mFallbackRestrictionsUid != uid) {
						// Initial load
						mFallbackRestrictions = new SharedPreferencesEx(file);
						mFallbackRestrictionsUid = uid;
						mFallbackRestrictionsTime = now;
						long ms = System.currentTimeMillis() - now;
						Util.log(null, Log.INFO, "Load fallback restrictions uid=" + uid + "/"
								+ mFallbackRestrictionsUid + " " + ms + " ms");
					} else if (mFallbackRestrictionsTime + PrivacyManager.cRestrictionCacheTimeoutMs < now) {
						// Check update
						mFallbackRestrictions.reload();
						mFallbackRestrictionsUid = uid;
						mFallbackRestrictionsTime = now;
						long ms = System.currentTimeMillis() - now;
						Util.log(null, Log.INFO, "Reload fallback restrictions uid=" + uid + " " + ms + " ms");
					}
				}

			if (mFallbackRestrictions == null)
				return false;
			else
				return getRestricted(restrictionName, methodName, mFallbackRestrictions);
		} catch (Throwable ex) {
			Util.bug(hook, ex);
			return false;
		}
	}

	public static String getSettingFallback(String settingName, String defaultValue) {
		try {
			long now = new Date().getTime();
			String name = getPrefFileName(PREF_SETTINGS);
			File file = new File(name);
			File backup = new File(name + ".bak");
			if (file.canRead() && !backup.exists()) {
				// Initial load
				if (mFallbackSettings == null) {
					mFallbackSettings = new SharedPreferencesEx(file);
					mFallbackSettingsTime = now;
					long ms = System.currentTimeMillis() - now;
					Util.log(null, Log.INFO, "Load fallback settings uid=" + Binder.getCallingUid() + " " + ms + " ms");
				}

				// Get update
				synchronized (mFallbackSettings) {
					if (mFallbackSettingsTime + PrivacyManager.cSettingCacheTimeoutMs < now) {
						mFallbackSettings.reload();
						mFallbackSettingsTime = now;
						long ms = System.currentTimeMillis() - now;
						Util.log(null, Log.INFO, "Reload fallback settings uid=" + Binder.getCallingUid() + " " + ms
								+ " ms");
					}
				}
			}

			if (mFallbackSettings == null)
				return defaultValue;
			else
				return mFallbackSettings.getString(getSettingPref(settingName), defaultValue);
		} catch (Throwable ex) {
			Util.bug(null, ex);
			return defaultValue;
		}
	}

	// Helper methods

	private void enforcePermission() throws SecurityException {
		if (Binder.getCallingUid() != Process.myUid())
			throw new SecurityException();
	}

	private static String getPrefFileName(String preference) {
		String packageName = PrivacyManager.class.getPackage().getName();
		return Environment.getDataDirectory() + File.separator + "data" + File.separator + packageName + File.separator
				+ "shared_prefs" + File.separator + preference + ".xml";
	}

	private static String getPrefFileName(String preference, int uid) {
		String packageName = PrivacyManager.class.getPackage().getName();
		return Environment.getDataDirectory() + File.separator + "data" + File.separator + packageName + File.separator
				+ "shared_prefs" + File.separator + preference + "." + uid + ".xml";
	}

	private static void setPrefFileReadable(String preference) {
		new File(getPrefFileName(preference)).setReadable(true, false);
	}

	private static void setPrefFileReadable(String preference, int uid) {
		new File(getPrefFileName(preference, uid)).setReadable(true, false);
	}

	public static void fixFilePermissions() {
		String packageName = PrivacyManager.class.getPackage().getName();
		File list[] = new File(Environment.getDataDirectory() + File.separator + "data" + File.separator + packageName
				+ File.separator + "shared_prefs").listFiles();
		if (list != null)
			for (File file : list)
				if (file.getName().startsWith("biz.bokhorst.xprivacy.provider.") && file.getName().endsWith(".xml")
						&& !file.getName().contains(".usage."))
					file.setReadable(true, false);
	}

	private static String getRestrictionPref(String restrictionName) {
		return String.format("%s.%s", COL_RESTRICTED, restrictionName);
	}

	private static String getExceptionPref(String restrictionName, String methodName) {
		return String.format("%s.%s.%s", COL_METHOD, restrictionName, methodName);
	}

	private static String getUsagePref(int uid, String methodName) {
		return String.format("%s.%d.%s", COL_USED, uid, methodName);
	}

	private static String getSettingPref(String settingName) {
		return String.format("%s.%s", COL_SETTING, settingName);
	}

	private static String getSettingName(String settingKey) {
		return settingKey.substring(COL_SETTING.length() + 1);
	}

	private void writeMetaData() throws IOException, FileNotFoundException {
		String packageName = PrivacyManager.class.getPackage().getName();
		File out = new File(Environment.getDataDirectory() + File.separator + "data" + File.separator + packageName
				+ File.separator + "meta.xml");
		Util.log(null, Log.INFO, "Writing meta=" + out.getAbsolutePath());
		InputStream is = getContext().getAssets().open("meta.xml");
		OutputStream os = new FileOutputStream(out.getAbsolutePath());
		byte[] buffer = new byte[1024];
		int read;
		while ((read = is.read(buffer)) != -1)
			os.write(buffer, 0, read);
		is.close();
		os.flush();
		os.close();
		out.setReadable(true, false);
	}

	private void convertRestrictions() throws IOException {
		String packageName = PrivacyManager.class.getPackage().getName();
		File source = new File(Environment.getDataDirectory() + File.separator + "data" + File.separator + packageName
				+ File.separator + "shared_prefs" + File.separator + "biz.bokhorst.xprivacy.provider.xml");
		File backup = new File(source.getAbsoluteFile() + ".orig");
		if (source.exists() && !backup.exists()) {
			Util.log(null, Log.INFO, "Converting restrictions");
			SharedPreferences prefs = getContext().getSharedPreferences(PREF_RESTRICTION, Context.MODE_WORLD_READABLE);
			for (String key : prefs.getAll().keySet()) {
				String[] component = key.split("\\.");
				if (key.startsWith(COL_RESTRICTED)) {
					String restrictionName = component[1];
					String value = prefs.getString(key, null);
					List<String> listRestriction = new ArrayList<String>(Arrays.asList(value.split(",")));
					listRestriction.remove(0);
					for (String uid : listRestriction)
						updateRestriction(Integer.parseInt(uid), restrictionName, null, false);
				} else if (key.startsWith(COL_METHOD)) {
					int uid = Integer.parseInt(component[1]);
					String restrictionName = component[2];
					String methodName = component[3];
					boolean value = prefs.getBoolean(key, false);
					updateRestriction(uid, restrictionName, methodName, value);
				} else
					Util.log(null, Log.WARN, "Unknown key=" + key);
			}

			// Backup old file
			Util.log(null, Log.INFO, "Backup name=" + backup.getAbsolutePath());
			Util.copy(source, backup);
		}
	}

	private void convertSettings() throws IOException {
		SharedPreferences prefs = getContext().getSharedPreferences(PREF_SETTINGS, Context.MODE_WORLD_READABLE);
		SharedPreferences.Editor editor = prefs.edit();
		for (String key : prefs.getAll().keySet())
			try {
				String value = prefs.getString(key, null);
				if (PrivacyManager.cValueRandomLegacy.equals(value))
					editor.putString(key, PrivacyManager.cValueRandom);
			} catch (Throwable ex) {

			}
		editor.apply();
		setPrefFileReadable(PREF_SETTINGS);
	}
}
