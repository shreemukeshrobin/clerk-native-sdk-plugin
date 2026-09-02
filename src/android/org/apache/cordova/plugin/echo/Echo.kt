package org.apache.cordova.plugin.echo

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.clerk.api.Clerk
import com.clerk.api.ClerkConfigurationOptions
import com.clerk.api.SharedSessionSyncConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Echo Cordova Plugin implemented in Kotlin with Clerk Android SDK Integration.
 */
class Echo : CordovaPlugin() {

    companion object {
        private const val TAG = "EchoPlugin"
        private const val NETWORK_TIMEOUT_MS = 30000L
    }

    private val pluginScope = CoroutineScope(Dispatchers.IO)
    private var inMemoryPublishableKey: String = ""

    private fun getStoredPublishableKey(): String {
        if (inMemoryPublishableKey.isNotEmpty()) return inMemoryPublishableKey
        return try {
            val prefs = cordova.activity.applicationContext.getSharedPreferences("clerk_prefs", android.content.Context.MODE_PRIVATE)
            val saved = prefs.getString("clerk_publishable_key", "") ?: ""
            if (saved.isNotEmpty()) inMemoryPublishableKey = saved
            inMemoryPublishableKey
        } catch (t: Throwable) {
            ""
        }
    }

    private fun saveStoredPublishableKey(key: String) {
        if (key.isNotEmpty()) {
            inMemoryPublishableKey = key
            try {
                val prefs = cordova.activity.applicationContext.getSharedPreferences("clerk_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putString("clerk_publishable_key", key).apply()
            } catch (t: Throwable) {
                Log.w(TAG, "Could not persist publishable key in SharedPreferences", t)
            }
        }
    }

    override fun execute(
        action: String,
        args: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        Log.d(TAG, "Executing action: $action")
        return when (action) {
            "echo" -> {
                val message = args.optString(0, "")
                this.echo(message, callbackContext)
                true
            }
            "echoAsync" -> {
                val message = args.optString(0, "")
                this.echoAsync(message, callbackContext)
                true
            }
            "add" -> {
                val num1 = args.optDouble(0, Double.NaN)
                val num2 = args.optDouble(1, Double.NaN)
                this.add(num1, num2, callbackContext)
                true
            }
            "checkClerk" -> {
                val publishableKey = args.optString(0, "")
                this.checkClerk(publishableKey, callbackContext)
                true
            }
            "initializeClerk" -> {
                val publishableKey = args.optString(0, "")
                val enableSharedSessionSync = args.optBoolean(1, true)
                this.initializeClerk(publishableKey, enableSharedSessionSync, callbackContext)
                true
            }
            "signInWithPassword" -> {
                val identifier = args.optString(0, "")
                val password = args.optString(1, "")
                this.signInWithPassword(identifier, password, callbackContext)
                true
            }
            "signInWithMicrosoft" -> {
                val publishableKey = args.optString(0, "")
                this.signInWithMicrosoft(publishableKey, callbackContext)
                true
            }
            "signOut" -> {
                this.signOut(callbackContext)
                true
            }
            "getCurrentUser" -> {
                this.getCurrentUser(callbackContext)
                true
            }
            "reloadFromSharedStorage" -> {
                this.reloadFromSharedStorage(callbackContext)
                true
            }
            "testConnection" -> {
                val publishableKey = args.optString(0, "")
                this.testConnection(publishableKey, callbackContext)
                true
            }
            "getKeychainAccessGroup" -> {
                this.getKeychainAccessGroup(callbackContext)
                true
            }
            else -> false
        }
    }

    private fun echo(message: String?, callbackContext: CallbackContext) {
        val msg = message ?: ""
        if (msg.isNotEmpty()) {
            callbackContext.success(msg)
        } else {
            callbackContext.error("Expected one non-empty string argument.")
        }
    }

    private fun echoAsync(message: String?, callbackContext: CallbackContext) {
        cordova.threadPool.execute {
            val msg = message ?: ""
            if (msg.isNotEmpty()) {
                val response = JSONObject().apply {
                    put("status", "success")
                    put("message", msg)
                    put("timestamp", System.currentTimeMillis())
                    put("language", "Kotlin")
                }
                callbackContext.success(response)
            } else {
                callbackContext.error("Expected one non-empty string argument.")
            }
        }
    }

    private fun add(num1: Double, num2: Double, callbackContext: CallbackContext) {
        if (num1.isNaN() || num2.isNaN()) {
            callbackContext.error("Expected two valid numeric arguments.")
        } else {
            val sum = num1 + num2
            val response = JSONObject().apply {
                put("num1", num1)
                put("num2", num2)
                put("sum", sum)
            }
            callbackContext.success(response)
        }
    }

    private fun isClerkInitialized(): Boolean {
        return try {
            val method = Clerk::class.java.methods.firstOrNull { it.name == "isInitialized" }
            if (method != null) {
                val res = method.invoke(null)
                if (res is Boolean) res else true
            } else {
                true
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun checkClerk(publishableKey: String?, callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            try {
                Class.forName("com.clerk.api.Clerk")
                response.put("sdkAvailable", true)
                response.put("className", "com.clerk.api.Clerk")

                val key = publishableKey ?: ""
                if (key.isNotEmpty()) {
                    inMemoryPublishableKey = key
                    try {
                        val context = cordova.activity.applicationContext
                        val options = ClerkConfigurationOptions(
                            sharedSessionSync = SharedSessionSyncConfig.enabled
                        )
                        Clerk.initialize(context, key, options)
                        response.put("initialized", true)
                        response.put("publishableKey", key)
                        response.put("sharedSessionSyncEnabled", true)
                        response.put("message", "Clerk SDK initialized successfully.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Initialization failed in checkClerk", e)
                        response.put("initialized", false)
                        response.put("error", e.message ?: e.toString())
                    }
                } else {
                    response.put("initialized", isClerkInitialized())
                    response.put("message", "Clerk SDK is present on Android classpath.")
                }

                response.put("status", "success")
                response.put("platform", "android")
                response.put("timestamp", System.currentTimeMillis())
                callbackContext.success(response)
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "Clerk SDK class not found", e)
                response.put("sdkAvailable", false)
                response.put("status", "error")
                response.put("message", "Clerk SDK not found on classpath.")
                callbackContext.error(response)
            } catch (e: Throwable) {
                Log.e(TAG, "Error in checkClerk", e)
                response.put("sdkAvailable", false)
                response.put("status", "error")
                response.put("message", e.message ?: e.toString())
                callbackContext.error(response)
            }
        }
    }

    private fun initializeClerk(publishableKey: String?, enableSharedSessionSync: Boolean, callbackContext: CallbackContext) {
        val key = publishableKey ?: ""
        if (key.isEmpty()) {
            callbackContext.error("Expected a non-empty publishableKey argument.")
            return
        }
        inMemoryPublishableKey = key
        pluginScope.launch {
            try {
                val context = cordova.activity.applicationContext
                val syncConfig = if (enableSharedSessionSync) SharedSessionSyncConfig.enabled else null
                val options = ClerkConfigurationOptions(sharedSessionSync = syncConfig)

                Clerk.initialize(
                    context = context,
                    publishableKey = key,
                    options = options
                )

                val response = JSONObject().apply {
                    put("status", "success")
                    put("message", "Clerk SDK initialized successfully.")
                    put("publishableKey", key)
                    put("sharedSessionSyncEnabled", enableSharedSessionSync)
                    put("platform", "android")
                    put("timestamp", System.currentTimeMillis())
                }
                callbackContext.success(response)
            } catch (e: Throwable) {
                Log.e(TAG, "initializeClerk failed", e)
                val response = JSONObject().apply {
                    put("status", "error")
                    put("message", "Failed to initialize Clerk SDK: ${e.message}")
                    put("error", e.toString())
                }
                callbackContext.error(response)
            }
        }
    }

    private fun reloadFromSharedStorage(callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            try {
                val stateChanged = Clerk.reloadFromSharedStorage()
                response.put("status", "success")
                response.put("stateChanged", stateChanged)
                response.put("message", "Reloaded shared storage successfully.")
                callbackContext.success(response)
            } catch (e: Throwable) {
                Log.e(TAG, "reloadFromSharedStorage exception", e)
                response.put("status", "error")
                response.put("message", "Failed to reload shared storage: ${e.message}")
                response.put("error", e.toString())
                callbackContext.error(response)
            }
        }
    }

    private fun testConnection(publishableKey: String?, callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            val diagnostics = JSONObject()
            try {
                var networkReachable = false
                var responseCode = -1
                try {
                    val url = URL("https://clerk.com")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.connect()
                    responseCode = connection.responseCode
                    networkReachable = (responseCode in 200..399)
                    connection.disconnect()
                } catch (netEx: Throwable) {
                    diagnostics.put("networkError", netEx.message ?: netEx.toString())
                }

                diagnostics.put("networkReachable", networkReachable)
                diagnostics.put("httpResponseCode", responseCode)
                diagnostics.put("isSDKInitialized", isClerkInitialized())

                response.put("status", if (networkReachable) "success" else "warning")
                response.put("message", "Diagnostic pipeline executed.")
                response.put("diagnostics", diagnostics)
                response.put("timestamp", System.currentTimeMillis())

                callbackContext.success(response)
            } catch (e: Throwable) {
                response.put("status", "error")
                response.put("message", "Diagnostic pipeline failed: ${e.message}")
                callbackContext.error(response)
            }
        }
    }

