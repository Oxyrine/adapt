package com.popgamma.tutor

import android.content.Context

/** Persists the Groq API key on-device via SharedPreferences -- the native platform's own local
 *  storage, no new dependency (DataStore etc. would be overkill for one string). Needed because a
 *  downloaded APK has no build-time local.properties value baked in (BuildConfig.GROQ_API_KEY is
 *  empty for anyone who didn't build it themselves); this is what lets someone who just installed
 *  the app enter their own key and have it stick across restarts. */
object ApiKeyStore {
    private const val PREFS_NAME = "adaptive_tutor_tone"
    private const val KEY_GROQ_API_KEY = "groq_api_key"

    /** Falls back to the build-time BuildConfig value (empty for a downloaded APK, real for a
     *  local dev build with local.properties set) so existing dev workflows keep working. */
    fun get(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GROQ_API_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GROQ_API_KEY

    fun save(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GROQ_API_KEY, key.trim())
            .apply()
    }
}
