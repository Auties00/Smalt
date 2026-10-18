package com.github.auties00.smalt.capture.internal.macos;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the AVFoundation classes, selectors and constants camera capture uses.
 *
 * <p>Loading the framework registers its Objective-C classes with the runtime. This class loads it
 * when it is initialized, which only happens on macOS.
 */
@SuppressWarnings("restricted")
final class AvFoundation {
    /**
     * The framework.
     */
    private static final SymbolLookup LIBRARY = Frameworks.load(Frameworks.AV_FOUNDATION);

    /**
     * {@code AVMediaTypeVideo}, the {@code NSString} {@code "vide"}.
     */
    static final MemorySegment MEDIA_TYPE_VIDEO = Frameworks.requiredConstant(LIBRARY, "AVMediaTypeVideo");

    /**
     * {@code AVAuthorizationStatusNotDetermined}.
     */
    static final long AUTHORIZATION_NOT_DETERMINED = 0;

    /**
     * {@code AVAuthorizationStatusRestricted}.
     */
    static final long AUTHORIZATION_RESTRICTED = 1;

    /**
     * {@code AVAuthorizationStatusDenied}.
     */
    static final long AUTHORIZATION_DENIED = 2;

    /**
     * {@code AVCaptureDevicePositionUnspecified}.
     */
    static final long POSITION_UNSPECIFIED = 0;

    /**
     * The {@code AVCaptureDevice} class.
     */
    static final MemorySegment CAPTURE_DEVICE = ObjC.objcClass("AVCaptureDevice");

    /**
     * The {@code AVCaptureDeviceDiscoverySession} class (macOS 10.15), or {@code NULL}.
     */
    static final MemorySegment DISCOVERY_SESSION = ObjC.objcClass("AVCaptureDeviceDiscoverySession");

    /**
     * The {@code AVCaptureSession} class.
     */
    static final MemorySegment CAPTURE_SESSION = ObjC.objcClass("AVCaptureSession");

    /**
     * The {@code AVCaptureDeviceInput} class.
     */
    static final MemorySegment DEVICE_INPUT = ObjC.objcClass("AVCaptureDeviceInput");

    /**
     * The {@code AVCaptureVideoDataOutput} class.
     */
    static final MemorySegment VIDEO_DATA_OUTPUT = ObjC.objcClass("AVCaptureVideoDataOutput");

    /**
     * The {@code NSArray} class.
     */
    static final MemorySegment NS_ARRAY = ObjC.objcClass("NSArray");

    /**
     * The {@code NSDictionary} class.
     */
    static final MemorySegment NS_DICTIONARY = ObjC.objcClass("NSDictionary");

    /**
     * The {@code NSNumber} class.
     */
    static final MemorySegment NS_NUMBER = ObjC.objcClass("NSNumber");

    /**
     * The {@code NSBundle} class.
     */
    static final MemorySegment NS_BUNDLE = ObjC.objcClass("NSBundle");

    /**
     * {@code +authorizationStatusForMediaType:}.
     */
    static final MemorySegment AUTHORIZATION_STATUS = ObjC.selector("authorizationStatusForMediaType:");

    /**
     * {@code +requestAccessForMediaType:completionHandler:}.
     */
    static final MemorySegment REQUEST_ACCESS = ObjC.selector("requestAccessForMediaType:completionHandler:");

    /**
     * {@code +discoverySessionWithDeviceTypes:mediaType:position:}.
     */
    static final MemorySegment DISCOVERY_WITH_TYPES = ObjC.selector("discoverySessionWithDeviceTypes:mediaType:position:");

    /**
     * {@code -devices} of the discovery session.
     */
    static final MemorySegment DEVICES = ObjC.selector("devices");

    /**
     * {@code +devicesWithMediaType:}, deprecated in macOS 10.15.
     */
    static final MemorySegment DEVICES_WITH_MEDIA_TYPE = ObjC.selector("devicesWithMediaType:");

    /**
     * {@code +defaultDeviceWithMediaType:}.
     */
    static final MemorySegment DEFAULT_DEVICE = ObjC.selector("defaultDeviceWithMediaType:");

    /**
     * {@code +deviceWithUniqueID:}.
     */
    static final MemorySegment DEVICE_WITH_UNIQUE_ID = ObjC.selector("deviceWithUniqueID:");

