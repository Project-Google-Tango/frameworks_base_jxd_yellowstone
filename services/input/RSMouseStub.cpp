// This file serves only to offer stub services, and contains no valuable logic

#ifndef SHIELDTECH_RSMOUSE

#include "EventHub.h"

#include <cutils/properties.h>
#include <utils/Log.h>
#include <utils/Timers.h>
#include <utils/threads.h>
#include <utils/Errors.h>

#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <memory.h>
#include <errno.h>
#include <assert.h>

#include <input/KeyLayoutMap.h>
#include <input/KeyCharacterMap.h>
#include <input/VirtualKeyMap.h>

#include <string.h>
#include <stdint.h>
#include <dirent.h>

#include <sys/inotify.h>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <sys/limits.h>
#include <sys/sha1.h>
#include "RSMouse.h"

namespace android {

namespace InputFilter {

void RSMouse::filterNewDevice(int fd, int32_t id, const String8& path, const InputDeviceIdentifier& identifier) {}
void RSMouse::filterCloseDevice(int32_t id)  {}
RESPONSE RSMouse::filterEvent(struct input_event& event, int32_t& deviceId) { return EVENT_DEFAULT; }

bool RSMouse::notifyKeyState(int32_t deviceId, int32_t keyCode, bool handled) { return false; }
bool RSMouse::notifyMotionState(int32_t deviceId, PointerCoords* pc, bool handled) { return false; }
void RSMouse::notifyCursorPointerFade() {}

// Set methods
void RSMouse::setVolumeModeState(bool enable) {}
void RSMouse::setVirtualMouseState(bool enable) {}

// RS-Mouse specific logic
int RSMouse::getMouseFd()    { return -1; }
void RSMouse::registerVirtualMouseDevice(int32_t id) {}
void RSMouse::registerPointerController(sp<PointerControllerInterface> pointerController) {}
void RSMouse::registerCursorInputMapper(CursorInputMapper* mapper, InputDevice* device) {}
void RSMouse::setVirtualMouseBitmasks(uint8_t* keyBitmask, uint8_t* relBitmask) {}

};

};

#endif // SHIELDTECH_RSMOUSE

