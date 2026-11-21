package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.Optional;

/**
 * Holds the ScreenCaptureKit classes, selectors and constants screen capture uses.
 *
 * <p>ScreenCaptureKit exists from macOS 12.3; on older systems {@link #AVAILABLE} is
 * {@code false} and the other fields are {@code NULL}. This class loads the framework when it is
 * initialized, which only happens on macOS.
 */
@SuppressWarnings("restricted")
final class ScreenCaptureKit {
    /**
     * The framework, or empty before macOS 12.3.
     */
    private static final Optional<SymbolLookup> LIBRARY = Native.library(Frameworks.SCREEN_CAPTURE_KIT);

    /**
     * The {@code SCShareableContent} class.
     */
    static final MemorySegment SHAREABLE_CONTENT = objcClass("SCShareableContent");

    /**
     * The {@code SCContentFilter} class.
     */
    static final MemorySegment CONTENT_FILTER = objcClass("SCContentFilter");

    /**
     * The {@code SCStreamConfiguration} class.
     */
    static final MemorySegment STREAM_CONFIGURATION = objcClass("SCStreamConfiguration");

    /**
     * The {@code SCStream} class.
     */
    static final MemorySegment STREAM = objcClass("SCStream");

    /**
     * The {@code NSArray} class.
     */
    static final MemorySegment NS_ARRAY = objcClass("NSArray");

    /**
     * Whether ScreenCaptureKit can be used.
     */
    static final boolean AVAILABLE = LIBRARY.isPresent() && !ObjC.isNull(SHAREABLE_CONTENT)
            && !ObjC.isNull(CONTENT_FILTER) && !ObjC.isNull(STREAM_CONFIGURATION) && !ObjC.isNull(STREAM);

    /**
     * {@code SCStreamFrameInfoStatus}, the key of a frame's {@code SCFrameStatus} attachment.
     */
    static final MemorySegment FRAME_STATUS_KEY = LIBRARY.flatMap(library -> Frameworks.constant(library, "SCStreamFrameInfoStatus"))
            .orElse(MemorySegment.NULL);

    /**
     * {@code SCStreamErrorDomain}, the domain of ScreenCaptureKit errors.
     */
    static final MemorySegment ERROR_DOMAIN = LIBRARY.flatMap(library -> Frameworks.constant(library, "SCStreamErrorDomain"))
            .orElse(MemorySegment.NULL);

    /**
     * {@code SCStreamOutputTypeScreen}.
     */
    static final long OUTPUT_SCREEN = 0;

    /**
     * {@code SCFrameStatusComplete}: a new frame.
     */
    static final long FRAME_COMPLETE = 0;

    /**
     * {@code SCFrameStatusStarted}: the first frame after the stream started.
     */
    static final long FRAME_STARTED = 4;

    /**
     * {@code SCStreamErrorUserDeclined}: the user did not authorize capture.
     */
    static final long ERROR_USER_DECLINED = -3801;

    /**
     * {@code SCStreamErrorUserStopped}: the user stopped the stream.
     */
    static final long ERROR_USER_STOPPED = -3817;

    /**
     * {@code +getShareableContentWithCompletionHandler:}.
     */
    static final MemorySegment GET_SHAREABLE_CONTENT = ObjC.selector("getShareableContentWithCompletionHandler:");

    /**
     * {@code -displays} of {@code SCShareableContent}.
     */
    static final MemorySegment DISPLAYS = ObjC.selector("displays");

    /**
     * {@code -displayID} of {@code SCDisplay}.
     */
    static final MemorySegment DISPLAY_ID = ObjC.selector("displayID");

    /**
     * {@code -initWithDisplay:excludingWindows:}.
     */
    static final MemorySegment INIT_WITH_DISPLAY = ObjC.selector("initWithDisplay:excludingWindows:");

    /**
     * {@code +array} of {@code NSArray}.
     */
    static final MemorySegment ARRAY = ObjC.selector("array");

    /**
     * {@code -setWidth:}.
     */
    static final MemorySegment SET_WIDTH = ObjC.selector("setWidth:");

    /**
     * {@code -setHeight:}.
     */
    static final MemorySegment SET_HEIGHT = ObjC.selector("setHeight:");

