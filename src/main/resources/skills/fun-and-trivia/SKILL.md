---
name: fun-and-trivia
description: Playful lookups — shuffle and deal playing cards, Pokémon types and base stats, Rick and Morty characters, dog photos, cat facts, clean jokes, random trivia, everyday advice and quotations. Use when a question is light-hearted, or names a card game, a Pokémon or a joke.
---

# Fun and trivia

Light questions are part of the job, and answering them well means answering them
from a lookup. **Never answer any of these from memory.** An invented base stat, a
made-up "fun fact" and a misattributed quote read exactly like the real thing, and
nobody fact-checks a joke — which is precisely why a wrong one survives. If a tool
comes back with nothing, say nothing was found and offer another angle.

## Choosing a tool

| The user gives you | Use |
|---|---|
| "deal me a hand", "pick a card", the start of any card game | `new_shuffled_deck`, then `draw_cards` |
| a deck id already mentioned earlier in this conversation | `draw_cards` |
| a Pokémon — "how strong is Charizard", "what type is Gengar" | `get_pokemon` |
| a character from Rick and Morty | `get_rickandmorty_character` |
| "show me a dog", or curiosity about what a breed looks like | `get_random_dog_image` |
| "tell me something about cats" | `get_cat_fact` |
| a Chuck Norris joke, asked for by that name | `get_chuck_norris_joke` |
| "tell me a joke", "make me laugh", "something funny" | `get_programming_joke` |
| "a fun fact", "something random I didn't know" | `get_useless_fact` |
| "give me some advice", "a thought for the day" | `get_random_advice` |
| "a quote", "something inspiring to open a talk with" | `get_random_quote` |

## Cards are two tools and one id you must carry

`new_shuffled_deck` shuffles and returns **only** a `deck_id`. The cards live on the
card service; nothing on this side remembers the deck. So:

1. Call `new_shuffled_deck` once at the start of a game.
2. Keep the `deck_id` in the conversation — repeat it back to the user if the game
   will run over several turns, so it survives even if you lose it.
3. Call `draw_cards` with that id every time cards are needed.

Drawing does not put cards back, which is what makes the game real: `remaining`
falls, and the same card cannot come out twice. Shuffling a new deck mid-game
silently resets everything, so do it only when the user asks for a new game or the
old id has stopped working.

This is also the only genuine source of randomness available here. If the user asks
for a coin flip or a die roll, either draw a card and read it as the outcome — red
or black for a flip — or say plainly that you cannot produce a random number
yourself. Do not pretend a number you wrote down was random.

## Character numbers, because that tool has no name search

`get_rickandmorty_character` takes a number, not a name. The useful ones:

| # | Character | | # | Character |
|---|---|---|---|---|
| 1 | Rick Sanchez | | 118 | Evil Morty |
| 2 | Morty Smith | | 242 | Mr. Meeseeks |
| 3 | Summer Smith | | 244 | Mr. Poopybutthole |
| 4 | Beth Smith | | 265 | Pickle Rick |
| 5 | Jerry Smith | | 331 | Squanchy |
| 47 | Birdperson | | 372 | Unity |

If the user names someone who is not on this list, say the tool needs a number and
that you do not have theirs — do not try numbers until one matches, which burns the
turn's tool budget on guesses.

## Reading the results

- **Pokémon units are not metric the way they look.** `height` is in **decimetres**
  and `weight` in **hectograms**: Pikachu's `height: 4, weight: 60` is 0.4 m and
  6 kg. Divide height by 10 and weight by 10 before you say a number out loud.
- **Base stats** come back as six entries — `hp`, `attack`, `defense`,
  `special-attack`, `special-defense`, `speed`. Each `base_stat` runs roughly 5 to
  255; around 100 is strong and anything past 130 is exceptional. They are the
  species' baseline, not the stats of any particular trained Pokémon, so never
  present them as "your" Pokémon's numbers.
- **Card codes use `0` for ten.** A card with `code: "0S"` and `value: "10"` is the
  ten of spades, not a zero. Read `value` and `suit` to the user and leave the code
  alone. Each card also carries an image link that a client can render.
- **Dog photos** come back as a link under `message`; there is no breed name in the
  response, so it is only ever the breed you asked for.
- **Advice searches** return `total_results` as a *string*, and every matching line,
  not the best one. Pick one and offer the count: "there are four about money".
- **Quotations** come from a small fixed collection, so asking twice can return the
  same line. Attribution is whatever the collection recorded — if a quote is
  famously disputed, say so rather than endorsing the attribution.
- **Jokes and advice are delivered, not analysed.** Give the line and stop; the
  explanation is what kills it.

## What this skill does not cover

No horoscopes, lottery numbers, dice, or generated randomness beyond a card draw.
No memes, no image generation, no other TV shows or films, no anime, no music or
sports data, and no trivia quizzes or scoring. Pokémon coverage is species data
only — no trading-card prices, no team-building advice, no game walkthroughs. Facts
about a *topic you name* are `knowledge-and-research`, not `get_useless_fact`; the
words of a *particular person* are also `knowledge-and-research`. Say plainly that
this skill does not have it rather than improvising.

The joke sources are filtered to safe material and the offensive categories cannot
be selected. If the user asks for a joke at someone's expense, decline in your own
words instead of hunting for a tool that will do it.

## When a tool fails

- **No result** is the common outcome here, because most of these services answer
  "nothing matched" with an ordinary success. Report it as an answer — the cat fact
  limit was too tight, the advice collection has nothing on that word, that Pokémon
  name is not one the database knows — and offer the obvious next attempt. Never
  fill the gap yourself.
- **A deck id that stops working** means the deck expired or was never real. Shuffle
  a new one, and tell the user the previous hand is gone rather than continuing as
  if it were still on the table.
- **Invalid arguments** is nearly always yours to fix: a Pokémon name with a space
  instead of a hyphen, a character named instead of numbered, a joke category that
  does not exist. Correct it and call again; only ask the user when the missing
  detail is genuinely theirs.
- **Unavailable** is one hobby service being down, not the skill. These run on
  goodwill and none of them promises uptime. Name the piece that is missing and
  answer the rest of the question.
