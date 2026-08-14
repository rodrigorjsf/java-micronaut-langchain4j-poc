---
name: geo-and-weather
description: Places, addresses and weather worldwide — turn a name or a street address into coordinates and back, then read current conditions, forecasts, past weather, air quality, sea state, elevation and sunrise. Use for any weather, location or address question.
---

# Geography and weather

Ten tools over one idea: **coordinates are the currency of this skill**. Two tools
produce them, one turns them back into an address, and the rest consume them.
Nothing here accepts a place name except `find_place` and `search_address_osm`.

## Choosing a tool

| The user gives you | Use |
|---|---|
| a city, region or country name | `find_place` |
| a street address, a landmark, a shop, a POI | `search_address_osm` |
| coordinates, and wants today or the next days | `get_weather` |
| coordinates, and wants a past date or range | `get_historical_weather` |
| coordinates, and asks about pollution, smoke or haze | `get_air_quality` |
| coordinates on the coast, and asks about waves, surf or sailing | `get_marine_conditions` |
| coordinates, and asks how high the place is | `get_elevation` |
| coordinates, and asks when the sun rises or sets | `get_sun_times` |
| coordinates, and wants to know what is there | `reverse_geocode_osm` |
| a postal code from outside Brazil | `lookup_postal_code_intl` |

## The normal flow

1. `find_place` turns a name into latitude, longitude, country and timezone,
   returning several candidates ranked by population.
2. Hand those coordinates to whichever question tool the user actually asked for.

Never pass a place name to a tool that wants coordinates — it will refuse and you
will have spent a round trip. If you already hold coordinates, skip step 1: a CEP
lookup in the `brazil-civic-data` skill returns them, and so does
`search_address_osm`.

`find_place` for a city, `search_address_osm` for anything smaller. "Curitiba" is
a `find_place` question; "Rua XV de Novembro 500, Curitiba" is a
`search_address_osm` one.

## Disambiguation

`find_place` returning more than one candidate is normal and important: there is
a São Paulo in Brazil and a São Paulo in Portugal. When the top two candidates
are in different countries or different states, ask the user which one they mean
instead of silently picking the largest. When they are clearly the same place at
different administrative levels, pick the first.

## Reading the results

- **Weather.** Temperatures are Celsius, wind km/h, precipitation mm. The
  forecast is in the location's own timezone, so "today" means today there.
  `weather_code` is a WMO code, not a description — translate it: 0 clear, 1-3
  increasingly cloudy, 45/48 fog, 51-57 drizzle, 61-67 rain, 71-77 snow, 80-82
  showers, 95-99 thunderstorm.
- **Historical weather.** `time` and the measurement arrays share an index: the
  third date goes with the third maximum. The archive runs a few days behind
  today, so the most recent days can come back empty — say so rather than
  reporting a gap as zero rainfall.
- **Air quality.** PM2.5 and PM10 are µg/m³; `us_aqi` is the US index, where 0-50
  is good, 51-100 moderate, 101-150 unhealthy for sensitive groups, above 150
  unhealthy for everyone.
- **Marine.** `wave_height` is significant wave height in metres. A result with
  **no** `wave_height` means the point is inland, not that the sea is calm — tell
  the user the coordinates are not at sea and offer to try a coastal point.
- **Sun times.** Every timestamp is **UTC**. Convert to the place's own timezone
  — the one `find_place` returned — before telling the user a clock time, or you
  will be hours off. `day_length` is in seconds.
- **Elevation.** Metres above sea level, for the exact point, not the summit of a
  nearby mountain.

## Cost and pacing

`search_address_osm` and `reverse_geocode_osm` run on a volunteer service that
allows about one request per second and asks not to be used in bulk. Call at most
one of them per turn, and prefer `find_place` whenever a city-level answer is
enough.

## What this skill does not do

No severe-weather alerts or storm warnings, no pollen counts, no tide tables, no
driving directions or distances between two points, and no Brazilian CEPs — those
belong to `lookup_cep` in the `brazil-civic-data` skill, and
`lookup_postal_code_intl` has no Brazilian data at all. If asked for any of
these, say so plainly rather than approximating from a forecast.

## When a tool fails

- **Not found** from a postal-code or address lookup is an answer, not a failure.
  Report that nothing matched and ask the user to confirm what they typed; do not
  invent a nearby code.
- **Invalid arguments** tells you exactly what was wrong. Fix the call yourself —
  usually it means you sent a name where coordinates belong — and only ask the
  user when the missing detail is genuinely theirs to give.
- **Rate limited or unavailable** — answer from what you already have, name the
  data as unavailable, and do not retry the same tool in the same turn.
