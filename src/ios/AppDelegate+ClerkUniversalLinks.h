#import <UIKit/UIKit.h>

@class AppDelegate;

// Cordova's stock AppDelegate does not forward Universal Links (NSUserActivity /
// continueUserActivity) to plugins the way it forwards custom URL scheme opens.
// This category adds that, posting ClerkHostedAuthUniversalLinkNotification with
// the resolved webpage URL so EchoPlugin can pick up the startHostedAuth callback.
@interface AppDelegate (ClerkUniversalLinks)

- (BOOL)application:(UIApplication *)application continueUserActivity:(NSUserActivity *)userActivity restorationHandler:(void (^)(NSArray * _Nullable))restorationHandler;

@end
