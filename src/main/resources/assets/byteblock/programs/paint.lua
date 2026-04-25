-- paint.lua — minimal paint app, exports NFP files
local w, h = term.getSize()
local canvas = {}
for y = 1, h - 2 do canvas[y] = {} for x = 1, w do canvas[y][x] = -1 end end
local color = colors.red
local palette = { colors.white, colors.orange, colors.magenta, colors.lightBlue,
    colors.yellow, colors.lime, colors.pink, colors.gray, colors.lightGray,
    colors.cyan, colors.purple, colors.blue, colors.brown, colors.green,
    colors.red, colors.black }

local function draw()
    term.setBackgroundColor(colors.black); term.clear()
    for y, row in ipairs(canvas) do
        for x, c in ipairs(row) do
            if c >= 0 then
                term.setBackgroundColor(c); term.setCursorPos(x, y); term.write(" ")
            end
        end
    end
    term.setBackgroundColor(colors.black); term.setCursorPos(1, h - 1)
    for i, c in ipairs(palette) do
        term.setBackgroundColor(c); term.write(" ")
    end
    term.setBackgroundColor(colors.black); term.setTextColor(colors.white)
    term.setCursorPos(1, h); term.write("S=save  Q=quit  click to paint")
end

draw()
while true do
    local ev, b, x, y = os.pullEvent()
    if ev == "mouse_click" or ev == "mouse_drag" then
        if y == h - 1 and x >= 1 and x <= #palette then
            color = palette[x]; draw()
        elseif y >= 1 and y <= h - 2 then
            canvas[y][x] = color
            term.setBackgroundColor(color); term.setCursorPos(x, y); term.write(" ")
        end
    elseif ev == "key" then
        if b == keys.q then term.setBackgroundColor(colors.black); term.clear() return end
        if b == keys.s then
            local lines = {}
            for y, row in ipairs(canvas) do
                local s = ""
                for x, c in ipairs(row) do
                    if c < 0 then s = s .. " "
                    else s = s .. string.format("%x", math.log(c) / math.log(2)) end
                end
                lines[#lines+1] = s
            end
            local f = fs.open("/Users/User/painting.nfp", "w")
            f.write(table.concat(lines, "\n")); f.close()
            term.setBackgroundColor(colors.black); term.setTextColor(colors.lime)
            term.setCursorPos(1, h); term.write("Saved /Users/User/painting.nfp" .. string.rep(" ", 20))
        end
    end
end
