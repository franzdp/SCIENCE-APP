package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

fun ensureFirebaseAppInitialized(context: Context): FirebaseApp? {
  val appContext = context.applicationContext ?: context
  runCatching { FirebaseApp.getInstance() }.getOrNull()?.let { return it }

  val defaultApp = runCatching { FirebaseApp.initializeApp(appContext) }.getOrNull()
  if (defaultApp != null) {
    return defaultApp
  }

  // Fallback: look up google-services string resources directly by package name
  // in case StringResourceValueReader's R package lookup differs under nonTransitiveRClass.
  val appId = readStringResource(appContext, "google_app_id")
  val apiKey = readStringResource(appContext, "google_api_key")
  if (!appId.isNullOrBlank() && !apiKey.isNullOrBlank()) {
    val optionsBuilder = FirebaseOptions.Builder()
      .setApplicationId(appId)
      .setApiKey(apiKey)
    readStringResource(appContext, "project_id")?.takeIf { it.isNotBlank() }?.let {
      optionsBuilder.setProjectId(it)
    }
    readStringResource(appContext, "google_storage_bucket")?.takeIf { it.isNotBlank() }?.let {
      optionsBuilder.setStorageBucket(it)
    }
    readStringResource(appContext, "gcm_defaultSenderId")?.takeIf { it.isNotBlank() }?.let {
      optionsBuilder.setGcmSenderId(it)
    }
    return runCatching {
      FirebaseApp.initializeApp(appContext, optionsBuilder.build())
    }.onFailure {
      Log.e(TAG, "Fallback FirebaseApp.initializeApp(context, options) failed", it)
    }.getOrNull()
  }

  Log.e(TAG, "FirebaseApp could not initialize: google_app_id or google_api_key missing from resources.")
  return null
}

private fun readStringResource(context: Context, name: String): String? {
  val candidatePackages = listOf(context.packageName, "com.example").distinct()
  for (pkg in candidatePackages) {
    val resId = context.resources.getIdentifier(name, "string", pkg)
    if (resId != 0) {
      val value = runCatching { context.getString(resId) }.getOrNull()
      if (!value.isNullOrBlank()) return value
    }
  }
  return null
}

fun configureAppCheck(context: Context, intent: Intent?) {
  val appContext = context.applicationContext ?: context
  try {
    val firebaseApp = ensureFirebaseAppInitialized(appContext)
    if (firebaseApp == null) {
      Log.e(TAG, "Skipping App Check setup because FirebaseApp is not initialized.")
      return
    }

    if (BuildConfig.DEBUG) {
      val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

      if (!debugToken.isNullOrEmpty()) {
        val persistenceKey = firebaseApp.persistenceKey
        val prefs = appContext.getSharedPreferences(
          "$DEBUG_STORE_PREFIX$persistenceKey",
          Context.MODE_PRIVATE,
        )
        // Synchronous commit() ensures the secret is written before DebugAppCheckProviderFactory reads it.
        prefs.edit()
          .putString(DEBUG_SECRET_KEY, debugToken)
          .commit()
        Log.d(TAG, "App Check debug secret configured successfully.")
      } else {
        Log.w(TAG, "No debug token found in Intent extra.")
      }

      FirebaseAppCheck.getInstance(firebaseApp).installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance(),
      )
      Log.d(TAG, "DebugAppCheckProviderFactory installed.")
    } else {
      val resId = appContext.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", appContext.packageName)
      if (resId != 0) {
        val siteKey = appContext.getString(resId)
        if (siteKey.isNotBlank()) {
          FirebaseAppCheck.getInstance(firebaseApp).installAppCheckProviderFactory(
            RecaptchaAppCheckProviderFactory.getInstance(siteKey),
          )
          Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
        } else {
          Log.e(TAG, "recaptcha_site_key resource string is blank.")
        }
      } else {
        Log.e(TAG, "recaptcha_site_key string resource not found in package.")
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "Failed to initialize Firebase App Check", e)
  }
}
