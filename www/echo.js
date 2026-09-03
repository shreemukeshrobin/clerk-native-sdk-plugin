var exec = require('cordova/exec');

var Echo = {
    echo: function (phrase, successCallback, errorCallback) {
        if (typeof phrase !== 'string' || phrase.trim() === '') {
            if (typeof errorCallback === 'function') {
                errorCallback('Expected a non-empty string argument.');
            }
            return;
        }
        exec(successCallback, errorCallback, 'Echo', 'echo', [phrase]);
    },

    echoAsync: function (phrase, successCallback, errorCallback) {
        if (typeof phrase !== 'string' || phrase.trim() === '') {
            if (typeof errorCallback === 'function') {
                errorCallback('Expected a non-empty string argument.');
            }
            return;
        }
        exec(successCallback, errorCallback, 'Echo', 'echoAsync', [phrase]);
    },

    add: function (num1, num2, successCallback, errorCallback) {
        var n1 = parseFloat(num1);
        var n2 = parseFloat(num2);
        if (isNaN(n1) || isNaN(n2)) {
            if (typeof errorCallback === 'function') {
                errorCallback('Expected two valid numeric arguments.');
            }
            return;
        }
        exec(successCallback, errorCallback, 'Echo', 'add', [n1, n2]);
    },

    checkClerk: function (publishableKey, successCallback, errorCallback) {
        if (typeof publishableKey === 'function') {
            errorCallback = successCallback;
            successCallback = publishableKey;
            publishableKey = '';
        }
        var key = (typeof publishableKey === 'string') ? publishableKey : '';
        exec(successCallback, errorCallback, 'Echo', 'checkClerk', [key]);
    },

    initializeClerk: function (publishableKey, enableSharedSessionSync, successCallback, errorCallback) {
        if (typeof enableSharedSessionSync === 'function') {
            errorCallback = successCallback;
            successCallback = enableSharedSessionSync;
            enableSharedSessionSync = true;
        }
        if (typeof publishableKey !== 'string' || publishableKey.trim() === '') {
            if (typeof errorCallback === 'function') {
                errorCallback('Expected a non-empty publishableKey string argument.');
            }
            return;
        }
        var syncEnabled = (typeof enableSharedSessionSync === 'boolean') ? enableSharedSessionSync : true;
        exec(successCallback, errorCallback, 'Echo', 'initializeClerk', [publishableKey, syncEnabled]);
    },

    signInWithPassword: function (identifier, password, successCallback, errorCallback) {
        if (typeof identifier !== 'string' || identifier.trim() === '') {
            if (typeof errorCallback === 'function') {
                errorCallback('Expected a non-empty identifier string argument.');
            }
            return;
        }
        if (typeof password !== 'string' || password.trim() === '') {
            if (typeof errorCallback === 'function') {
                errorCallback('Expected a non-empty password string argument.');
            }
            return;
        }
        exec(successCallback, errorCallback, 'Echo', 'signInWithPassword', [identifier, password]);
    },

    signInWithMicrosoft: function (publishableKeyOrSuccess, successCallback, errorCallback) {
        var pk = "";
        var success = successCallback;
        var error = errorCallback;
        if (typeof publishableKeyOrSuccess === 'string') {
            pk = publishableKeyOrSuccess;
        } else if (typeof publishableKeyOrSuccess === 'function') {
            success = publishableKeyOrSuccess;
            error = successCallback;
        }
        exec(success, error, 'Echo', 'signInWithMicrosoft', [pk]);
    },

    signInWithEnterpriseSso: function (email, successCallback, errorCallback) {
        if (typeof email !== 'string' || email.trim() === '') {
            if (typeof errorCallback === 'function') {
                errorCallback('Expected a non-empty email string argument.');
            }
            return;
        }
        exec(successCallback, errorCallback, 'Echo', 'signInWithEnterpriseSso', [email.trim()]);
    },

    signUpWithEnterpriseSso: function (email, successCallback, errorCallback) {
        if (typeof email !== 'string' || email.trim() === '') {
            if (typeof errorCallback === 'function') {
                errorCallback('Expected a non-empty email string argument.');
            }
            return;
        }
        exec(successCallback, errorCallback, 'Echo', 'signUpWithEnterpriseSso', [email.trim()]);
    },

    startHostedAuth: function (redirectUrl, successCallback, errorCallback) {
        // redirectUrl is optional (e.g. "myapp://clerk-callback")
        // It must be registered as an Allowed Redirect URL in your Clerk Dashboard
        if (typeof redirectUrl === 'function') {
            errorCallback = successCallback;
            successCallback = redirectUrl;
            redirectUrl = '';
        }
        var url = (typeof redirectUrl === 'string') ? redirectUrl : '';
        exec(successCallback, errorCallback, 'Echo', 'startHostedAuth', [url]);
    },

    signOut: function (successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Echo', 'signOut', []);
    },

    getCurrentUser: function (successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Echo', 'getCurrentUser', []);
    },

    reloadFromSharedStorage: function (successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Echo', 'reloadFromSharedStorage', []);
    },

    testConnection: function (publishableKey, successCallback, errorCallback) {
        if (typeof publishableKey === 'function') {
            errorCallback = successCallback;
            successCallback = publishableKey;
            publishableKey = '';
        }
        var key = (typeof publishableKey === 'string') ? publishableKey : '';
        exec(successCallback, errorCallback, 'Echo', 'testConnection', [key]);
    },

    getKeychainAccessGroup: function (successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Echo', 'getKeychainAccessGroup', []);
    }
};

module.exports = Echo;
