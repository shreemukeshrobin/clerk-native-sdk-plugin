import Foundation
import Security
import WebKit
import AuthenticationServices

/**
 * Echo Cordova Plugin implemented in Swift for iOS with Native Clerk REST API & Shared Keychain Session Engine.
 */
@objc(EchoPlugin)
class EchoPlugin : CDVPlugin {

    private static let TAG = "EchoPlugin"
    private static let SHARED_KEYCHAIN_SERVICE = "com.luvelo.clerk.sharedservice"
    private static let KEYCHAIN_ACCOUNT_KEY = "active_clerk_session_jwt"
    private static let KEYCHAIN_SESSION_ID_KEY = "active_clerk_session_id"
    private static let KEYCHAIN_PUBLISHABLE_KEY = "clerk_publishable_key"
    private static let KEYCHAIN_DEV_BROWSER_JWT_KEY = "clerk_dev_browser_jwt"
    private static let KEYCHAIN_USER_ID_KEY = "active_clerk_user_id"
    private static let KEYCHAIN_FIRST_NAME_KEY = "active_clerk_first_name"
    private static let KEYCHAIN_LAST_NAME_KEY = "active_clerk_last_name"
    private static let KEYCHAIN_EMAIL_KEY = "active_clerk_email"

    private var inMemoryPublishableKey: String = ""

    // Holds ASWebAuthenticationSession strongly to prevent premature deallocation (iOS 12+)
    private var authSession: AnyObject?

    // MARK: - Explicit Keychain & Cookie Cleaning

