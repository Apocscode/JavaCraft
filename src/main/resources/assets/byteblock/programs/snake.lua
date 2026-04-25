-- snake.lua — classic snake game
local w, h = term.getSize()
local snake = { {x = math.floor(w/2), y = math.floor(h/2)} }
local dx, dy = 1, 0
local food = { x = math.random(2, w - 1), y = math.random(2, h - 1) }
local score = 0
local alive = true

local function draw()
    term.setBackgroundColor(colors.black); term.clear()
    term.setBackgroundColor(colors.red)
    term.setCursorPos(food.x, food.y); term.write(" ")
    term.setBackgroundColor(colors.lime)
    for _, s in ipairs(snake) do
        term.setCursorPos(s.x, s.y); term.write(" ")
    end
    term.setBackgroundColor(colors.black); term.setTextColor(colors.white)
    term.setCursorPos(1, 1); term.write("Score: " .. score .. "  (arrows / WASD, q quit)")
end

local function step()
    local head = snake[1]
    local nx, ny = head.x + dx, head.y + dy
    if nx < 1 or nx > w or ny < 2 or ny > h then alive = false; return end
    for _, s in ipairs(snake) do
        if s.x == nx and s.y == ny then alive = false; return end
    end
    table.insert(snake, 1, {x = nx, y = ny})
    if nx == food.x and ny == food.y then
        score = score + 1
        food = { x = math.random(2, w - 1), y = math.random(2, h - 1) }
    else
        table.remove(snake)
    end
end

local timer = os.startTimer(0.15)
draw()
while alive do
    local ev, p1 = os.pullEvent()
    if ev == "timer" and p1 == timer then
        step(); draw()
        timer = os.startTimer(0.15)
    elseif ev == "key" then
        if     p1 == keys.up    or p1 == keys.w then if dy ==  0 then dx, dy = 0, -1 end
        elseif p1 == keys.down  or p1 == keys.s then if dy ==  0 then dx, dy = 0,  1 end
        elseif p1 == keys.left  or p1 == keys.a then if dx ==  0 then dx, dy = -1, 0 end
        elseif p1 == keys.right or p1 == keys.d then if dx ==  0 then dx, dy =  1, 0 end
        elseif p1 == keys.q then alive = false end
    end
end
term.setBackgroundColor(colors.black); term.clear()
term.setCursorPos(1, 1); term.setTextColor(colors.red)
print("GAME OVER — score: " .. score)