    /**
     * {@code -setMinimumFrameInterval:}.
     */
    static final MemorySegment SET_MINIMUM_FRAME_INTERVAL = ObjC.selector("setMinimumFrameInterval:");

    /**
     * {@code -setPixelFormat:}.
     */
    static final MemorySegment SET_PIXEL_FORMAT = ObjC.selector("setPixelFormat:");

    /**
     * {@code -setShowsCursor:}.
     */
    static final MemorySegment SET_SHOWS_CURSOR = ObjC.selector("setShowsCursor:");

    /**
     * {@code -setColorMatrix:}.
     */
    static final MemorySegment SET_COLOR_MATRIX = ObjC.selector("setColorMatrix:");

    /**
     * {@code -setColorSpaceName:}.
     */
    static final MemorySegment SET_COLOR_SPACE_NAME = ObjC.selector("setColorSpaceName:");

    /**
     * {@code -initWithFilter:configuration:delegate:}.
     */
    static final MemorySegment INIT_WITH_FILTER = ObjC.selector("initWithFilter:configuration:delegate:");

    /**
     * {@code -addStreamOutput:type:sampleHandlerQueue:error:}.
     */
    static final MemorySegment ADD_STREAM_OUTPUT = ObjC.selector("addStreamOutput:type:sampleHandlerQueue:error:");

    /**
     * {@code -removeStreamOutput:type:error:}.
     */
    static final MemorySegment REMOVE_STREAM_OUTPUT = ObjC.selector("removeStreamOutput:type:error:");

    /**
     * {@code -startCaptureWithCompletionHandler:}.
     */
    static final MemorySegment START_CAPTURE = ObjC.selector("startCaptureWithCompletionHandler:");

    /**
     * {@code -stopCaptureWithCompletionHandler:}.
     */
    static final MemorySegment STOP_CAPTURE = ObjC.selector("stopCaptureWithCompletionHandler:");

    /**
     * {@code -integerValue} of {@code NSNumber}.
     */
    static final MemorySegment INTEGER_VALUE = ObjC.selector("integerValue");

    /**
     * The {@code SCShareableContent.windows} getter.
     */
    static final MemorySegment WINDOWS = ObjC.selector("windows");

    /**
     * The {@code SCWindow.windowID} getter.
     */
    static final MemorySegment WINDOW_ID = ObjC.selector("windowID");

    /**
     * The {@code SCWindow.windowLayer} getter.
     */
    static final MemorySegment WINDOW_LAYER = ObjC.selector("windowLayer");

    /**
     * The {@code SCWindow.onScreen} getter.
     */
    static final MemorySegment IS_ON_SCREEN = ObjC.selector("isOnScreen");

    /**
     * The {@code SCWindow.frame} getter.
     */
    static final MemorySegment FRAME = ObjC.selector("frame");

    /**
     * The {@code SCWindow.title} getter.
     */
    static final MemorySegment TITLE = ObjC.selector("title");

    /**
     * The {@code SCWindow.owningApplication} getter.
     */
    static final MemorySegment OWNING_APPLICATION = ObjC.selector("owningApplication");

    /**
     * The {@code SCRunningApplication.applicationName} getter.
     */
    static final MemorySegment APPLICATION_NAME = ObjC.selector("applicationName");

    /**
     * The {@code SCContentFilter} initializer of a single window.
     */
    static final MemorySegment INIT_WITH_WINDOW = ObjC.selector("initWithDesktopIndependentWindow:");

    /**
     * The {@code SCStreamConfiguration.sourceRect} setter.
     */
    static final MemorySegment SET_SOURCE_RECT = ObjC.selector("setSourceRect:");

    /**
     * Prevents instantiation.
     */
    private ScreenCaptureKit() {
        throw new AssertionError();
    }

    /**
     * Looks up a class once the framework is loaded.
     *
     * @param name the class name
     * @return the class, or {@code NULL} when the framework or the class is missing
     */
    private static MemorySegment objcClass(String name) {
        return LIBRARY.isPresent() ? ObjC.objcClass(name) : MemorySegment.NULL;
    }
}
