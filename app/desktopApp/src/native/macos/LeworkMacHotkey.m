#import <Carbon/Carbon.h>
#import <Cocoa/Cocoa.h>
#import <dispatch/dispatch.h>
#import <jni.h>
#import <stdint.h>

static JavaVM *g_jvm = NULL;
static jclass g_bridgeClass = NULL;
static jmethodID g_onPressedMethod = NULL;
static EventHotKeyRef g_hotKeyRef = NULL;
static EventHandlerRef g_eventHandlerRef = NULL;

static const OSType kLeworkHotKeySignature = 'LWRK';
static const UInt32 kLeworkToggleHotKeyId = 1;

static void runOnMainThreadSync(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

static OSStatus leworkHotKeyHandler(
    EventHandlerCallRef nextHandler,
    EventRef theEvent,
    void *userData
) {
    (void)nextHandler;
    (void)userData;

    EventHotKeyID hotKeyId;
    OSStatus status = GetEventParameter(
        theEvent,
        kEventParamDirectObject,
        typeEventHotKeyID,
        NULL,
        sizeof(EventHotKeyID),
        NULL,
        &hotKeyId
    );
    if (status != noErr) {
        return status;
    }
    if (hotKeyId.signature != kLeworkHotKeySignature || hotKeyId.id != kLeworkToggleHotKeyId) {
        return eventNotHandledErr;
    }

    if (g_jvm == NULL || g_bridgeClass == NULL || g_onPressedMethod == NULL) {
        return noErr;
    }

    BOOL isActive = [NSApp isActive];
    BOOL isHidden = [NSApp isHidden];

    JNIEnv *env = NULL;
    BOOL didAttach = NO;
    jint envState = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    if (envState == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) == JNI_OK) {
            didAttach = YES;
        }
    }
    if (env != NULL) {
        (*env)->CallStaticVoidMethod(
            env,
            g_bridgeClass,
            g_onPressedMethod,
            isActive ? JNI_TRUE : JNI_FALSE,
            isHidden ? JNI_TRUE : JNI_FALSE
        );
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }
    if (didAttach) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
    return noErr;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

JNIEXPORT jint JNICALL Java_dev_lework_desktop_MacGlobalHotkeyBridge_nativeRegisterToggleHotKey(
    JNIEnv *env,
    jclass clazz,
    jint keyCode,
    jint modifiers
) {
    jmethodID callback = (*env)->GetStaticMethodID(env, clazz, "onNativeHotKeyPressed", "(ZZ)V");
    if (callback == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        return -1;
    }

    jclass globalClazz = (jclass)(*env)->NewGlobalRef(env, clazz);
    if (globalClazz == NULL) {
        return -2;
    }

    __block OSStatus result = noErr;
    __block jclass staleClass = NULL;
    runOnMainThreadSync(^{
        if (g_hotKeyRef != NULL) {
            UnregisterEventHotKey(g_hotKeyRef);
            g_hotKeyRef = NULL;
        }
        if (g_eventHandlerRef != NULL) {
            RemoveEventHandler(g_eventHandlerRef);
            g_eventHandlerRef = NULL;
        }
        if (g_bridgeClass != NULL) {
            staleClass = g_bridgeClass;
        }
        g_bridgeClass = globalClazz;
        g_onPressedMethod = callback;

        EventTypeSpec eventType;
        eventType.eventClass = kEventClassKeyboard;
        eventType.eventKind = kEventHotKeyPressed;

        OSStatus handlerStatus = InstallEventHandler(
            GetApplicationEventTarget(),
            leworkHotKeyHandler,
            1,
            &eventType,
            NULL,
            &g_eventHandlerRef
        );
        if (handlerStatus != noErr) {
            result = handlerStatus;
            return;
        }

        EventHotKeyID hotKeyId;
        hotKeyId.signature = kLeworkHotKeySignature;
        hotKeyId.id = kLeworkToggleHotKeyId;

        result = RegisterEventHotKey(
            (UInt32)keyCode,
            (UInt32)modifiers,
            hotKeyId,
            GetApplicationEventTarget(),
            0,
            &g_hotKeyRef
        );
        if (result != noErr && g_eventHandlerRef != NULL) {
            RemoveEventHandler(g_eventHandlerRef);
            g_eventHandlerRef = NULL;
        }
    });

    if (staleClass != NULL) {
        (*env)->DeleteGlobalRef(env, staleClass);
    }
    if (result != noErr) {
        __block jclass failedClass = NULL;
        runOnMainThreadSync(^{
            if (g_bridgeClass == globalClazz) {
                failedClass = g_bridgeClass;
                g_bridgeClass = NULL;
                g_onPressedMethod = NULL;
            }
        });
        if (failedClass != NULL) {
            (*env)->DeleteGlobalRef(env, failedClass);
        }
    }
    return (jint)result;
}

JNIEXPORT void JNICALL Java_dev_lework_desktop_MacGlobalHotkeyBridge_nativeUnregisterToggleHotKey(
    JNIEnv *env,
    jclass clazz
) {
    (void)clazz;
    __block jclass oldClass = NULL;
    runOnMainThreadSync(^{
        if (g_hotKeyRef != NULL) {
            UnregisterEventHotKey(g_hotKeyRef);
            g_hotKeyRef = NULL;
        }
        if (g_eventHandlerRef != NULL) {
            RemoveEventHandler(g_eventHandlerRef);
            g_eventHandlerRef = NULL;
        }
        oldClass = g_bridgeClass;
        g_bridgeClass = NULL;
        g_onPressedMethod = NULL;
    });
    if (oldClass != NULL) {
        (*env)->DeleteGlobalRef(env, oldClass);
    }
}

JNIEXPORT void JNICALL Java_dev_lework_desktop_MacGlobalHotkeyBridge_nativeHideApp(
    JNIEnv *env,
    jclass clazz
) {
    (void)env;
    (void)clazz;
    dispatch_async(dispatch_get_main_queue(), ^{
        [NSApp hide:nil];
    });
}

JNIEXPORT void JNICALL Java_dev_lework_desktop_MacGlobalHotkeyBridge_nativeActivateApp(
    JNIEnv *env,
    jclass clazz
) {
    (void)env;
    (void)clazz;
    dispatch_async(dispatch_get_main_queue(), ^{
        [NSApp unhideWithoutActivation];
        for (NSWindow *win in [NSApp windows]) {
            if ([win isMiniaturized]) {
                [win deminiaturize:nil];
            }
        }
        [NSApp activateIgnoringOtherApps:YES];
        for (NSWindow *win in [NSApp windows]) {
            if ([win isVisible] && [win canBecomeKeyWindow]) {
                [win makeKeyAndOrderFront:nil];
                break;
            }
        }
    });
}
