---
name: science-and-space
description: Live space and Earth-science data — NASA's astronomy picture of the day, recent earthquakes worldwide with magnitude and location, where the International Space Station is right now, who is currently in orbit, and the next scheduled rocket launches. Use for astronomy, spaceflight and earthquake questions.
---

# Science and space

Five tools over five independent observatories. Every one of them reports
something **happening now**: the station moves 7.7 km a second, the earthquake
feed changes by the minute, and a launch date slips. Answer from the tool result
and quote the timestamp it carried — a remembered ISS position is wrong before
you finish the sentence.

## Choosing a tool

| The user gives you | Use |
|---|---|
| "show me today's space picture", or a date they want NASA's image for | `get_astronomy_picture_of_day` |
| "was there an earthquake", a region, a magnitude, a recent date | `get_recent_earthquakes` |
| "where is the ISS", "is it over Brazil right now" | `get_iss_position` |
| "when is the next launch", "what is launching this week" | `get_upcoming_launches` |

## How to combine them

- **The station: position and crew are two questions.** `get_iss_position` says
  else in orbit. "Who is on the ISS right now" needs only the second one.
- **From orbit to the ground.** `get_iss_position` returns latitude and
  longitude. To say *what* it is flying over, hand those coordinates to
  `reverse_geocode_osm` in the `geo-and-weather` skill — but only if the user
  asked; the coordinates alone answer most questions.
- **Earthquake to place.** The feed already names the place in plain language
  ("112 km SSE of Adak, Alaska"). Do not geocode it again to restate it.
- **One launch tool per turn.** `get_upcoming_launches` runs on a service that
  allows only about fifteen anonymous requests an hour, shared across every
  conversation. Call it once, then answer from what came back.

## Reading the results

- **Astronomy picture.** `media_type` is `image` for a photograph and `video`
  for an embedded clip — say which one it is instead of describing a video as a
  picture. `explanation` is written by an astronomer for a general audience;
  summarize it rather than pasting it whole. `copyright`, when present, means
  the image is *not* public domain — name the photographer.
- **Earthquakes.** Results come back strongest-first, not newest-first. Each
  feature's `properties.mag` is the magnitude, `properties.place` the location
  in words, `properties.time` a Unix timestamp in **milliseconds** (convert it
  before quoting a date), and `geometry.coordinates` is `[longitude, latitude,
  depth in km]` — longitude first, which is the opposite of the usual order.
  Magnitude is logarithmic: 6 is not "a bit more than" 5, it releases about
  thirty times the energy.
- **ISS position.** `latitude` and `longitude` are degrees, `altitude` km,
  `velocity` km/h, and `visibility` is `daylight` or `eclipsed` — that is the
  station's own lighting, not whether anyone on the ground can see it.
  `timestamp` is Unix seconds; the fix is a snapshot, so say "as of" and give
  the time.
- **People in space.** `number` is the total across all craft, and each entry
  names the spacecraft. Do not assume everyone is on the ISS — Tiangong is
  usually crewed too.
- **Launches.** `net` is "no earlier than", the earliest the rocket can fly, not
  a promise; `status` says how firm it is (`Go`, `TBD`, `TBC`). Always report
  the status alongside the date, and treat a `TBD` window as "not scheduled
  yet".

## What this skill does not cover

No asteroid or near-Earth-object tracking, no live aircraft or flight tracking,
no satellite passes or "when will the ISS be visible from my city", no tsunami
or aftershock warnings, no historical earthquake catalogues beyond what the
recent feed returns, and no launch results after the fact. Weather, air quality
and sunrise times belong to `geo-and-weather`; if asked for any of these, say
plainly that this skill does not have it rather than estimating.

## When a tool fails

- **Rate limited** on the astronomy picture means the shared NASA quota for this
  deployment is spent for the hour. Say the image is not available right now and
  move on; retrying in the same turn will fail identically.
- **Rate limited** on launches means the hourly anonymous budget is gone. Answer
  from anything already retrieved and do not call it again this turn.
- **Unavailable** is a real outage of one observatory, not of the skill. The
  other four tools still answer — say which piece is missing rather than
  refusing the whole question.
- **An empty earthquake list is an answer**: no event above that magnitude in
  that window. Report exactly that, and offer to lower the magnitude threshold
  rather than implying the feed is broken.
- **Invalid arguments** tells you what was wrong. Fix it yourself — usually a
  future date, or a magnitude sent as words instead of a number — and only ask
  the user when the missing detail is genuinely theirs.