    /**
     * {@code -uniqueID}.
     */
    static final MemorySegment UNIQUE_ID = ObjC.selector("uniqueID");

    /**
     * {@code -localizedName}.
     */
    static final MemorySegment LOCALIZED_NAME = ObjC.selector("localizedName");

    /**
     * {@code -formats}.
     */
    static final MemorySegment FORMATS = ObjC.selector("formats");

    /**
     * {@code -activeFormat}.
     */
    static final MemorySegment ACTIVE_FORMAT = ObjC.selector("activeFormat");

    /**
     * {@code -setActiveFormat:}.
     */
    static final MemorySegment SET_ACTIVE_FORMAT = ObjC.selector("setActiveFormat:");

    /**
     * {@code -activeVideoMinFrameDuration}.
     */
    static final MemorySegment ACTIVE_MIN_FRAME_DURATION = ObjC.selector("activeVideoMinFrameDuration");

    /**
     * {@code -setActiveVideoMinFrameDuration:}.
     */
    static final MemorySegment SET_ACTIVE_MIN_FRAME_DURATION = ObjC.selector("setActiveVideoMinFrameDuration:");

    /**
     * {@code -activeVideoMaxFrameDuration}.
     */
    static final MemorySegment ACTIVE_MAX_FRAME_DURATION = ObjC.selector("activeVideoMaxFrameDuration");

    /**
     * {@code -setActiveVideoMaxFrameDuration:}.
     */
    static final MemorySegment SET_ACTIVE_MAX_FRAME_DURATION = ObjC.selector("setActiveVideoMaxFrameDuration:");

    /**
     * {@code -lockForConfiguration:}.
     */
    static final MemorySegment LOCK_FOR_CONFIGURATION = ObjC.selector("lockForConfiguration:");

    /**
     * {@code -unlockForConfiguration}.
     */
    static final MemorySegment UNLOCK_FOR_CONFIGURATION = ObjC.selector("unlockForConfiguration");

    /**
     * {@code -formatDescription} of {@code AVCaptureDeviceFormat}.
     */
    static final MemorySegment FORMAT_DESCRIPTION = ObjC.selector("formatDescription");

    /**
     * {@code -videoSupportedFrameRateRanges}.
     */
    static final MemorySegment FRAME_RATE_RANGES = ObjC.selector("videoSupportedFrameRateRanges");

    /**
     * {@code -minFrameRate} of {@code AVFrameRateRange}.
     */
    static final MemorySegment MIN_FRAME_RATE = ObjC.selector("minFrameRate");

    /**
     * {@code -maxFrameRate} of {@code AVFrameRateRange}.
     */
    static final MemorySegment MAX_FRAME_RATE = ObjC.selector("maxFrameRate");

    /**
     * {@code -minFrameDuration} of {@code AVFrameRateRange}.
     */
    static final MemorySegment MIN_FRAME_DURATION = ObjC.selector("minFrameDuration");

    /**
     * {@code -maxFrameDuration} of {@code AVFrameRateRange}.
     */
    static final MemorySegment MAX_FRAME_DURATION = ObjC.selector("maxFrameDuration");

    /**
     * {@code +deviceInputWithDevice:error:}.
     */
    static final MemorySegment INPUT_WITH_DEVICE = ObjC.selector("deviceInputWithDevice:error:");

    /**
     * {@code -beginConfiguration}.
     */
    static final MemorySegment BEGIN_CONFIGURATION = ObjC.selector("beginConfiguration");

    /**
     * {@code -commitConfiguration}.
     */
    static final MemorySegment COMMIT_CONFIGURATION = ObjC.selector("commitConfiguration");

    /**
     * {@code -canAddInput:}.
     */
    static final MemorySegment CAN_ADD_INPUT = ObjC.selector("canAddInput:");

    /**
     * {@code -addInput:}.
     */
    static final MemorySegment ADD_INPUT = ObjC.selector("addInput:");

    /**
     * {@code -canAddOutput:}.
     */
    static final MemorySegment CAN_ADD_OUTPUT = ObjC.selector("canAddOutput:");

    /**
     * {@code -addOutput:}.
     */
    static final MemorySegment ADD_OUTPUT = ObjC.selector("addOutput:");