    private fun signInWithPassword(identifier: String?, password: String?, callbackContext: CallbackContext) {
        val id = identifier ?: ""
        val pass = password ?: ""
        if (id.isEmpty() || pass.isEmpty()) {
            callbackContext.error("Expected non-empty identifier and password.")
            return
        }

        pluginScope.launch {
            val response = JSONObject()
            try {
                val result = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                    Clerk.auth.signInWithPassword {
                        this.identifier = id
                        this.password = pass
                    }
                }

                if (result == null) {
                    response.put("status", "error")
                    response.put("message", "Sign in request timed out after ${NETWORK_TIMEOUT_MS / 1000} seconds.")
                    response.put("errorCode", "timeout")
                    callbackContext.error(response)
                    return@launch
                }

                when (result) {
                    is com.clerk.api.network.serialization.ClerkResult.Success -> {
                        val signIn = result.value
                        val sessionId = signIn.createdSessionId
                        if (!sessionId.isNullOrEmpty()) {
                            try {
                                Clerk.auth.setActive(sessionId = sessionId)
                            } catch (t: Throwable) {
                                Log.w(TAG, "Failed to set active session: ${t.message}")
                            }
                        }

                        val user = try { Clerk.user } catch (t: Throwable) { null }
                        val activeSession = try { Clerk.session ?: Clerk.auth.sessions.firstOrNull() } catch (t: Throwable) { null }
                        val effectiveUser = user ?: activeSession?.user

                        response.put("status", "success")
                        response.put("message", "Sign in successful")
                        response.put("signInId", signIn.id)
                        response.put("signInStatus", try { signIn.status.name } catch (t: Throwable) { "COMPLETE" })
                        response.put("createdSessionId", sessionId ?: "")
                        response.put("userId", effectiveUser?.id ?: "")
                        response.put("firstName", effectiveUser?.firstName ?: "")
                        response.put("lastName", effectiveUser?.lastName ?: "")
                        response.put("identifier", id)
                        response.put("platform", "android")
                        callbackContext.success(response)
                    }

                    is com.clerk.api.network.serialization.ClerkResult.Failure -> {
                        val (errMessage, errCode) = extractClerkError(result)
                        Log.e(TAG, "signInWithPassword FAILURE: $errMessage (code: $errCode)")
                        response.put("status", "error")
                        response.put("message", errMessage)
                        response.put("errorCode", errCode)
                        response.put("error", errMessage)
                        response.put("platform", "android")
                        callbackContext.error(response)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "signInWithPassword error", e)
                response.put("status", "error")
                response.put("message", e.message ?: e.toString())
                callbackContext.error(response)
            }
        }
    }

