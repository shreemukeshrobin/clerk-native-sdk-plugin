#import "AppDelegate+ClerkUniversalLinks.h"

@implementation AppDelegate (ClerkUniversalLinks)

- (BOOL)application:(UIApplication *)application continueUserActivity:(NSUserActivity *)userActivity restorationHandler:(void (^)(NSArray * _Nullable))restorationHandler {
    if ([userActivity.activityType isEqualToString:NSUserActivityTypeBrowsingWeb] && userActivity.webpageURL != nil) {
        [[NSNotificationCenter defaultCenter] postNotificationName:@"ClerkHostedAuthUniversalLinkNotification" object:userActivity.webpageURL];
        return YES;
    }
    return NO;
}

@end