    private func purgeAllClerkCookies() {
        self.deleteFromKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY)
        if let cookies = HTTPCookieStorage.shared.cookies {
            for cookie in cookies {
                HTTPCookieStorage.shared.deleteCookie(cookie)
            }
        }
        DispatchQueue.main.async {
            let dataStore = WKWebsiteDataStore.default()
            let types = WKWebsiteDataStore.allWebsiteDataTypes()
            dataStore.removeData(ofTypes: types, modifiedSince: Date(timeIntervalSince1970: 0), completionHandler: {})
        }
    }

    private func saveToKeychain(key: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: EchoPlugin.SHARED_KEYCHAIN_SERVICE,
            kSecAttrAccount as String: key,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        SecItemDelete(query as CFDictionary)
        var newQuery = query
        newQuery[kSecValueData as String] = data
        SecItemAdd(newQuery as CFDictionary, nil)
    }

    private func loadFromKeychain(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: EchoPlugin.SHARED_KEYCHAIN_SERVICE,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var dataTypeRef: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &dataTypeRef)
        if status == errSecSuccess, let data = dataTypeRef as? Data, let str = String(data: data, encoding: .utf8), !str.isEmpty {
            return str
        }
        return nil
    }

    private func deleteFromKeychain(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: EchoPlugin.SHARED_KEYCHAIN_SERVICE,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }

    private func extractAndSaveDevBrowserJwt(response: URLResponse?) {
        guard let httpResp = response as? HTTPURLResponse else { return }
        if let dbJwt = httpResp.value(forHTTPHeaderField: "Clerk-Db-Jwt") ?? httpResp.value(forHTTPHeaderField: "clerk-db-jwt"), !dbJwt.isEmpty {
            self.saveToKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY, value: dbJwt)
            return
        }
        if let allHeaders = httpResp.allHeaderFields as? [String: String] {
            for (headerKey, headerVal) in allHeaders {
                if headerKey.lowercased() == "clerk-db-jwt" && !headerVal.isEmpty {
                    self.saveToKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY, value: headerVal)
                    return
                }
                if headerKey.lowercased() == "set-cookie" && headerVal.contains("__clerk_db_jwt=") {
                    let parts = headerVal.components(separatedBy: "__clerk_db_jwt=")
                    if parts.count > 1 {
                        let cookieVal = parts[1].components(separatedBy: ";").first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                        if !cookieVal.isEmpty {
                            self.saveToKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY, value: cookieVal)
                            return
                        }
                    }
                }
            }
        }
    }

    private func getFrontendApiHost(publishableKey: String) -> String? {
        let key = publishableKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty, key.contains("_") else { return nil }

        let parts = key.components(separatedBy: "_")
        guard parts.count >= 3 else { return nil }

        let rawBase64 = parts.dropFirst(2).joined(separator: "_")
            .replacingOccurrences(of: "$", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        var base64 = rawBase64
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")

        let remainder = base64.count % 4
        if remainder > 0 {
            base64 += String(repeating: "=", count: 4 - remainder)
        }

        if let data = Data(base64Encoded: base64),
           let decodedHost = String(data: data, encoding: .utf8) {
            let cleaned = decodedHost.replacingOccurrences(of: "$", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !cleaned.isEmpty {
                return cleaned
            }
        }

        return nil
    }

    // MARK: - Plugin Actions

    @objc(echo:)
    func echo(command: CDVInvokedUrlCommand) {
        let message = command.argument(at: 0) as? String ?? ""
        let pluginResult = !message.isEmpty
            ? CDVPluginResult(status: CDVCommandStatus_OK, messageAs: message)
            : CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Expected one non-empty string argument.")
        self.commandDelegate!.send(pluginResult, callbackId: command.callbackId)
    }

    @objc(echoAsync:)
    func echoAsync(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            let message = command.argument(at: 0) as? String ?? ""
            if !message.isEmpty {
                let response: [String: Any] = [
                    "status": "success",
                    "message": message,
                    "timestamp": Int64(Date().timeIntervalSince1970 * 1000),
                    "language": "Swift"
                ]
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
            } else {
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Expected one non-empty string argument."), callbackId: command.callbackId)
            }
        })
    }

    @objc(add:)
    func add(command: CDVInvokedUrlCommand) {
        guard let arg1 = command.argument(at: 0), let arg2 = command.argument(at: 1) else {
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Expected two valid numeric arguments."), callbackId: command.callbackId)
            return
        }

        let num1: Double = (arg1 as? NSNumber)?.doubleValue ?? (Double(arg1 as? String ?? "") ?? Double.nan)
        let num2: Double = (arg2 as? NSNumber)?.doubleValue ?? (Double(arg2 as? String ?? "") ?? Double.nan)

        if num1.isNaN || num2.isNaN {
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Expected two valid numeric arguments."), callbackId: command.callbackId)
            return
        }

        let response: [String: Any] = [
            "num1": num1,
            "num2": num2,
            "sum": num1 + num2,
            "platform": "ios",
            "language": "Swift"
        ]
        self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
    }

    @objc(checkClerk:)
    func checkClerk(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            let publishableKey = command.argument(at: 0) as? String ?? ""
            let keyToUse = !publishableKey.isEmpty ? publishableKey : (self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_PUBLISHABLE_KEY) ?? "")

            var response: [String: Any] = [
                "sdkAvailable": true,
                "initialized": !keyToUse.isEmpty,
                "status": "success",
                "platform": "ios",
                "message": !keyToUse.isEmpty ? "Clerk native iOS SDK bridge is ready and initialized." : "Clerk native iOS SDK bridge is present.",
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
            ]
            if !keyToUse.isEmpty {
                response["publishableKey"] = keyToUse
            }
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
        })
    }

    @objc(initializeClerk:)
    func initializeClerk(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            guard let publishableKey = command.argument(at: 0) as? String, !publishableKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Expected a non-empty publishableKey string argument."), callbackId: command.callbackId)
                return
            }

            let enableSharedSessionSync = command.argument(at: 1) as? Bool ?? true
            let key = publishableKey.trimmingCharacters(in: .whitespacesAndNewlines)

            self.inMemoryPublishableKey = key
            self.saveToKeychain(key: EchoPlugin.KEYCHAIN_PUBLISHABLE_KEY, value: key)

            let response: [String: Any] = [
                "status": "success",
                "message": "Clerk SDK configured successfully on iOS with Shared Session Sync.",
                "publishableKey": key,
                "sharedSessionSyncEnabled": enableSharedSessionSync,
                "platform": "ios",
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
            ]
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
        })
    }

    private func executeSignIn(id: String, pass: String, pk: String, host: String, isRetry: Bool, completion: @escaping ([String: Any]?, Int, Error?) -> Void) {
        guard var urlComponents = URLComponents(string: "https://\(host)/v1/client/sign_ins") else {
            completion(["status": "error", "message": "Invalid Clerk Frontend API URL for host: \(host)", "errorCode": "invalid_url"], 0, nil)
            return
        }

        var queryItems = [
            URLQueryItem(name: "_clerk_js_version", value: "5.0.0"),
            URLQueryItem(name: "_is_native", value: "true")
        ]
        if !isRetry, let dbJwt = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY), !dbJwt.isEmpty {
            queryItems.append(URLQueryItem(name: "_clerk_db_jwt", value: dbJwt))
        }
        urlComponents.queryItems = queryItems

        guard let url = urlComponents.url else {
            completion(["status": "error", "message": "Failed to construct URL"], 0, nil)
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        if !pk.isEmpty {
            request.setValue("Bearer \(pk)", forHTTPHeaderField: "Authorization")
        }
        if !isRetry, let dbJwt = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY), !dbJwt.isEmpty {
            request.setValue(dbJwt, forHTTPHeaderField: "Clerk-Db-Jwt")
        }

        let encodedId = id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? id
        let encodedPass = pass.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? pass
        let bodyString = "strategy=password&identifier=\(encodedId)&password=\(encodedPass)"
        request.httpBody = bodyString.data(using: .utf8)

        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            self.extractAndSaveDevBrowserJwt(response: response)
            var statusCode = 0
            if let httpResp = response as? HTTPURLResponse {
                statusCode = httpResp.statusCode
            }
            var responseJson: [String: Any]?
            if let data = data, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                responseJson = json
            }
            completion(responseJson, statusCode, error)
        }
        task.resume()
    }

    private func processSignInResponse(json: [String: Any]?, statusCode: Int, netErr: Error?, host: String, id: String, command: CDVInvokedUrlCommand) {
        if let json = json, let errorsList = json["errors"] as? [[String: Any]], let firstErr = errorsList.first {
            let errorMsg = firstErr["long_message"] as? String ?? (firstErr["message"] as? String ?? "Sign in failed")
            let errorCode = firstErr["code"] as? String ?? "authentication_failed"
            let response: [String: Any] = [
                "status": "error",
                "message": errorMsg,
                "errorCode": errorCode,
                "error": errorMsg,
                "platform": "ios"
            ]
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: response), callbackId: command.callbackId)
            return
        }

        if let netErr = netErr {
            let response: [String: Any] = [
                "status": "error",
                "message": "Network error connecting to \(host): \(netErr.localizedDescription)",
                "errorCode": "network_error",
                "platform": "ios"
            ]
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: response), callbackId: command.callbackId)
            return
        }

        if let json = json, let clientObj = json["client"] as? [String: Any] {
            var createdSessionId = ""
            var jwtToken = ""
            var userId = ""
            var firstName = ""
            var lastName = ""
            var userEmail = ""

            if let responseObj = json["response"] as? [String: Any] {
                createdSessionId = responseObj["created_session_id"] as? String ?? ""
            }
            if createdSessionId.isEmpty, let activeSessId = clientObj["active_session_id"] as? String {
                createdSessionId = activeSessId
            }

            if let sessionsList = clientObj["sessions"] as? [[String: Any]] {
                let activeSess = sessionsList.first(where: { ($0["id"] as? String) == createdSessionId }) ?? sessionsList.first
                if let sess = activeSess {
                    if let lastActiveToken = sess["last_active_token"] as? [String: Any], let jwt = lastActiveToken["jwt"] as? String {
                        jwtToken = jwt
                    }
                    if let userObj = sess["user"] as? [String: Any] {
                        userId = userObj["id"] as? String ?? ""
                        firstName = userObj["first_name"] as? String ?? ""
                        lastName = userObj["last_name"] as? String ?? ""
                        if let emailList = userObj["email_addresses"] as? [[String: Any]], let firstEmail = emailList.first {
                            userEmail = firstEmail["email_address"] as? String ?? ""
                        }
                    }
                }
            }

            if !createdSessionId.isEmpty {
                self.saveToKeychain(key: EchoPlugin.KEYCHAIN_SESSION_ID_KEY, value: createdSessionId)
            }
            if !jwtToken.isEmpty {
                self.saveToKeychain(key: EchoPlugin.KEYCHAIN_ACCOUNT_KEY, value: jwtToken)
            } else if !createdSessionId.isEmpty {
                self.saveToKeychain(key: EchoPlugin.KEYCHAIN_ACCOUNT_KEY, value: createdSessionId)
            }
            if !userId.isEmpty {
                self.saveToKeychain(key: EchoPlugin.KEYCHAIN_USER_ID_KEY, value: userId)
            }
            if !firstName.isEmpty {
                self.saveToKeychain(key: EchoPlugin.KEYCHAIN_FIRST_NAME_KEY, value: firstName)
            }
            if !lastName.isEmpty {
                self.saveToKeychain(key: EchoPlugin.KEYCHAIN_LAST_NAME_KEY, value: lastName)
            }
            if !userEmail.isEmpty {
                self.saveToKeychain(key: EchoPlugin.KEYCHAIN_EMAIL_KEY, value: userEmail)
            }

            let response: [String: Any] = [
                "status": "success",
                "message": "Sign in successful",
                "identifier": id,
                "signInStatus": "COMPLETE",
                "createdSessionId": createdSessionId,
                "userId": userId,
                "firstName": firstName,
                "lastName": lastName,
                "platform": "ios"
            ]
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
        } else {
            let statusText = statusCode > 0 ? "HTTP \(statusCode)" : "No Response"
            let response: [String: Any] = [
                "status": "error",
                "message": "Unable to authenticate with Clerk server (\(statusText)). Please check publishable key and internet connection.",
                "errorCode": "connection_failed",
                "platform": "ios"
            ]
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: response), callbackId: command.callbackId)
        }
    }

    @objc(signInWithPassword:)
    func signInWithPassword(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            guard let identifier = command.argument(at: 0) as? String, !identifier.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  let password = command.argument(at: 1) as? String, !password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Expected non-empty identifier and password arguments."), callbackId: command.callbackId)
                return
            }

            let id = identifier.trimmingCharacters(in: .whitespacesAndNewlines)
            let pass = password.trimmingCharacters(in: .whitespacesAndNewlines)
            let pk = self.inMemoryPublishableKey.isEmpty ? (self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_PUBLISHABLE_KEY) ?? "") : self.inMemoryPublishableKey
            guard let host = self.getFrontendApiHost(publishableKey: pk), !host.isEmpty else {
                let response: [String: Any] = [
                    "status": "error",
                    "message": "Clerk publishable key is missing or invalid. Please call initializeClerk(publishableKey) first.",
                    "errorCode": "clerk_not_initialized",
                    "platform": "ios"
                ]
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: response), callbackId: command.callbackId)
                return
            }

            self.executeSignIn(id: id, pass: pass, pk: pk, host: host, isRetry: false) { json, statusCode, netErr in
                // Check if dev_browser_unauthenticated occurred
                if let json = json, let errorsList = json["errors"] as? [[String: Any]], let firstErr = errorsList.first {
                    let errorCode = firstErr["code"] as? String ?? ""
                    if errorCode == "dev_browser_unauthenticated" {
                        // Purge all stale Clerk cookies and dev browser token, then auto-retry with clean state
                        self.purgeAllClerkCookies()
                        self.executeSignIn(id: id, pass: pass, pk: pk, host: host, isRetry: true) { retryJson, retryStatus, retryErr in
                            self.processSignInResponse(json: retryJson, statusCode: retryStatus, netErr: retryErr, host: host, id: id, command: command)
                        }
                        return
                    }
                }

                self.processSignInResponse(json: json, statusCode: statusCode, netErr: netErr, host: host, id: id, command: command)
            }
        })
    }

    @objc(signInWithMicrosoft:)
    func signInWithMicrosoft(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            let pkArg = command.arguments.first as? String ?? ""
            let pk = !pkArg.isEmpty ? pkArg : (self.inMemoryPublishableKey.isEmpty ? (self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_PUBLISHABLE_KEY) ?? "") : self.inMemoryPublishableKey)
            let host = self.getFrontendApiHost(publishableKey: pk) ?? "clerk.accounts.dev"
            let callbackUrl = "https://\(host)/v1/client/sign_ins/callback"

            var urlComponents = URLComponents(string: "https://\(host)/v1/client/sign_ins")
            var queryItems = [
                URLQueryItem(name: "_is_native", value: "true"),
                URLQueryItem(name: "_clerk_js_version", value: "5.0.0")
            ]
            let dbJwt = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY) ?? ""
            if !dbJwt.isEmpty {
                queryItems.append(URLQueryItem(name: "_clerk_db_jwt", value: dbJwt))
            }
            urlComponents?.queryItems = queryItems

            guard let url = urlComponents?.url else {
                let errResp: [String: Any] = ["status": "error", "message": "Invalid Clerk API host", "platform": "ios"]
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: errResp), callbackId: command.callbackId)
                return
            }

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            if !pk.isEmpty {
                request.setValue("Bearer \(pk)", forHTTPHeaderField: "Authorization")
            }
            if !dbJwt.isEmpty {
                request.setValue(dbJwt, forHTTPHeaderField: "Clerk-Db-Jwt")
            }
            let encodedCallback = callbackUrl.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? callbackUrl
            request.httpBody = "strategy=oauth_microsoft&_is_native=true&redirect_url=\(encodedCallback)".data(using: .utf8)

            let sem = DispatchSemaphore(value: 0)
            var targetUrl = ""
            var clerkError = ""

            URLSession.shared.dataTask(with: request) { data, _, _ in
                if let data = data, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    if let errors = json["errors"] as? [[String: Any]], let firstErr = errors.first {
                        clerkError = firstErr["long_message"] as? String ?? (firstErr["message"] as? String ?? "")
                    }
                    if let resp = json["response"] as? [String: Any], let verification = resp["first_factor_verification"] as? [String: Any],
                       let extUrl = verification["external_verification_redirect_url"] as? String {
                        targetUrl = extUrl
                    }
                }
                sem.signal()
            }.resume()
            _ = sem.wait(timeout: .now() + 10.0)

            if !clerkError.isEmpty {
                let errResp: [String: Any] = ["status": "error", "message": clerkError, "error": clerkError, "platform": "ios"]
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: errResp), callbackId: command.callbackId)
                return
            }

            guard !targetUrl.isEmpty, let openUrl = URL(string: targetUrl) else {
                let errResp: [String: Any] = [
                    "status": "error",
                    "message": "Could not retrieve Microsoft OAuth authorization URL from Clerk. Please ensure Microsoft is enabled under Social / SSO Connections in your Clerk Dashboard.",
                    "platform": "ios"
                ]
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: errResp), callbackId: command.callbackId)
                return
            }

            DispatchQueue.main.async {
                UIApplication.shared.open(openUrl, options: [:], completionHandler: nil)
            }

            let response: [String: Any] = [
                "status": "success",
                "message": "Microsoft OAuth browser opened.",
                "url": targetUrl,
                "requiresRedirect": true,
                "platform": "ios"
            ]
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
        })
    }

    @objc(signInWithEnterpriseSso:)
    func signInWithEnterpriseSso(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            let email = command.arguments.first as? String ?? ""
            let pk = !self.inMemoryPublishableKey.isEmpty ? self.inMemoryPublishableKey : (self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_PUBLISHABLE_KEY) ?? "")
            let host = self.getFrontendApiHost(publishableKey: pk) ?? "clerk.accounts.dev"
            let dbJwt = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY) ?? ""
            let callbackUrl = !dbJwt.isEmpty ? "https://\(host)/v1/client/sign_ins/callback?__clerk_db_jwt=\(dbJwt)" : "https://\(host)/v1/client/sign_ins/callback"

            var urlComponents = URLComponents(string: "https://\(host)/v1/client/sign_ins")
            var queryItems = [
                URLQueryItem(name: "_is_native", value: "true"),
                URLQueryItem(name: "_clerk_js_version", value: "5.0.0")
            ]
            if !dbJwt.isEmpty {
                queryItems.append(URLQueryItem(name: "_clerk_db_jwt", value: dbJwt))
            }
            urlComponents?.queryItems = queryItems

            guard let url = urlComponents?.url else {
                let errResp: [String: Any] = ["status": "error", "message": "Invalid Clerk API host", "platform": "ios"]
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: errResp), callbackId: command.callbackId)
                return
            }

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            if !pk.isEmpty {
                request.setValue("Bearer \(pk)", forHTTPHeaderField: "Authorization")
            }
            if !dbJwt.isEmpty {
                request.setValue(dbJwt, forHTTPHeaderField: "Clerk-Db-Jwt")
            }

            let encodedEmail = email.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? email
            let encodedCallback = callbackUrl.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? callbackUrl
            request.httpBody = "strategy=enterprise_sso&identifier=\(encodedEmail)&_is_native=true&redirect_url=\(encodedCallback)".data(using: .utf8)

            let sem = DispatchSemaphore(value: 0)
            var targetUrl = ""
            var clerkError = ""

            URLSession.shared.dataTask(with: request) { data, _, _ in
                if let data = data, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    if let errors = json["errors"] as? [[String: Any]], let firstErr = errors.first {
                        clerkError = firstErr["long_message"] as? String ?? (firstErr["message"] as? String ?? "")
                    }
                    if let resp = json["response"] as? [String: Any], let verification = resp["first_factor_verification"] as? [String: Any],
                       let extUrl = verification["external_verification_redirect_url"] as? String {
                        targetUrl = extUrl
                    }
                }
                sem.signal()
            }.resume()
            _ = sem.wait(timeout: .now() + 10.0)

            if !clerkError.isEmpty {
                let errResp: [String: Any] = ["status": "error", "message": clerkError, "error": clerkError, "platform": "ios"]
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: errResp), callbackId: command.callbackId)
                return
            }

            guard !targetUrl.isEmpty, let openUrl = URL(string: targetUrl) else {
                let errResp: [String: Any] = [
                    "status": "error",
                    "message": "Could not retrieve Enterprise SSO URL from Clerk.",
                    "platform": "ios"
                ]
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: errResp), callbackId: command.callbackId)
                return
            }

            DispatchQueue.main.async {
                UIApplication.shared.open(openUrl, options: [:], completionHandler: nil)
            }

            let response: [String: Any] = [
                "status": "success",
                "message": "Enterprise SSO browser opened.",
                "url": targetUrl,
                "requiresRedirect": true,
                "platform": "ios"
            ]
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
        })
    }

    @objc(signUpWithEnterpriseSso:)
    func signUpWithEnterpriseSso(command: CDVInvokedUrlCommand) {
        self.signInWithEnterpriseSso(command: command)
    }

    @objc(startHostedAuth:)
    func startHostedAuth(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            let pk = !self.inMemoryPublishableKey.isEmpty ? self.inMemoryPublishableKey : (self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_PUBLISHABLE_KEY) ?? "")
            let host = self.getFrontendApiHost(publishableKey: pk) ?? "clerk.accounts.dev"

            // Optional redirectUrl from JS (e.g. "org.luvelo.dev.ClerkApp1://callback")
            // Must be registered in your Clerk Dashboard → Allowlist for mobile SSO redirect
            let redirectUrl = command.arguments.count > 0 ? (command.arguments[0] as? String ?? "") : ""

            var signInUrl = ""
            var dbJwt = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY) ?? ""

            // 1. Query Clerk /v1/environment for official sign_in_url and dev browser token
            let sem = DispatchSemaphore(value: 0)
            if let envUrl = URL(string: "https://\(host)/v1/environment") {
                var req = URLRequest(url: envUrl)
                req.httpMethod = "GET"
                if !pk.isEmpty {
                    req.setValue("Bearer \(pk)", forHTTPHeaderField: "Authorization")
                }
                URLSession.shared.dataTask(with: req) { data, response, _ in
                    self.extractAndSaveDevBrowserJwt(response: response)
                    if let data = data,
                       let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                       let displayConfig = json["display_config"] as? [String: Any],
                       let customSignInUrl = displayConfig["sign_in_url"] as? String,
                       !customSignInUrl.isEmpty {
                        signInUrl = customSignInUrl
                    }
                    sem.signal()
                }.resume()
                _ = sem.wait(timeout: .now() + 5.0)
                dbJwt = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY) ?? ""
            }

            // 2. Fallback: derive frontend URL from API host
            if signInUrl.isEmpty {
                var portalHost = host
                if portalHost.contains(".clerk.accounts.dev") {
                    portalHost = portalHost.replacingOccurrences(of: ".clerk.accounts.dev", with: ".accounts.dev")
                } else if portalHost.hasPrefix("clerk.") {
                    portalHost = String(portalHost.dropFirst(6))
                }
                signInUrl = "https://\(portalHost)/sign-in"
            }

            // 3. Determine redirect URL and callback scheme
            //    Bundle ID is a valid URL scheme on iOS (e.g. "org.luvelo.dev.ClerkApp1")
            let bundleId = Bundle.main.bundleIdentifier ?? "clerk"
            let effectiveRedirectUrl: String
            let callbackScheme: String

            if !redirectUrl.isEmpty, let scheme = URL(string: redirectUrl)?.scheme, !scheme.isEmpty {
                effectiveRedirectUrl = redirectUrl
                callbackScheme = scheme
            } else {
                // Clerk's documented default for hosted auth on iOS: <bundle-identifier>://callback
                // https://clerk.com/docs/ios/guides/account-portal/hosted-auth
                // bundleId must also be registered as a CFBundleURLScheme (plugin.xml).
                callbackScheme = bundleId
                effectiveRedirectUrl = "\(bundleId)://callback"
            }

            // 4. Build final URL with comprehensive redirect parameters + dev browser JWT
            var urlComponents = URLComponents(string: signInUrl) ?? URLComponents()
            var queryItems = urlComponents.queryItems ?? []
            // Tells the Account Portal this is a native app context, so it validates
            // effectiveRedirectUrl against the Native Applications allowlist instead of
            // treating a non-https redirect target as invalid. See signInWithEnterpriseSso
            // above, which sets the same flag on its sign_ins POST body.
            queryItems.append(URLQueryItem(name: "_is_native", value: "true"))
            queryItems.append(URLQueryItem(name: "redirect_url", value: effectiveRedirectUrl))
            queryItems.append(URLQueryItem(name: "force_redirect_url", value: effectiveRedirectUrl))
            queryItems.append(URLQueryItem(name: "fallback_redirect_url", value: effectiveRedirectUrl))
            queryItems.append(URLQueryItem(name: "after_sign_in_url", value: effectiveRedirectUrl))
            queryItems.append(URLQueryItem(name: "after_sign_up_url", value: effectiveRedirectUrl))
            if !dbJwt.isEmpty {
                queryItems.append(URLQueryItem(name: "__clerk_db_jwt", value: dbJwt))
            }
            urlComponents.queryItems = queryItems

            guard let openUrl = urlComponents.url else {
                let errResp: [String: Any] = ["status": "error", "message": "Invalid Account Portal URL", "platform": "ios"]
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: errResp), callbackId: command.callbackId)
                return
            }

            DispatchQueue.main.async {
                if #available(iOS 12.0, *) {
                    let session = ASWebAuthenticationSession(url: openUrl, callbackURLScheme: callbackScheme) { [weak self] callbackURL, error in
                        guard let self = self else { return }
                        if let error = error {
                            let errCode = (error as NSError).code
                            if errCode == ASWebAuthenticationSessionError.canceledLogin.rawValue {
                                let errResp: [String: Any] = ["status": "error", "message": "User canceled authentication.", "errorCode": "user_canceled", "platform": "ios"]
                                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: errResp), callbackId: command.callbackId)
                                return
                            }
                        }

                        // Query Clerk /v1/client in background to synchronize session into shared Keychain
                        self.commandDelegate!.run(inBackground: {
                            var userId = ""
                            var firstName = ""
                            var lastName = ""
                            var sessionId = ""

                            if let clientUrl = URL(string: "https://\(host)/v1/client") {
                                var req = URLRequest(url: clientUrl)
                                req.httpMethod = "GET"
                                if !pk.isEmpty {
                                    req.setValue("Bearer \(pk)", forHTTPHeaderField: "Authorization")
                                }
                                let dbToken = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY) ?? ""
                                if !dbToken.isEmpty {
                                    req.setValue(dbToken, forHTTPHeaderField: "Clerk-Db-Jwt")
                                }

                                let sem = DispatchSemaphore(value: 0)
                                URLSession.shared.dataTask(with: req) { data, resp, _ in
                                    self.extractAndSaveDevBrowserJwt(response: resp)
                                    if let data = data,
                                       let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                                       let clientObj = json["client"] as? [String: Any],
                                       let sessionsList = clientObj["sessions"] as? [[String: Any]],
                                       let activeSession = sessionsList.first {

                                        sessionId = activeSession["id"] as? String ?? ""
                                        if let lastActiveToken = activeSession["last_active_token"] as? [String: Any],
                                           let jwt = lastActiveToken["jwt"] as? String {
                                            self.saveToKeychain(key: EchoPlugin.KEYCHAIN_ACCOUNT_KEY, value: jwt)
                                        }
                                        if let userObj = activeSession["user"] as? [String: Any] {
                                            userId = userObj["id"] as? String ?? ""
                                            firstName = userObj["first_name"] as? String ?? ""
                                            lastName = userObj["last_name"] as? String ?? ""
                                            if let emailList = userObj["email_addresses"] as? [[String: Any]],
                                               let firstEmail = emailList.first,
                                               let email = firstEmail["email_address"] as? String {
                                                self.saveToKeychain(key: EchoPlugin.KEYCHAIN_EMAIL_KEY, value: email)
                                            }
                                        }

                                        if !sessionId.isEmpty {
                                            self.saveToKeychain(key: EchoPlugin.KEYCHAIN_SESSION_ID_KEY, value: sessionId)
                                        }
                                        if !userId.isEmpty {
                                            self.saveToKeychain(key: EchoPlugin.KEYCHAIN_USER_ID_KEY, value: userId)
                                        }
                                        if !firstName.isEmpty {
                                            self.saveToKeychain(key: EchoPlugin.KEYCHAIN_FIRST_NAME_KEY, value: firstName)
                                        }
                                        if !lastName.isEmpty {
                                            self.saveToKeychain(key: EchoPlugin.KEYCHAIN_LAST_NAME_KEY, value: lastName)
                                        }
                                    }
                                    sem.signal()
                                }.resume()
                                _ = sem.wait(timeout: .now() + 5.0)
                            }

                            var response: [String: Any] = [
                                "status": "success",
                                "message": "Hosted authentication completed successfully.",
                                "isSignedIn": true,
                                "callbackUrl": callbackURL?.absoluteString ?? "",
                                "platform": "ios"
                            ]
                            if !userId.isEmpty { response["userId"] = userId }
                            if !firstName.isEmpty { response["firstName"] = firstName }
                            if !lastName.isEmpty { response["lastName"] = lastName }
                            if !sessionId.isEmpty { response["sessionId"] = sessionId }

                            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
                        })
                    }

                    if #available(iOS 13.0, *) {
                        session.presentationContextProvider = self
                    }
                    session.prefersEphemeralWebBrowserSession = false
                    self.authSession = session

                    if !session.start() {
                        UIApplication.shared.open(openUrl, options: [:], completionHandler: nil)
                        let response: [String: Any] = [
                            "status": "success",
                            "message": "Hosted Account Portal opened.",
                            "url": openUrl.absoluteString,
                            "requiresRedirect": true,
                            "platform": "ios"
                        ]
                        self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
                    }
                } else {
                    UIApplication.shared.open(openUrl, options: [:], completionHandler: nil)
                    let response: [String: Any] = [
                        "status": "success",
                        "message": "Hosted Account Portal opened.",
                        "url": openUrl.absoluteString,
                        "requiresRedirect": true,
                        "platform": "ios"
                    ]
                    self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
                }
            }
        })
    }

    @objc(getHostedAuthDebugInfo:)
    func getHostedAuthDebugInfo(command: CDVInvokedUrlCommand) {
        let bundleId = Bundle.main.bundleIdentifier ?? "unknown"
        let redirectUrl = command.arguments.count > 0 ? (command.arguments[0] as? String ?? "") : ""

        let effectiveRedirectUrl: String
        let callbackScheme: String
        if !redirectUrl.isEmpty, let scheme = URL(string: redirectUrl)?.scheme, !scheme.isEmpty {
            effectiveRedirectUrl = redirectUrl
            callbackScheme = scheme
        } else {
            callbackScheme = bundleId
            effectiveRedirectUrl = "\(bundleId)://callback"
        }

        let response: [String: Any] = [
            "bundleId": bundleId,
            "callbackScheme": callbackScheme,
            "effectiveRedirectUrl": effectiveRedirectUrl,
            "pluginVersion": "1.0.11",
            "platform": "ios"
        ]
        self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
    }

    @objc(getCurrentUser:)
    func getCurrentUser(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            let sessionId = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_SESSION_ID_KEY) ?? ""
            let userId = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_USER_ID_KEY) ?? ""
            let firstName = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_FIRST_NAME_KEY) ?? ""
            let lastName = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_LAST_NAME_KEY) ?? ""
            let email = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_EMAIL_KEY) ?? ""

            // If no active session or user exists in Keychain, user is signed out
            if sessionId.isEmpty && userId.isEmpty {
                let response: [String: Any] = [
                    "status": "success",
                    "isSignedIn": false,
                    "message": "No active signed-in user session found.",
                    "platform": "ios"
                ]
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
                return
            }

            // We have an active session! Return signed-in user metadata
            var response: [String: Any] = [
                "status": "success",
                "isSignedIn": true,
                "userId": userId.isEmpty ? "user_shared_session" : userId,
                "firstName": firstName,
                "lastName": lastName,
                "email": email,
                "sessionId": sessionId,
                "platform": "ios"
            ]

            // Best-effort live refresh from Clerk /v1/client using Publishable Key
            let pk = self.inMemoryPublishableKey.isEmpty ? (self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_PUBLISHABLE_KEY) ?? "") : self.inMemoryPublishableKey
            if let host = self.getFrontendApiHost(publishableKey: pk), !host.isEmpty, var urlComponents = URLComponents(string: "https://\(host)/v1/client") {
                var queryItems = [URLQueryItem(name: "_clerk_js_version", value: "5.0.0")]
                if let dbJwt = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY), !dbJwt.isEmpty {
                    queryItems.append(URLQueryItem(name: "_clerk_db_jwt", value: dbJwt))
                }
                urlComponents.queryItems = queryItems

                if let url = urlComponents.url {
                    var request = URLRequest(url: url)
                    request.httpMethod = "GET"
                    request.setValue("Bearer \(pk)", forHTTPHeaderField: "Authorization")
                    if let dbJwt = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_DEV_BROWSER_JWT_KEY), !dbJwt.isEmpty {
                        request.setValue(dbJwt, forHTTPHeaderField: "Clerk-Db-Jwt")
                    }

                    let semaphore = DispatchSemaphore(value: 0)
                    var responseJson: [String: Any]?
                    var statusCode: Int = 0

                    let task = URLSession.shared.dataTask(with: request) { data, resp, _ in
                        self.extractAndSaveDevBrowserJwt(response: resp)
                        if let httpResp = resp as? HTTPURLResponse {
                            statusCode = httpResp.statusCode
                        }
                        if let data = data, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                            responseJson = json
                        }
                        semaphore.signal()
                    }
                    task.resume()
                    _ = semaphore.wait(timeout: .now() + 3.0)

                    if (statusCode == 200 || statusCode == 304), let json = responseJson, let clientObj = json["client"] as? [String: Any],
                       let sessionsList = clientObj["sessions"] as? [[String: Any]], let activeSession = sessionsList.first(where: { ($0["id"] as? String) == sessionId }) ?? sessionsList.first,
                       let userObj = activeSession["user"] as? [String: Any] {

                        let freshUserId = userObj["id"] as? String ?? userId
                        let freshFirstName = userObj["first_name"] as? String ?? firstName
                        let freshLastName = userObj["last_name"] as? String ?? lastName

                        self.saveToKeychain(key: EchoPlugin.KEYCHAIN_USER_ID_KEY, value: freshUserId)
                        self.saveToKeychain(key: EchoPlugin.KEYCHAIN_FIRST_NAME_KEY, value: freshFirstName)
                        self.saveToKeychain(key: EchoPlugin.KEYCHAIN_LAST_NAME_KEY, value: freshLastName)

                        response["userId"] = freshUserId
                        response["firstName"] = freshFirstName
                        response["lastName"] = freshLastName
                    }
                }
            }

            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
        })
    }

    @objc(signOut:)
    func signOut(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            let sessionId = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_SESSION_ID_KEY) ?? ""
            let pk = self.inMemoryPublishableKey.isEmpty ? (self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_PUBLISHABLE_KEY) ?? "") : self.inMemoryPublishableKey
            let sessionToken = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_ACCOUNT_KEY) ?? ""

            // 1. Attempt server-side session revoke on Clerk
            if !sessionId.isEmpty, let host = self.getFrontendApiHost(publishableKey: pk), let url = URL(string: "https://\(host)/v1/client/sessions/\(sessionId)/remove") {
                var req = URLRequest(url: url)
                req.httpMethod = "POST"
                if !sessionToken.isEmpty {
                    req.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
                } else if !pk.isEmpty {
                    req.setValue("Bearer \(pk)", forHTTPHeaderField: "Authorization")
                }
                let sem = DispatchSemaphore(value: 0)
                URLSession.shared.dataTask(with: req) { _, _, _ in sem.signal() }.resume()
                _ = sem.wait(timeout: .now() + 3.0)
            }

            // 2. Delete all session & user keys from Keychain
            self.deleteFromKeychain(key: EchoPlugin.KEYCHAIN_ACCOUNT_KEY)
            self.deleteFromKeychain(key: EchoPlugin.KEYCHAIN_SESSION_ID_KEY)
            self.deleteFromKeychain(key: EchoPlugin.KEYCHAIN_USER_ID_KEY)
            self.deleteFromKeychain(key: EchoPlugin.KEYCHAIN_FIRST_NAME_KEY)
            self.deleteFromKeychain(key: EchoPlugin.KEYCHAIN_LAST_NAME_KEY)
            self.deleteFromKeychain(key: EchoPlugin.KEYCHAIN_EMAIL_KEY)

            // 3. Purge all Clerk cookies and dev tokens
            self.purgeAllClerkCookies()

            let response: [String: Any] = [
                "status": "success",
                "message": "Signed out successfully",
                "platform": "ios"
            ]
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
        })
    }

    @objc(reloadFromSharedStorage:)
    func reloadFromSharedStorage(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            let activeSessionToken = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_ACCOUNT_KEY) ?? ""
            let activeSessionId = self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_SESSION_ID_KEY) ?? ""
            let hasSession = !activeSessionToken.isEmpty || !activeSessionId.isEmpty

            let response: [String: Any] = [
                "status": "success",
                "stateChanged": hasSession,
                "sessionId": activeSessionId,
                "message": hasSession ? "Reloaded shared Keychain storage. Active session found." : "Reloaded shared Keychain storage. No active session found.",
                "platform": "ios"
            ]
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
        })
    }

    @objc(testConnection:)
    func testConnection(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            let publishableKey = command.argument(at: 0) as? String ?? ""
            let keyToUse = !publishableKey.isEmpty ? publishableKey : (self.loadFromKeychain(key: EchoPlugin.KEYCHAIN_PUBLISHABLE_KEY) ?? "")

            var diagnostics: [String: Any] = [
                "platform": "ios",
                "sdkAvailable": true,
                "isSDKInitialized": !keyToUse.isEmpty
            ]

            let url = URL(string: "https://api.clerk.com/v1/environment")!
            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            request.timeoutInterval = 5.0
            if !keyToUse.isEmpty {
                request.addValue("Bearer \(keyToUse)", forHTTPHeaderField: "Authorization")
            }

            let semaphore = DispatchSemaphore(value: 0)
            var networkReachable = false
            var responseCode = -1

            let task = URLSession.shared.dataTask(with: request) { data, response, error in
                self.extractAndSaveDevBrowserJwt(response: response)
                if let httpResp = response as? HTTPURLResponse {
                    responseCode = httpResp.statusCode
                    networkReachable = (responseCode > 0)
                }
                semaphore.signal()
            }
            task.resume()
            _ = semaphore.wait(timeout: .now() + 5.0)

            diagnostics["networkReachable"] = networkReachable
            diagnostics["httpResponseCode"] = responseCode

            let response: [String: Any] = [
                "status": networkReachable ? "success" : "warning",
                "message": "Connection diagnostic pipeline executed on iOS.",
                "diagnostics": diagnostics,
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
            ]
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
        })
    }

    @objc(getKeychainAccessGroup:)
    func getKeychainAccessGroup(command: CDVInvokedUrlCommand) {
        self.commandDelegate!.run(inBackground: {
            let response: [String: Any] = [
                "status": "success",
                "accessGroup": "org.luvelo.dev.shared",
                "service": EchoPlugin.SHARED_KEYCHAIN_SERVICE,
                "platform": "ios"
            ]
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response), callbackId: command.callbackId)
        })
    }
}

@available(iOS 13.0, *)
extension EchoPlugin: ASWebAuthenticationPresentationContextProviding {
    public func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        return self.viewController.view.window ?? ASPresentationAnchor()
    }
}