    /**
     * Safely extract human-readable error messages from ClerkResult.Failure.
     */
    private fun extractClerkError(failure: com.clerk.api.network.serialization.ClerkResult.Failure<*>): Pair<String, String> {
        var errorMessage = ""
        var errorCode = ""
        val errorObj = failure.error
        val throwable = failure.throwable

        if (errorObj != null) {
            try {
                val errorsField = errorObj.javaClass.getDeclaredField("errors")
                errorsField.isAccessible = true
                val errorsList = errorsField.get(errorObj) as? List<*>
                if (!errorsList.isNullOrEmpty()) {
                    val firstErr = errorsList.first()
                    if (firstErr != null) {
                        val longMsgField = try { firstErr.javaClass.getDeclaredField("longMessage") } catch (t: Throwable) { null }
                        val msgField = try { firstErr.javaClass.getDeclaredField("message") } catch (t: Throwable) { null }
                        val codeField = try { firstErr.javaClass.getDeclaredField("code") } catch (t: Throwable) { null }
                        longMsgField?.isAccessible = true
                        msgField?.isAccessible = true
                        codeField?.isAccessible = true

                        errorMessage = (longMsgField?.get(firstErr) as? String)
                            ?: (msgField?.get(firstErr) as? String)
                            ?: ""
                        errorCode = (codeField?.get(firstErr) as? String) ?: ""
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "extractClerkError: could not read errors field", t)
            }
        }

        if (errorMessage.isEmpty() && throwable != null) {
            errorMessage = throwable.message ?: throwable.toString()
        }
        if (errorMessage.isEmpty()) {
            errorMessage = "Authentication failed. Please verify your credentials."
        }
        if (errorCode.isEmpty()) {
            errorCode = "auth_failed"
        }

        return Pair(errorMessage, errorCode)
    }

    private fun getFrontendApiHost(publishableKey: String): String? {
        if (publishableKey.isEmpty()) return null
        val parts = publishableKey.split("_")
        if (parts.size < 3) return null
        val encoded = parts[2].trimEnd('$')
        return try {
            val decodedBytes = android.util.Base64.decode(
                encoded,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            String(decodedBytes, Charsets.UTF_8).trimEnd('$')
        } catch (e: Exception) {
            null
        }
    }

    private fun getOrFetchDevBrowserJwt(host: String, pk: String, forceRefresh: Boolean = false): String {
        val prefs = cordova.activity.applicationContext.getSharedPreferences("clerk_prefs", android.content.Context.MODE_PRIVATE)
        if (!forceRefresh) {
            val jwt = prefs.getString("clerk_dev_browser_jwt", "") ?: ""
            if (jwt.isNotEmpty()) return jwt
        }

        var freshJwt = ""
        try {
            val url = URL("https://$host/v1/client?_is_native=true&_clerk_js_version=5.0.0")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            if (pk.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $pk")
            }
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.connect()

            val headerJwt = conn.getHeaderField("Clerk-Db-Jwt") ?: conn.getHeaderField("clerk-db-jwt") ?: ""
            if (headerJwt.isNotEmpty()) {
                freshJwt = headerJwt
            } else {
                val setCookie = conn.getHeaderField("Set-Cookie") ?: ""
                if (setCookie.contains("__clerk_db_jwt=")) {
                    freshJwt = setCookie.substringAfter("__clerk_db_jwt=").substringBefore(";")
                }
            }
            conn.disconnect()

            if (freshJwt.isNotEmpty()) {
                prefs.edit().putString("clerk_dev_browser_jwt", freshJwt).apply()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not fetch dev browser token from Clerk: ${t.message}")
        }
        return freshJwt
    }

    private fun queryClerkOAuth(host: String, pk: String, callbackUrl: String, isRetry: Boolean = false): Pair<String, String> {
        var targetUrl = ""
        var clerkErrorMessage = ""

        try {
            val dbJwt = getOrFetchDevBrowserJwt(host, pk, forceRefresh = isRetry)
            val queryUrl = if (dbJwt.isNotEmpty()) {
                "https://$host/v1/client/sign_ins?_is_native=true&_clerk_js_version=5.0.0&_clerk_db_jwt=${URLEncoder.encode(dbJwt, "UTF-8")}"
            } else {
                "https://$host/v1/client/sign_ins?_is_native=true&_clerk_js_version=5.0.0"
            }

            val url = URL(queryUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            if (pk.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $pk")
            }
            if (dbJwt.isNotEmpty()) {
                conn.setRequestProperty("Clerk-Db-Jwt", dbJwt)
            }
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val body = "strategy=oauth_microsoft&_is_native=true&redirect_url=" + URLEncoder.encode(callbackUrl, "UTF-8")
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }

            val newJwt = conn.getHeaderField("Clerk-Db-Jwt") ?: conn.getHeaderField("clerk-db-jwt") ?: ""
            if (newJwt.isNotEmpty()) {
                val prefs = cordova.activity.applicationContext.getSharedPreferences("clerk_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putString("clerk_dev_browser_jwt", newJwt).apply()
            }

            val respCode = conn.responseCode
            val stream = if (respCode in 200..399) conn.inputStream else conn.errorStream
            val respText = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            if (respText.isNotEmpty()) {
                val json = JSONObject(respText)
                val errorsArr = json.optJSONArray("errors")
                var errCode = ""
                if (errorsArr != null && errorsArr.length() > 0) {
                    val firstErr = errorsArr.getJSONObject(0)
                    clerkErrorMessage = firstErr.optString("long_message", firstErr.optString("message", ""))
                    errCode = firstErr.optString("code", "")
                }

                if (errCode == "dev_browser_unauthenticated" && !isRetry) {
                    Log.d(TAG, "dev_browser_unauthenticated detected, refreshing token and retrying...")
                    return queryClerkOAuth(host, pk, callbackUrl, isRetry = true)
                }

                val respObj = json.optJSONObject("response")
                val verificationObj = respObj?.optJSONObject("first_factor_verification")
                targetUrl = verificationObj?.optString("external_verification_redirect_url", "") ?: ""
            }
        } catch (netEx: Throwable) {
            Log.w(TAG, "Exception contacting Clerk OAuth endpoint: ${netEx.message}")
        }

        return Pair(targetUrl, clerkErrorMessage)
    }

    private fun launchInAppAuthDialog(targetUrl: String, host: String, dbJwt: String, callbackContext: CallbackContext) {
        cordova.activity.runOnUiThread {
            try {
                val context = cordova.activity
                val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)

                val rootLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(0xFFF8FAFC.toInt())
                }

                // Top Header Bar
                val headerLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(40, 32, 40, 32)
                    setBackgroundColor(0xFF1E293B.toInt())
                }

                val titleText = TextView(context).apply {
                    text = "Sign in with Microsoft"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 17f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                }

                val closeButton = TextView(context).apply {
                    text = "✕"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 22f
                    setPadding(24, 0, 8, 0)
                    setOnClickListener {
                        dialog.dismiss()
                        val errResp = JSONObject().apply {
                            put("status", "error")
                            put("message", "Sign in cancelled by user.")
                        }
                        callbackContext.error(errResp)
                    }
                }

                headerLayout.addView(titleText)
                headerLayout.addView(closeButton)

                // Progress Bar
                val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
                }

                // WebView
                val webView = WebView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
                }

                // Configure Cookies
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                if (dbJwt.isNotEmpty()) {
                    cookieManager.setCookie("https://$host", "__clerk_db_jwt=$dbJwt; path=/; domain=.$host; Secure; SameSite=None")
                    cookieManager.setCookie("https://clerk.accounts.dev", "__clerk_db_jwt=$dbJwt; path=/; domain=.clerk.accounts.dev; Secure; SameSite=None")
                    cookieManager.flush()
                }

                // Configure WebSettings
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                var isCompleted = false

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        progressBar.visibility = View.VISIBLE
                        super.onPageStarted(view, url, favicon)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        progressBar.visibility = View.GONE
                        super.onPageFinished(view, url)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        val currentUrl = request?.url?.toString() ?: ""
                        return handleInterceptUrl(currentUrl)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, currentUrl: String?): Boolean {
                        return handleInterceptUrl(currentUrl ?: "")
                    }

                    private fun handleInterceptUrl(currentUrl: String): Boolean {
                        Log.d(TAG, "In-App Auth navigation: $currentUrl")
                        if ((currentUrl.contains("/v1/client/sign_ins/callback") || currentUrl.contains("status=complete") || currentUrl.contains("created_session_id")) && !isCompleted) {
                            isCompleted = true
                            pluginScope.launch {
                                kotlinx.coroutines.delay(1500)
                                val user = try { Clerk.user } catch (t: Throwable) { null }
                                val session = try { Clerk.session ?: Clerk.auth.sessions.firstOrNull() } catch (t: Throwable) { null }
                                val effectiveUser = user ?: session?.user

                                val res = JSONObject().apply {
                                    put("status", "success")
                                    put("message", "Microsoft sign in completed successfully.")
                                    put("isSignedIn", true)
                                    put("userId", effectiveUser?.id ?: "")
                                    put("firstName", effectiveUser?.firstName ?: "")
                                    put("lastName", effectiveUser?.lastName ?: "")
                                    put("sessionId", session?.id ?: "")
                                    put("platform", "android")
                                }

                                cordova.activity.runOnUiThread {
                                    try { dialog.dismiss() } catch (t: Throwable) {}
                                    callbackContext.success(res)
                                }
                            }
                            return false
                        }
                        return false
                    }
                }

