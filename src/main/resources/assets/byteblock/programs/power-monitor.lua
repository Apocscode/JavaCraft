-- power-monitor.lua — simple gauge bar HUD on glasses
-- Reads RF/FE-style data from a peripheral named "energy" if present;
-- otherwise simulates for demo purposes.
local channel = tonumber(arg and arg[1]) or 1
glasses.setChannel(channel)
glasses.clear()
glasses.addTitle("hdr", "POWER MONITOR", "cyan")
glasses.addBar("pwr", "Stored", 0, 100000, 0, "blue")
glasses.addBar("io",  "Net I/O", -1000, 1000, 0, "lime")
glasses.addText("upd", "Updated", "--")
glasses.flush()

local last = 0
while true do
    local stored, net
    local p = peripheral and peripheral.find and peripheral.find("energy")
    if p and p.getEnergy then
        stored = p.getEnergy()
        net = stored - last
        last = stored
    else
        stored = math.random(20000, 80000)
        net = math.random(-500, 500)
    end
    glasses.set("pwr", stored)
    glasses.set("io", net)
    glasses.set("upd", os.date and os.date("%H:%M:%S") or tostring(os.time()))
    glasses.flush()
    sleep(2)
end
