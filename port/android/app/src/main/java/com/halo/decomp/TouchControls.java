package com.halo.decomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

/**
 * On-screen controls: two thumbsticks and the Xbox buttons drawn over the
 * game. Their state goes to the host library (port/android/host/host_touch.c),
 * which shows the game one more controller, so the game reads them as an
 * Xbox pad.
 *
 * Left half of the screen: a floating move stick (it centres where the
 * finger lands). Right half, outside the buttons: drag to look. The fire
 * button also looks while the finger slides on it, so you can shoot and aim
 * with one thumb.
 *
 * The overlay goes away while a physical controller is connected (the
 * controller is then player 1) and comes back when it disconnects. The small
 * button at the top centre hides or shows the buttons.
 */
public class TouchControls extends View implements InputManager.InputDeviceListener {
    static native void nativeSetEnabled(boolean enabled);
    static native void nativeSetState(int leftX, int leftY, int rightX, int rightY,
                                      int leftTrigger, int rightTrigger, int buttons);

    // SDL_GamepadButton
    private static final int SOUTH = 0, EAST = 1, WEST = 2, NORTH = 3, BACK = 4, START = 6,
            LEFT_STICK = 7, RIGHT_STICK = 8, LEFT_SHOULDER = 9, RIGHT_SHOULDER = 10,
            DPAD_UP = 11, DPAD_DOWN = 12, DPAD_LEFT = 13, DPAD_RIGHT = 14;
    // the triggers are axes; these stand for them in Button.code
    private static final int LEFT_TRIGGER = -1, RIGHT_TRIGGER = -2, TOGGLE = -3;

    /** a round button, placed in dp from a corner of the screen */
    private static final class Button {
        final String label;
        final int code;
        final boolean fromRight, fromBottom;
        final float dx, dy, radius; // dp
        float cx, cy, r;            // px, after layout
        int pointers;               // fingers on it

        Button(String label, int code, boolean fromRight, boolean fromBottom, float dx, float dy, float radius) {
            this.label = label;
            this.code = code;
            this.fromRight = fromRight;
            this.fromBottom = fromBottom;
            this.dx = dx;
            this.dy = dy;
            this.radius = radius;
        }

        boolean contains(float x, float y, float slop) {
            float ex = x - cx, ey = y - cy, reach = r + slop;
            return ex * ex + ey * ey <= reach * reach;
        }
    }

    private final Button[] buttons = {
        // right thumb
        new Button("FIRE", RIGHT_TRIGGER, true, true, 95, 105, 50),
        new Button("A", SOUTH, true, true, 205, 55, 34),
        new Button("B", EAST, true, true, 40, 215, 30),
        new Button("X", WEST, true, true, 175, 170, 30),
        new Button("Y", NORTH, true, true, 110, 235, 30),
        new Button("GREN", LEFT_TRIGGER, true, true, 265, 135, 30),
        new Button("ZOOM", RIGHT_STICK, true, true, 40, 300, 26),
        new Button("G⇄", RIGHT_SHOULDER, true, true, 205, 270, 24),
        // left thumb
        new Button("CROUCH", LEFT_STICK, false, true, 55, 225, 30),
        new Button("LIGHT", LEFT_SHOULDER, false, true, 55, 300, 24),
        // top row
        new Button("BACK", BACK, false, false, 45, 35, 22),
        new Button("↑", DPAD_UP, false, false, 130, 30, 18),
        new Button("←", DPAD_LEFT, false, false, 95, 70, 18),
        new Button("→", DPAD_RIGHT, false, false, 165, 70, 18),
        new Button("↓", DPAD_DOWN, false, false, 130, 105, 18),
        new Button("START", START, true, false, 45, 35, 22),
    };
    private final Button toggle = new Button("◐", TOGGLE, false, false, 0, 22, 18);

    private static final int ROLE_MOVE = 1, ROLE_LOOK = 2, ROLE_BUTTON = 3;