                rootLayout.addView(headerLayout)
                rootLayout.addView(progressBar)
                rootLayout.addView(webView)

                dialog.setContentView(rootLayout)
                dialog.show()

                webView.loadUrl(targetUrl)

            } catch (t: Throwable) {
                Log.e(TAG, "Failed to launch in-app auth dialog", t)
                val errResp = JSONObject().apply {
                    put("status", "error")
                    put("message", "Failed to launch in-app authentication: ${t.message}")
                }
                callbackContext.error(errResp)
            }
        }
    }

    private fun signInWithMicrosoft(publishableKeyParam: String?, callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            try {
                var pk = (publishableKeyParam ?: "").trim()
                if (pk.isEmpty()) {
                    pk = getStoredPublishableKey()
                }
                if (pk.isNotEmpty()) {
                    saveStoredPublishableKey(pk)
                }

                val host = getFrontendApiHost(pk) ?: "clerk.accounts.dev"
                val callbackUrl = "https://$host/v1/client/sign_ins/callback"
                val dbJwt = getOrFetchDevBrowserJwt(host, pk)

                val (targetUrl, clerkErrorMessage) = queryClerkOAuth(host, pk, callbackUrl)

                if (clerkErrorMessage.isNotEmpty()) {
                    Log.e(TAG, "Clerk OAuth error: $clerkErrorMessage")
                    response.put("status", "error")
                    response.put("message", clerkErrorMessage)
                    response.put("error", clerkErrorMessage)
                    response.put("platform", "android")
                    callbackContext.error(response)
                    return@launch
                }

                if (targetUrl.isEmpty()) {
                    response.put("status", "error")
                    response.put("message", "Could not retrieve Microsoft OAuth authorization URL from Clerk. Please ensure Microsoft is enabled under Social / SSO Connections in your Clerk Dashboard.")
                    response.put("platform", "android")
                    callbackContext.error(response)
                    return@launch
                }

                Log.d(TAG, "Launching Microsoft In-App Authentication Dialog: $targetUrl")
                launchInAppAuthDialog(targetUrl, host, dbJwt, callbackContext)

            } catch (e: Throwable) {
                Log.e(TAG, "signInWithMicrosoft error", e)
                response.put("status", "error")
                response.put("message", e.message ?: e.toString())
                callbackContext.error(response)
            }
        }
    }

    private fun signOut(callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            try {
                withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                    Clerk.auth.signOut()
                }
                response.put("status", "success")
                response.put("message", "Signed out successfully")
                response.put("platform", "android")
                callbackContext.success(response)
            } catch (e: Throwable) {
                Log.e(TAG, "signOut error", e)
                response.put("status", "error")
                response.put("message", e.message ?: e.toString())
                callbackContext.error(response)
            }
        }
    }

    private fun getCurrentUser(callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            try {
                var userId = ""
                var firstName = ""
                var lastName = ""
                var sessionId = ""
                var isSignedIn = false

                val userObj = try { Clerk.user } catch (t: Throwable) { null }
                val sessionObj = try { Clerk.session } catch (t: Throwable) { null }
                val sessionsObj = try { Clerk.auth.sessions } catch (t: Throwable) { emptyList() }
                val activeSession = sessionObj ?: sessionsObj.firstOrNull()
                val activeUser = userObj ?: activeSession?.user

                if (activeUser != null) {
                    isSignedIn = true
                    userId = activeUser.id
                    firstName = activeUser.firstName ?: ""
                    lastName = activeUser.lastName ?: ""
                    sessionId = activeSession?.id ?: ""
                }

                response.put("status", "success")
                response.put("isSignedIn", isSignedIn)
                response.put("userId", userId)
                response.put("firstName", firstName)
                response.put("lastName", lastName)
                response.put("sessionId", sessionId)
                response.put("platform", "android")
                if (!isSignedIn) {
                    response.put("message", "No active session found.")
                }
                callbackContext.success(response)
            } catch (e: Throwable) {
                Log.w(TAG, "getCurrentUser safe fallback", e)
                response.put("status", "success")
                response.put("isSignedIn", false)
                response.put("userId", "")
                response.put("platform", "android")
                response.put("message", "No active session found.")
                callbackContext.success(response)
            }
        }
    }

    private fun getKeychainAccessGroup(callbackContext: CallbackContext) {
        val response = JSONObject().apply {
            put("status", "success")
            put("storageType", "AndroidEncryptedSharedStorage")
            put("platform", "android")
        }
        callbackContext.success(response)
    }
}
