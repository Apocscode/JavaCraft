package com.apocscode.byteblock.computer;

import java.util.ArrayList;
import java.util.List;

/**
 * Event system for the ByteBlock OS.
 * Events flow from input (keyboard, mouse, timer, bluetooth) to the focused program.
 */
public class OSEvent {

    public enum Type {
        KEY,            // key press: data[0]=keyCode, data[1]=isRepeat
        KEY_UP,         // key release: data[0]=keyCode
        CHAR,           // typed character: data[0]=character
        MOUSE_CLICK,    // mouse click: data[0]=button, data[1]=cellX, data[2]=cellY
        MOUSE_CLICK_PX, // pixel mouse click: data[0]=button, data[1]=pixelX, data[2]=pixelY
        MOUSE_UP,       // mouse release: data[0]=button, data[1]=x, data[2]=y
        MOUSE_DRAG,     // mouse drag: data[0]=button, data[1]=x, data[2]=y
        MOUSE_DRAG_PX,  // pixel mouse drag: data[0]=button, data[1]=pixelX, data[2]=pixelY
        MOUSE_SCROLL,   // mouse scroll: data[0]=direction, data[1]=x, data[2]=y
        TIMER,          // timer fired: data[0]=timerId
        BLUETOOTH,      // BT message: data[0]=channel, data[1]=message, data[2]=senderDistance
        REDSTONE,       // redstone change
        TERMINATE,      // Ctrl+T
        SHUTDOWN,       // OS shutdown
        REBOOT,         // OS reboot
        PASTE,          // clipboard paste: data[0]=text
        TASK_COMPLETE   // background task finished: data[0]=taskId
    }

    private final Type type;
    private final Object[] data;

    public OSEvent(Type type, Object... data) {
        this.type = type;
        this.data = data;
    }

    public Type getType() { return type; }

    public Object getData(int index) {
        if (index < 0 || index >= data.length) return null;
        return data[index];
    }

    public int getInt(int index) {
        Object val = getData(index);
        return val instanceof Number n ? n.intValue() : 0;
    }

    public String getString(int index) {
        Object val = getData(index);
        return val instanceof String s ? s : "";
    }

    public int getDataLength() { return data.length; }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        parts.add(type.name());
        for (Object d : data) parts.add(String.valueOf(d));
        return String.join(",", parts);
    }
}
