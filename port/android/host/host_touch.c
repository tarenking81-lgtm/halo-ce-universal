/*
HOST_TOUCH.C

The on-screen controls (TouchControls.java). The overlay draws the sticks and
buttons and sends their state here; host_sdl.c then lists a "touch gamepad"
among the SDL gamepads that the guest sees, so the game reads it as an Xbox
controller (port/linux/src/xinput_sdl.c) without any change to the game.

The overlay hides itself and turns the touch gamepad off while a physical
controller is connected, so that controller stays player 1.

The UI thread writes the state and the game's threads read it: each value is
a single atomic word.
*/

#include "host.h"

#include <SDL3/SDL.h>
#include <jni.h>
#include <stdatomic.h>

static atomic_int touch_enabled;
static atomic_int touch_axes[SDL_GAMEPAD_AXIS_COUNT];
static atomic_uint touch_buttons; /* bit n: SDL_GamepadButton n */

int host_touch_enabled(void)
{
	return atomic_load(&touch_enabled);
}

int host_touch_axis(int axis)
{
	if (axis < 0 || axis >= SDL_GAMEPAD_AXIS_COUNT || !atomic_load(&touch_enabled))
		return 0;
	return atomic_load(&touch_axes[axis]);
}

int host_touch_button(int button)
{
	if (button < 0 || button >= 32 || !atomic_load(&touch_enabled))
		return 0;
	return (atomic_load(&touch_buttons) >> button) & 1;
}

static int clamp_axis(jint value, int minimum)
{
	if (value < minimum)
		return minimum;
	if (value > 32767)
		return 32767;
	return value;
}

JNIEXPORT void JNICALL Java_com_halo_decomp_TouchControls_nativeSetEnabled(JNIEnv *env, jclass cls, jboolean enabled)
{
	(void)env;
	(void)cls;
	if (!enabled)
	{
		int axis;

		for (axis = 0; axis < SDL_GAMEPAD_AXIS_COUNT; axis++)
			atomic_store(&touch_axes[axis], 0);
		atomic_store(&touch_buttons, 0);
	}
	if (atomic_exchange(&touch_enabled, enabled ? 1 : 0) != (enabled ? 1 : 0))
		host_logf(HOST_LOG_INFO, "touch controls %s", enabled ? "on" : "off");
}

/* the sticks in SDL's sense (y down is positive), the triggers 0..32767 */
JNIEXPORT void JNICALL Java_com_halo_decomp_TouchControls_nativeSetState(JNIEnv *env, jclass cls,
	jint left_x, jint left_y, jint right_x, jint right_y, jint left_trigger, jint right_trigger, jint buttons)
{
	(void)env;
	(void)cls;
	atomic_store(&touch_axes[SDL_GAMEPAD_AXIS_LEFTX], clamp_axis(left_x, -32768));
	atomic_store(&touch_axes[SDL_GAMEPAD_AXIS_LEFTY], clamp_axis(left_y, -32768));
	atomic_store(&touch_axes[SDL_GAMEPAD_AXIS_RIGHTX], clamp_axis(right_x, -32768));
	atomic_store(&touch_axes[SDL_GAMEPAD_AXIS_RIGHTY], clamp_axis(right_y, -32768));
	atomic_store(&touch_axes[SDL_GAMEPAD_AXIS_LEFT_TRIGGER], clamp_axis(left_trigger, 0));
	atomic_store(&touch_axes[SDL_GAMEPAD_AXIS_RIGHT_TRIGGER], clamp_axis(right_trigger, 0));
	atomic_store(&touch_buttons, (unsigned)buttons);
}

/* Looking: a swipe on the right half of the screen turns the view as a mouse
does, by as much as the finger moves and at once, rather than as the right
stick, whose turn rate ramps up. The motion goes to the game's mouse look
(port/linux/src/xinput_sdl.c) as SDL mouse motion, in mouse pixels. */
JNIEXPORT void JNICALL Java_com_halo_decomp_TouchControls_nativeLook(JNIEnv *env, jclass cls, jfloat dx, jfloat dy)
{
	SDL_Event event;

	(void)env;
	(void)cls;
	if (!atomic_load(&touch_enabled) || !SDL_WasInit(SDL_INIT_EVENTS) || (dx == 0.0f && dy == 0.0f))
		return;
	SDL_zero(event);
	event.type = SDL_EVENT_MOUSE_MOTION;
	event.motion.timestamp = SDL_GetTicksNS();
	event.motion.xrel = dx;
	event.motion.yrel = dy;
	SDL_PushEvent(&event);
}
