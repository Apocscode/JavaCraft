-- clock.lua — large analog + digital clock for ByteBlock terminals
local w, h = term.getSize()
local cx, cy = math.floor(w/2), math.floor(h/2)
term.setBackgroundColor(colors.black); term.clear()

local function drawDigital()
    local t = os.date and os.date("%H:%M:%S") or tostring(os.time())
    term.setCursorPos(math.floor((w - #t)/2) + 1, h - 1)
    term.setTextColor(colors.cyan); term.write(t)
end

local function drawHand(angle, length, color)
    local sx, sy = cx, cy
    for i = 1, length do
        local x = math.floor(sx + math.sin(angle) * i + 0.5)
        local y = math.floor(sy - math.cos(angle) * i * 0.5 + 0.5)
        if x >= 1 and x <= w and y >= 1 and y <= h then
            term.setCursorPos(x, y)
            term.setBackgroundColor(color); term.write(" ")
            term.setBackgroundColor(colors.black)
        end
    end
end

while true do
    term.setBackgroundColor(colors.black); term.clear()
    -- face
    for a = 0, 2 * math.pi, math.pi / 12 do
        local x = math.floor(cx + math.sin(a) * (cx - 2) + 0.5)
        local y = math.floor(cy - math.cos(a) * (cy - 2) * 0.5 + 0.5)
        if x >= 1 and x <= w and y >= 1 and y <= h then
            term.setCursorPos(x, y); term.setTextColor(colors.gray); term.write(".")
        end
    end
    local s = (os.clock() % 60)
    local m = (os.clock() / 60) % 60
    local hr = (os.clock() / 3600) % 12
    drawHand(s * math.pi / 30, math.min(cx, cy) - 3, colors.red)
    drawHand(m * math.pi / 30, math.min(cx, cy) - 4, colors.white)
    drawHand(hr * math.pi / 6,  math.min(cx, cy) - 6, colors.cyan)
    drawDigital()
    sleep(1)
end