    /** what one finger is doing */
    private static final class Finger {
        int role;
        Button button;
        float originX, originY, x, y;
    }

    private final SparseArray<Finger> fingers = new SparseArray<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final InputManager inputManager;

    private boolean nativeReady = true;
    private boolean controllerConnected;
    private boolean buttonsHidden;
    private Finger moveFinger, lookFinger;

    public TouchControls(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * density);
        text.setColor(Color.argb(200, 255, 255, 255));
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        setHapticFeedbackEnabled(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (inputManager != null)
            inputManager.registerInputDeviceListener(this, new Handler(Looper.getMainLooper()));
        refreshControllers();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (inputManager != null)
            inputManager.unregisterInputDeviceListener(this);
        super.onDetachedFromWindow();
    }

    // ---------- physical controllers

    @Override public void onInputDeviceAdded(int deviceId) { refreshControllers(); }
    @Override public void onInputDeviceRemoved(int deviceId) { refreshControllers(); }
    @Override public void onInputDeviceChanged(int deviceId) { refreshControllers(); }

    /** a real controller: buttons and sticks (phones' key devices have no sticks) */
    private static boolean isController(InputDevice device) {
        if (device == null || device.isVirtual())
            return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD &&
               (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void refreshControllers() {
        boolean found = false;
        for (int id : InputDevice.getDeviceIds()) {
            if (isController(InputDevice.getDevice(id))) {
                found = true;
                break;
            }
        }
        controllerConnected = found;
        releaseAll();
        setVisibility(found ? GONE : VISIBLE);
        callSetEnabled(!found);
    }

    // ---------- native calls (libmain.so is loaded by SDLActivity)

    private void callSetEnabled(boolean enabled) {
        if (!nativeReady)
            return;
        try {
            nativeSetEnabled(enabled);
        } catch (UnsatisfiedLinkError e) {
            nativeReady = false;
        }
    }

    private void sendState() {
        if (!nativeReady)
            return;
        float[] move = stick(moveFinger, 60);
        float[] look = stick(lookFinger, 55);
        int mask = 0;
        boolean leftTrigger = false, rightTrigger = false;
        for (Button button : buttons) {
            if (button.pointers <= 0)
                continue;
            if (button.code == LEFT_TRIGGER) leftTrigger = true;
            else if (button.code == RIGHT_TRIGGER) rightTrigger = true;
            else if (button.code >= 0) mask |= 1 << button.code;
        }
        try {
            nativeSetState((int) (move[0] * 32767), (int) (move[1] * 32767),
                           (int) (look[0] * 32767), (int) (look[1] * 32767),
                           leftTrigger ? 32767 : 0, rightTrigger ? 32767 : 0, mask);
        } catch (UnsatisfiedLinkError e) {
            nativeReady = false;
        }
    }

    /** a finger's offset from where it landed, as a stick in -1..1 (y down) */
    private float[] stick(Finger finger, float radiusDp) {
        if (finger == null)
            return new float[] { 0, 0 };
        float radius = radiusDp * density;
        float x = (finger.x - finger.originX) / radius;
        float y = (finger.y - finger.originY) / radius;
        float length = (float) Math.sqrt(x * x + y * y);
        if (length > 1) {
            x /= length;
            y /= length;
        }
        return new float[] { x, y };
    }

    // ---------- layout and drawing

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        for (Button button : buttons)
            place(button, width, height);
        place(toggle, width, height);
        toggle.cx = width / 2f;
    }

    private void place(Button button, int width, int height) {
        float dx = button.dx * density, dy = button.dy * density;
        button.cx = button.fromRight ? width - dx : dx;
        button.cy = button.fromBottom ? height - dy : dy;
        button.r = button.radius * density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawButton(canvas, toggle);
        if (buttonsHidden)
            return;
        for (Button button : buttons)
            drawButton(canvas, button);

        // the move stick: where the finger is, or its resting place
        float baseX, baseY, knobX, knobY, radius = 60 * density;
        if (moveFinger != null) {
            float[] move = stick(moveFinger, 60);
            baseX = moveFinger.originX;
            baseY = moveFinger.originY;
            knobX = baseX + move[0] * radius;
            knobY = baseY + move[1] * radius;
        } else {
            baseX = knobX = 150 * density;
            baseY = knobY = getHeight() - 110 * density;
        }
        fill.setColor(Color.argb(45, 255, 255, 255));
        canvas.drawCircle(baseX, baseY, radius, fill);
        ring.setColor(Color.argb(110, 255, 255, 255));
        canvas.drawCircle(baseX, baseY, radius, ring);
        fill.setColor(Color.argb(moveFinger != null ? 150 : 90, 255, 255, 255));
        canvas.drawCircle(knobX, knobY, 26 * density, fill);
    }

    private void drawButton(Canvas canvas, Button button) {
        boolean down = button.pointers > 0;
        fill.setColor(down ? Color.argb(140, 255, 255, 255) : Color.argb(55, 0, 0, 0));
        canvas.drawCircle(button.cx, button.cy, button.r, fill);
        ring.setColor(Color.argb(down ? 220 : 120, 255, 255, 255));
        canvas.drawCircle(button.cx, button.cy, button.r, ring);
        float size = Math.min(button.r * 0.6f, 15 * density);
        if (button.label.length() > 2)
            size = Math.min(size, button.r * 1.6f / (button.label.length() * 0.62f));
        text.setTextSize(size);
        canvas.drawText(button.label, button.cx, button.cy - (text.ascent() + text.descent()) / 2, text);
    }

    // ---------- touches

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (controllerConnected)
            return false;
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        boolean changed = false;

        switch (action) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
            changed = fingerDown(event.getPointerId(index), event.getX(index), event.getY(index));
            break;
        case MotionEvent.ACTION_MOVE:
            for (int i = 0; i < event.getPointerCount(); i++) {
                Finger finger = fingers.get(event.getPointerId(i));
                if (finger == null)
                    continue;
                finger.x = event.getX(i);
                finger.y = event.getY(i);
                changed = true;
            }
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
            changed = fingerUp(event.getPointerId(index));
            break;
        case MotionEvent.ACTION_CANCEL:
            releaseAll();
            changed = true;
            break;
        default:
            break;
        }
        if (changed) {
            sendState();
            invalidate();
        }
        return true;
    }

    private boolean fingerDown(int id, float x, float y) {
        if (toggle.contains(x, y, 6 * density)) {
            buttonsHidden = !buttonsHidden;
            releaseAll();
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            return true;
        }
        if (buttonsHidden)
            return false;

        Finger finger = new Finger();
        finger.originX = finger.x = x;
        finger.originY = finger.y = y;

        Button hit = null;
        for (Button button : buttons) {
            if (button.contains(x, y, 4 * density)) {
                hit = button;
                break;
            }
        }
        if (hit != null) {
            finger.role = ROLE_BUTTON;
            finger.button = hit;
            hit.pointers++;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            // fire and aim with one thumb
            if (hit.code == RIGHT_TRIGGER && lookFinger == null)
                lookFinger = finger;
        } else if (x < getWidth() * 0.45f) {
            if (moveFinger != null)
                return false;
            finger.role = ROLE_MOVE;
            moveFinger = finger;
        } else {
            if (lookFinger != null)
                return false;
            finger.role = ROLE_LOOK;
            lookFinger = finger;
        }
        fingers.put(id, finger);
        return true;
    }

    private boolean fingerUp(int id) {
        Finger finger = fingers.get(id);
        if (finger == null)
            return false;
        fingers.remove(id);
        if (finger.button != null)
            finger.button.pointers--;
        if (finger == moveFinger)
            moveFinger = null;
        if (finger == lookFinger)
            lookFinger = null;
        return true;
    }

    private void releaseAll() {
        fingers.clear();
        moveFinger = lookFinger = null;
        for (Button button : buttons)
            button.pointers = 0;
        sendState();
        invalidate();
    }
}