    /**
     * {@code -startRunning}.
     */
    static final MemorySegment START_RUNNING = ObjC.selector("startRunning");

    /**
     * {@code -stopRunning}.
     */
    static final MemorySegment STOP_RUNNING = ObjC.selector("stopRunning");

    /**
     * {@code -isRunning}, the getter of {@code running}.
     */
    static final MemorySegment IS_RUNNING = ObjC.selector("isRunning");

    /**
     * {@code -availableVideoCVPixelFormatTypes}.
     */
    static final MemorySegment AVAILABLE_PIXEL_FORMATS = ObjC.selector("availableVideoCVPixelFormatTypes");

    /**
     * {@code -setVideoSettings:}.
     */
    static final MemorySegment SET_VIDEO_SETTINGS = ObjC.selector("setVideoSettings:");

    /**
     * {@code -setAlwaysDiscardsLateVideoFrames:}.
     */
    static final MemorySegment SET_DISCARDS_LATE_FRAMES = ObjC.selector("setAlwaysDiscardsLateVideoFrames:");

    /**
     * {@code -setSampleBufferDelegate:queue:}.
     */
    static final MemorySegment SET_SAMPLE_BUFFER_DELEGATE = ObjC.selector("setSampleBufferDelegate:queue:");

    /**
     * {@code +numberWithUnsignedInt:}.
     */
    static final MemorySegment NUMBER_WITH_UNSIGNED_INT = ObjC.selector("numberWithUnsignedInt:");

    /**
     * {@code -unsignedIntValue}.
     */
    static final MemorySegment UNSIGNED_INT_VALUE = ObjC.selector("unsignedIntValue");

    /**
     * {@code +dictionaryWithObjects:forKeys:count:}.
     */
    static final MemorySegment DICTIONARY_WITH_OBJECTS = ObjC.selector("dictionaryWithObjects:forKeys:count:");

    /**
     * {@code +arrayWithObjects:count:}.
     */
    static final MemorySegment ARRAY_WITH_OBJECTS = ObjC.selector("arrayWithObjects:count:");

    /**
     * {@code +mainBundle}.
     */
    static final MemorySegment MAIN_BUNDLE = ObjC.selector("mainBundle");

    /**
     * {@code -objectForInfoDictionaryKey:}.
     */
    static final MemorySegment INFO_VALUE = ObjC.selector("objectForInfoDictionaryKey:");

    /**
     * {@code -boolValue}.
     */
    static final MemorySegment BOOL_VALUE = ObjC.selector("boolValue");

    /**
     * Prevents instantiation.
     */
    private AvFoundation() {
        throw new AssertionError();
    }

    /**
     * Returns the device types a discovery session should search, in preference order.
     *
     * <p>Built-in wide-angle cameras come first; then external cameras
     * ({@code AVCaptureDeviceTypeExternal} from macOS 14, or its deprecated synonym
     * {@code AVCaptureDeviceTypeExternalUnknown} before); then the Desk View camera (macOS 13).
     * Continuity Camera devices are listed as built-in wide-angle cameras unless the application's
     * {@code Info.plist} opts in with {@code NSCameraUseContinuityCameraDeviceType}, in which case
     * {@code AVCaptureDeviceTypeContinuityCamera} (macOS 14) is searched too. Types this system does
     * not export are skipped, as passing an unknown type raises an Objective-C exception.
     *
     * @param continuityOptIn whether the application opted in to the Continuity Camera type
     * @return the device type constants
     */
    static List<MemorySegment> deviceTypes(boolean continuityOptIn) {
        var types = new ArrayList<MemorySegment>();
        Frameworks.constant(LIBRARY, "AVCaptureDeviceTypeBuiltInWideAngleCamera").ifPresent(types::add);
        Frameworks.constant(LIBRARY, "AVCaptureDeviceTypeExternal")
                .or(() -> Frameworks.constant(LIBRARY, "AVCaptureDeviceTypeExternalUnknown"))
                .ifPresent(types::add);
        if (continuityOptIn) {
            Frameworks.constant(LIBRARY, "AVCaptureDeviceTypeContinuityCamera").ifPresent(types::add);
        }
        Frameworks.constant(LIBRARY, "AVCaptureDeviceTypeDeskViewCamera").ifPresent(types::add);
        types.removeIf(ObjC::isNull);
        return types;
    }
}
