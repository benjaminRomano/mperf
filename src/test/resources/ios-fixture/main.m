#import <UIKit/UIKit.h>
#import <os/signpost.h>

@interface MperfAppDelegate : UIResponder <UIApplicationDelegate>
@property(nonatomic, strong) UIWindow *window;
@end

@implementation MperfAppDelegate
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    self.window = [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
    UIViewController *controller = [[UIViewController alloc] init];
    controller.view.backgroundColor = UIColor.systemBlueColor;
    self.window.rootViewController = controller;
    [self.window makeKeyAndVisible];

    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        os_log_t log = os_log_create("com.bromano.mperf.integration.fixture", OS_LOG_CATEGORY_POINTS_OF_INTEREST);
        volatile uint64_t checksum = 0;
        while (true) {
            os_signpost_id_t signpost = os_signpost_id_generate(log);
            os_signpost_interval_begin(log, signpost, "mperf.fixture.workload");
            for (uint64_t pass = 0; pass < 10000000; pass++) {
                checksum = (checksum * 33) ^ pass;
            }
            os_signpost_event_emit(log, signpost, "mperf.fixture.checkpoint", "checksum=%llu", checksum);
            os_signpost_interval_end(log, signpost, "mperf.fixture.workload");
        }
    });
    return YES;
}
@end

int main(int argc, char *argv[]) {
    @autoreleasepool {
        return UIApplicationMain(argc, argv, nil, NSStringFromClass(MperfAppDelegate.class));
    }
}
