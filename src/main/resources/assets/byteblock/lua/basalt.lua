-- ════════════════════════════════════════════════════════════════════════
--  basalt.lua — minimal Basalt-style UI framework for ByteBlock terminals
--
--  Uses only standard term + paintutils + os.pullEvent — works on any
--  CC-compatible runtime. Supports: Frame, Label, Button, TextBox, List,
--  Checkbox, ProgressBar, focus + hover + click events.
--
--  Usage:
--    local basalt = require("basalt")
--    local main = basalt.createFrame()
--    main:addLabel():setPosition(2,1):setText("Hello!")
--    main:addButton():setPosition(2,3):setSize(10,1):setText("Quit")
--         :onClick(function() basalt.stop() end)
--    basalt.run()
-- ════════════════════════════════════════════════════════════════════════
local basalt = {}

local running = false
local rootFrames = {}
local activeFrame = nil

-- ─── color constants (CC palette indices) ──────────────────────────────
local C = {
    white=1, orange=2, magenta=4, lightBlue=8, yellow=16, lime=32, pink=64,
    gray=128, lightGray=256, cyan=512, purple=1024, blue=2048, brown=4096,
    green=8192, red=16384, black=32768,
}
basalt.colors = C

-- ─── object base ───────────────────────────────────────────────────────
local function newObject(kind, parent)
    local o = {
        _kind = kind, _parent = parent,
        x = 1, y = 1, w = 10, h = 1,
        bg = C.gray, fg = C.white,
        text = "", visible = true, enabled = true,
        _onClick = nil, _onChange = nil,
        _children = {},
    }
    function o:setPosition(x,y) self.x = x; self.y = y; return self end
    function o:setSize(w,h) self.w = w; self.h = h; return self end
    function o:setText(t) self.text = tostring(t or ""); return self end
    function o:getText() return self.text end
    function o:setBackground(c) self.bg = c; return self end
    function o:setForeground(c) self.fg = c; return self end
    function o:show() self.visible = true; return self end
    function o:hide() self.visible = false; return self end
    function o:onClick(fn) self._onClick = fn; return self end
    function o:onChange(fn) self._onChange = fn; return self end
    function o:hitTest(mx, my)
        return mx >= self.x and mx < self.x + self.w
           and my >= self.y and my < self.y + self.h
    end
    return o
end

-- ─── widgets ───────────────────────────────────────────────────────────

local function newLabel(parent)
    local o = newObject("Label", parent)
    o.bg = nil
    function o:_render()
        if not self.visible then return end
        if self.bg then term.setBackgroundColor(self.bg) end
        term.setTextColor(self.fg)
        term.setCursorPos(self.x, self.y)
        term.write(self.text)
    end
    return o
end

