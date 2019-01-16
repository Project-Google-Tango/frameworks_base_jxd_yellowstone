
#ifndef _RSMOUSE_INCLUDE
#define _RSMOUSE_INCLUDE

#include <utils/List.h>

namespace android {

class PointerControllerInterface;
class CursorInputMapper;
class InputDevice;
struct InputDeviceIdentifier;

namespace InputFilter {

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

// Response
typedef enum {
    EVENT_DEFAULT   = 0,
    EVENT_PROCESS   = 1,
    EVENT_SKIP      = 2,
    EVENT_ADD       = 3,
} RESPONSE;

// Controller Details
typedef enum {
    BUTTON_A = 0,
    BUTTON_B,
    BUTTON_X,
    BUTTON_Y,
    BUTTON_L1,
    BUTTON_R1,
    BUTTON_L2,
    BUTTON_R2,
    BUTTON_START,
    BUTTON_SELECT,
    BUTTON_THUMBL,
    BUTTON_THUMBR,
    BUTTON_HOME,
    BUTTON_BACK,
    BUTTON_MUTE,
    BUTTON_DPAD_LEFT,
    BUTTON_DPAD_RIGHT,
    BUTTON_DPAD_UP,
    BUTTON_DPAD_DOWN,
    BUTTON_LTRIGGER,
    BUTTON_RTRIGGER,
    BUTTON_MAX,
    BUTTON_ARRAY_MAX = BUTTON_MAX,
} CONTROLLER_BUTTON;

typedef enum {
    AXIS_LS_X = 0,
    AXIS_LS_Y,
    AXIS_RS_X,
    AXIS_RS_Y,
    AXIS_LTRIGGER,
    AXIS_RTRIGGER,
    AXIS_DPAD_X,
    AXIS_DPAD_Y,
    AXIS_MAX,
    AXIS_ARRAY_MAX = AXIS_MAX*2 // cope with up to 2 directions per axis
} CONTROLLER_AXIS;

// Basic Declarations for a profile
typedef enum {
    DEFAULT = 0,
    UNSUPPORTED,
    VIRTUALMOUSE,
    BUTTONBOARD,
    NVCONTROLLER,
    CONTROLLER,
} Device;

typedef struct {
    CONTROLLER_BUTTON button;
    Device device;
    int32_t mouseCode;
    bool passThru;
    bool hideMouse;
} GAMEPAD_BUTTON_MAP;

typedef enum {
    ANY = 0,
    NEGATIVE = 1,
    POSITIVE = 2,
} Polarity;

// Method Declarations
typedef enum {
    IGNORE = 0,
    BUTTON,
    ROCKER,
    REL_AXIS_X,
    REL_AXIS_Y,
} Method;

typedef struct {
    CONTROLLER_AXIS axis;
    Polarity polarity;
    float triggerLevel;
    bool triggerLow;
    Method method;
    bool passThru;
    bool hideMouse;
    Device device;
    int btnType;
    int btnCode;
} GAMEPAD_AXIS_MAP;

typedef struct {
    const GAMEPAD_BUTTON_MAP* buttonMap;
    const GAMEPAD_AXIS_MAP* axisMap;
} PROFILE;

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class RSMouse {
public:
    // Filters
    static void filterNewDevice(int fd, int32_t id, const String8& path, const InputDeviceIdentifier& identifier);
    static void filterCloseDevice(int32_t id);
    static RESPONSE filterEvent(struct input_event& event, int32_t& deviceId);

    // Notifiers
    static bool notifyKeyState(int32_t deviceId, int32_t keyCode, bool handled);
    static bool notifyMotionState(int32_t deviceId, PointerCoords* pc, bool handled);
    static void notifyCursorPointerFade();

    // Set methods
    static void setVolumeModeState(bool enable);
    static void setVirtualMouseState(bool enable);

    // RS-Mouse specific logic
    static int getMouseFd();
    static void registerVirtualMouseDevice(int32_t id);
    static void registerPointerController(sp<PointerControllerInterface> pointerController);
    static void registerCursorInputMapper(CursorInputMapper* mapper, InputDevice* device);
    static void setVirtualMouseBitmasks(uint8_t* keyBitmask, uint8_t* relBitmask);
};

};

};

#endif // _RSMOUSE_INCLUDE

