---
name: network-and-identity
description: IP address lookups and name statistics — the country, city, provider and autonomous system a public IP is registered to, and how often a first name is recorded as male or female, at what average age, in which countries. Use when the user gives an IP address or asks what a first name suggests.
---

# Network and identity

Four tools over two unrelated questions: where a block of internet addresses is
registered, and what a name database records about a first name. Both produce
numbers that sound like facts about a person and are not. Read the two warnings
below before answering from either one — they are the whole reason this skill
needs care.

## Choosing a tool

| The user gives you | Use |
|---|---|
| an IP address; "de onde vem esse IP?"; a server, host or log line to place | `geolocate_ip` |
| "esse nome é de homem ou de mulher?" — a first name, asked about gender | `guess_gender_from_name` |
| "esse nome é de que geração?" — a first name, asked about age | `guess_age_from_name` |
| "de onde vem esse nome?" — a first name, asked about country of origin | `guess_nationality_from_name` |

Every tool needs one argument and answers in one call. There is no sequence to
run here: pick the one tool that matches what was asked and stop.

## The name tools are population statistics, not facts about a person

`guess_gender_from_name`, `guess_age_from_name` and `guess_nationality_from_name`
count records in a name database. They describe **the name**, across millions of
people, and they are frequently wrong about the individual in front of you.
Andrea is overwhelmingly female in Brazil and male in Italy. A name that skews
old in the data belongs to plenty of children. A name common in Portugal is
carried by millions of Brazilians.

So report the statistic, never the inference:

- **Say it.** "Nesse banco de nomes, 91% dos registros de Maria são femininos" —
  not "Maria é mulher". "A média de idade registrada para esse nome é 46 anos" —
  not "essa pessoa tem 46 anos".
- **Always quote `probability` and `count`.** They are the difference between a
  real pattern and noise: `probability: 0.98, count: 120000` is a strong pattern,
  `probability: 0.51, count: 4` is nothing at all. A result with a small `count`
  should be reported as "the database barely has this name".
- **Never apply the result to a named individual**, and never let it decide how
  to address someone. If the user asks "is this customer a man or a woman", the
  honest answer is that a name cannot settle that — ask them, or use what they
  told you.
- The database is international and not weighted to any one country, so a name
  common in several places will look diluted. Say so instead of picking the top
  row and calling it the origin.

## IP geolocation is approximate, and it is not a person

`geolocate_ip` returns where an address **block is registered** — usually the internet provider's own record, which can point at
the provider's head office, a peering city, or the exit of a VPN. Mobile
addresses routinely resolve hundreds of kilometres from the handset.

- **Describe the network, not a person.** "Esse IP está registrado em um
  provedor com sede em São Paulo" — never "o usuário está em São Paulo".
- The `isp`, `org` and `asn` fields are the most reliable part of the answer and
  usually the useful one: they say *whose* network it is, which is what someone
  reading a log actually wants.
- Coordinates come back with city-level precision at best. Do not present them
  as an address, and do not hand them to a mapping or reverse-geocoding tool to
  manufacture a street that was never in the data.
- If the user is trying to find out where a specific person is, say plainly that
  an IP lookup cannot do that.

## Reading the results

- **`geolocate_ip`** returns `ip`, `country`, `city`, `latitude`, `longitude`,
  `isp`, `org` and `asn` — all at the top level, and `asn` is the autonomous
  system number, which identifies the network itself. Different geolocation
  providers routinely disagree by a city on the same address; that spread is the
  honest accuracy of the technique, so present the city as approximate rather
  than as a located fact.
- **`guess_gender_from_name`** returns `gender` (`male` or `female`),
  `probability` (0 to 1, the share of records on that side) and `count` (how many
  records exist). A `probability` near 0.5 means the name is genuinely used for
  everyone.
- **`guess_age_from_name`** returns `age`, a mean rounded to a whole year, and
  `count`. A mean hides the spread completely: report it as an average, never as
  a range or an estimate of anyone's birth year.
- **`guess_nationality_from_name`** returns `country`, a list of at most three
  entries, each a `country_id` with its `probability` — a share of the records,
  not a chance that a person is from there. A trailing `{"_more": n}` means the
  source held more; those were the weakest, and quoting a country at four percent
  as an origin is over-reading the data. The shares do not sum to 1.

## What this skill does not cover

No person identification, no lookup of who owns an address or a name, no WHOIS,
no reverse DNS, no port scanning, no email or phone validation, no breach checks,
no VPN or proxy detection, and no "where is this user right now".

**Do not combine the two halves of this skill to profile anybody.** An IP
location plus a name-derived gender, age and nationality is a portrait of a
person built entirely out of statistics that were never about them, and it is
wrong far more often than it reads as being. If a request needs that
combination, answer the individual questions with their caveats and decline the
portrait.

Country facts, capitals and populations belong to `countries-and-economy`;
turning a place into coordinates belongs to `geo-and-weather`; Brazilian name
census data — real frequencies per decade, from IBGE — belongs to
`brazil-civic-data` and is a better source than the name guessers for a
Brazilian question.

## When a tool fails

- **Invalid arguments** on an address means it was malformed, or it was a
  private, loopback or link-local address such as `10.0.0.5` or `127.0.0.1`.
  Those exist inside every network and have no location anywhere; tell the user
  the address is internal instead of retrying. A hostname is not an address —
  ask for the address.
- **Unavailable** from `geolocate_ip` means the geolocation service itself is
  down. There is no second provider to fall back to — say the lookup could not be
  made, and answer the rest of the question without it.
- **No result** for an address means there is no registration on file for it. Say
  the address could not be located rather than naming a country, and do not call
  the tool again with the same address.
- **No result** for a name means the database has never seen it. That is an
  answer — report it. Do not fall back to guessing gender from the ending of the
  word or origin from how the name looks.
- **Rate limited** on any name tool means the shared daily allowance for this
  deployment is spent — it is small, roughly a hundred names a day across all
  three tools together. Say the name service is unavailable and answer the rest
  of the question without it. Retrying in the same turn will fail identically,
  and so will the other two name tools.
- **Rate limited** on `geolocate_ip` means the monthly allowance is spent. Say
  the lookup is unavailable; retrying in the same turn will fail identically.