local function newButton(parent)
    local o = newObject("Button", parent)
    o.bg = C.lightGray; o.fg = C.black; o.w = 10; o.h = 1
    o._hover = false
    function o:_render()
        if not self.visible then return end
        local bg = self._hover and C.cyan or self.bg
        term.setBackgroundColor(bg); term.setTextColor(self.fg)
        for r = 0, self.h - 1 do
            term.setCursorPos(self.x, self.y + r)
            term.write(string.rep(" ", self.w))
        end
        local label = self.text
        if #label > self.w then label = label:sub(1, self.w) end
        local lx = self.x + math.floor((self.w - #label) / 2)
        local ly = self.y + math.floor(self.h / 2)
        term.setCursorPos(lx, ly); term.write(label)
    end
    function o:_event(ev, p1, p2, p3)
        if not (self.enabled and self.visible) then return end
        if ev == "mouse_click" and self:hitTest(p2, p3) then
            self._hover = true
            if self._onClick then self._onClick(self, p1, p2, p3) end
            return true
        elseif ev == "mouse_up" then
            self._hover = false
        end
    end
    return o
end

local function newTextBox(parent)
    local o = newObject("TextBox", parent)
    o.bg = C.black; o.fg = C.white; o.w = 16; o.h = 1
    o._cursor = 0; o._focus = false
    function o:_render()
        if not self.visible then return end
        term.setBackgroundColor(self.bg); term.setTextColor(self.fg)
        term.setCursorPos(self.x, self.y)
        local v = self.text
        if #v > self.w then v = v:sub(#v - self.w + 1) end
        term.write(v .. string.rep(" ", self.w - #v))
        if self._focus then
            term.setCursorPos(self.x + math.min(#self.text, self.w - 1), self.y)
            term.setCursorBlink(true)
        end
    end
    function o:_event(ev, p1, p2, p3)
        if not (self.enabled and self.visible) then return end
        if ev == "mouse_click" then self._focus = self:hitTest(p2, p3) end
        if not self._focus then return end
        if ev == "char" then
            self.text = self.text .. p1
            if self._onChange then self._onChange(self, self.text) end
            return true
        elseif ev == "key" then
            if p1 == 259 and #self.text > 0 then  -- backspace
                self.text = self.text:sub(1, -2)
                if self._onChange then self._onChange(self, self.text) end
                return true
            end
        end
    end
    return o
end

local function newList(parent)
    local o = newObject("List", parent)
    o.bg = C.black; o.fg = C.white; o.w = 16; o.h = 6
    o.items = {}; o.selected = 1; o.scroll = 0
    function o:addItem(t) self.items[#self.items+1] = tostring(t); return self end
    function o:clearItems() self.items = {}; self.selected = 1; self.scroll = 0; return self end
    function o:getSelected() return self.items[self.selected] end
    function o:_render()
        if not self.visible then return end
        for r = 0, self.h - 1 do
            local idx = self.scroll + r + 1
            local item = self.items[idx] or ""
            if idx == self.selected then
                term.setBackgroundColor(C.cyan); term.setTextColor(C.black)
            else
                term.setBackgroundColor(self.bg); term.setTextColor(self.fg)
            end
            term.setCursorPos(self.x, self.y + r)
            local s = item
            if #s > self.w then s = s:sub(1, self.w) end
            term.write(s .. string.rep(" ", self.w - #s))
        end
    end
    function o:_event(ev, p1, p2, p3)
        if not (self.enabled and self.visible) then return end
        if ev == "mouse_click" and self:hitTest(p2, p3) then
            local row = p3 - self.y + 1
            local idx = self.scroll + row
            if self.items[idx] then
                self.selected = idx
                if self._onChange then self._onChange(self, idx, self.items[idx]) end
                if self._onClick then self._onClick(self, idx, self.items[idx]) end
            end
            return true
        elseif ev == "mouse_scroll" and self:hitTest(p2, p3) then
            self.scroll = math.max(0, math.min(#self.items - self.h, self.scroll + p1))
            return true
        end
    end
    return o
end

local function newCheckbox(parent)
    local o = newObject("Checkbox", parent)
    o.bg = nil; o.fg = C.white; o.checked = false; o.w = 1; o.h = 1
    function o:setChecked(v) self.checked = v and true or false; return self end
    function o:_render()
        if not self.visible then return end
        if self.bg then term.setBackgroundColor(self.bg) end
        term.setTextColor(self.fg)
        term.setCursorPos(self.x, self.y)
        term.write(self.checked and "[X]" or "[ ]")
        if #self.text > 0 then
            term.setCursorPos(self.x + 4, self.y); term.write(self.text)
        end
    end
    function o:_event(ev, p1, p2, p3)
        if not (self.enabled and self.visible) then return end
        if ev == "mouse_click" and p2 >= self.x and p2 < self.x + 3 + 1 + #self.text and p3 == self.y then
            self.checked = not self.checked
            if self._onChange then self._onChange(self, self.checked) end
            if self._onClick then self._onClick(self, self.checked) end
            return true
        end
    end
    return o
end

local function newProgressBar(parent)
    local o = newObject("ProgressBar", parent)
    o.bg = C.gray; o.fg = C.lime; o.value = 0; o.max = 1; o.w = 20; o.h = 1
    function o:setValue(v) self.value = v; return self end
    function o:setMax(v) self.max = v; return self end
    function o:_render()
        if not self.visible then return end
        local pct = math.max(0, math.min(1, self.value / self.max))
        local fill = math.floor(pct * self.w)
        term.setBackgroundColor(self.bg); term.setCursorPos(self.x, self.y)
        term.write(string.rep(" ", self.w))
        if fill > 0 then
            term.setBackgroundColor(self.fg); term.setCursorPos(self.x, self.y)
            term.write(string.rep(" ", fill))
        end
    end
    return o
end

-- ─── frame container ───────────────────────────────────────────────────
local function newFrame()
    local f = newObject("Frame", nil)
    f.bg = C.black; f.fg = C.white
    local sw, sh = term.getSize(); f.w = sw; f.h = sh
    function f:addLabel()       local c = newLabel(self);       self._children[#self._children+1] = c; return c end
    function f:addButton()      local c = newButton(self);      self._children[#self._children+1] = c; return c end
    function f:addTextBox()     local c = newTextBox(self);     self._children[#self._children+1] = c; return c end
    function f:addList()        local c = newList(self);        self._children[#self._children+1] = c; return c end
    function f:addCheckbox()    local c = newCheckbox(self);    self._children[#self._children+1] = c; return c end
    function f:addProgressBar() local c = newProgressBar(self); self._children[#self._children+1] = c; return c end

    function f:_render()
        if self.bg then term.setBackgroundColor(self.bg); term.clear() end
        for _, c in ipairs(self._children) do c:_render() end
    end
    function f:_event(ev, p1, p2, p3)
        for i = #self._children, 1, -1 do
            local c = self._children[i]
            if c._event and c:_event(ev, p1, p2, p3) then return true end
        end
    end
    return f
end

-- ─── public API ────────────────────────────────────────────────────────
function basalt.createFrame()
    local f = newFrame()
    rootFrames[#rootFrames+1] = f
    activeFrame = f
    return f
end
function basalt.setActive(f) activeFrame = f end
function basalt.stop() running = false end

function basalt.run()
    running = true
    while running do
        if activeFrame then activeFrame:_render() end
        local ev = { os.pullEvent() }
        if activeFrame then activeFrame:_event(table.unpack(ev)) end
        if ev[1] == "terminate" then running = false end
    end
    term.setBackgroundColor(C.black); term.setTextColor(C.white); term.clear()
    term.setCursorPos(1,1); term.setCursorBlink(false)
end

return basalt
