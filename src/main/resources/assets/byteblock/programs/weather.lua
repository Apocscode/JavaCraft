-- weather.lua — simple weather widget on glasses
local lat = settings.get("weather.lat") or 47.6062
local lon = settings.get("weather.lon") or -122.3321

local url = "https://api.open-meteo.com/v1/forecast?latitude=" .. lat
    .. "&longitude=" .. lon .. "&current=temperature_2m,wind_speed_10m,weather_code"
local resp = http.get(url)
if not resp then print("HTTP error"); return end
local body = resp.readAll(); resp.close()

-- Crude JSON extraction (no full parser needed).
local function num(key)
    local s, e = body:find("\"" .. key .. "\":[%-%d.]+")
    if not s then return nil end
    return tonumber(body:sub(s, e):match("[%-%d.]+"))
end

local t  = num("temperature_2m")
local ws = num("wind_speed_10m")
local wc = num("weather_code") or 0

local codes = {
    [0]="Clear", [1]="Mostly clear", [2]="Partly cloudy", [3]="Overcast",
    [45]="Fog", [51]="Drizzle", [61]="Rain", [71]="Snow",
    [80]="Showers", [95]="Thunderstorm",
}
local desc = codes[math.floor(wc)] or "code " .. wc

print(string.format("Weather  @ %.4f, %.4f", lat, lon))
print(string.format("  Temp:  %s C", tostring(t)))
print(string.format("  Wind:  %s km/h", tostring(ws)))
print(string.format("  Cond:  %s", desc))

if glasses then
    glasses.clear()
    glasses.addTitle("w", "Weather", "cyan")
    glasses.addText("temp", "Temp", string.format("%s C", tostring(t)))
    glasses.addText("wind", "Wind", string.format("%s km/h", tostring(ws)))
    glasses.addText("cond", "Cond", desc)
    glasses.flush()
end
