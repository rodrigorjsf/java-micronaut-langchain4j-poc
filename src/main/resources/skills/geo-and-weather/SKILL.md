---
name: geo-and-weather
description: Place lookup and weather worldwide — turn a place name into coordinates, and get current conditions or a daily forecast for any latitude and longitude. Use for weather, temperature, rain, and "where is this place" questions.
---

# Geography and weather

Two tools, and they are meant to be used in that order.

## The normal flow

1. `find_place` turns a place name into latitude, longitude, country and
   timezone. It returns several candidates ranked by population.
2. `get_weather` takes those coordinates and returns current conditions plus a
   daily forecast.

Never pass a place name to `get_weather` — it only accepts coordinates. If you
already have coordinates (a CEP lookup returns them), skip step 1.

## Disambiguation

`find_place` returning more than one candidate is normal and important: there is
a São Paulo in Brazil and a São Paulo in Portugal. When the top two candidates
are in different countries or different states, ask the user which one they mean
instead of silently picking the largest. When they are clearly the same place at
different administrative levels, pick the first.

## Reading a weather result

- Temperatures are Celsius, wind is km/h, precipitation is millimetres.
- `weather_code` is a WMO code, not a description. Translate it for the user:
  0 clear, 1-3 increasingly cloudy, 45/48 fog, 51-57 drizzle, 61-67 rain,
  71-77 snow, 80-82 showers, 95-99 thunderstorm.
- The forecast is in the location's own timezone, so "today" means today there.

## What this skill does not do

No historical weather, no severe-weather alerts, no air quality. If asked, say
so plainly rather than approximating from the forecast.
