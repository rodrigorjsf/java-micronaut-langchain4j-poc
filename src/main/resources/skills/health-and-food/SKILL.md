---
name: health-and-food
description: Nutrition facts, ingredients, allergens and Nutri-Score of packaged foods by barcode or product name, plus cooking recipes with quantities and steps, cocktail recipes, and breweries by city. Use for what is in a food, its calories per 100 g, how to cook a dish, or how to mix a drink.
---

# Health and food

Five tools over four catalogues. Open Food Facts describes food that comes in a
package, TheMealDB and TheCocktailDB describe food and drink you make yourself,
and Open Brewery DB is a directory of places. All four are volunteer-contributed
and none of them is complete — a missing product or dish is the normal case, not
a broken tool.

## Choosing a tool

| The user gives you | Use |
|---|---|
| a barcode read off a package, "o que tem nesse produto?" with the number | `get_food_product_by_barcode` |
| a product or brand name — "nutella", "leite condensado Moça" — and no number | `search_food_products` |
| a dish to cook, or nothing at all and a wish for a suggestion | `search_recipe` |
| a drink to mix — "como faz uma caipirinha?" | `search_cocktail` |
| a city and an interest in beer, brewpubs or a brewery tour | `search_breweries` |

## Data, not medical advice

**These tools return what a database recorded about a product. They do not
support a judgement about whether anyone should eat it.** Report the values;
never convert them into a recommendation, a diagnosis or a plan. The line is
easy to cross by accident, so three concrete cases:

- **An empty `allergens_tags` means nobody recorded an allergen, never that the
  food is safe.** Someone asking because of an allergy is asking a medical
  question about a crowd-sourced field that is blank by default. Say what the
  entry contains, say plainly that the data is contributed by volunteers and may
  be incomplete, and tell them to read the physical label — that is the only
  authoritative source, and it is in their hand.
- **`nutriscore_grade` is a score somebody computed, not a verdict you reached.**
  Say "Open Food Facts lists this as Nutri-Score C". Do not restate it as "this
  is unhealthy", "this is a good choice" or anything else that sounds like you
  weighed it.
- **No "should I eat this", no calorie targets, no diets, no weight advice, no
  interpretation of a nutrient against a health condition.** Give the numbers,
  then say that a dietitian or doctor is who answers the rest. This holds even
  when the user insists, and even when the arithmetic looks trivial.

Recipes are the same boundary seen from the other side: give the ingredients and
the method, not an opinion about whether the dish suits the person asking.

## How to combine them

- **Name first, barcode second.** `search_food_products` returns each hit's
  `code`, which is its barcode. Take the code of the best match and call
  `get_food_product_by_barcode` for the full nutrition table — the search result
  carries only the name, brand and Nutri-Score letter, and it is the barcode
  lookup that has the nutriments.
- **Pick the match, do not guess it.** Product names in the database are messy
  and often carry the brand twice. When two hits are plausibly what the user
  meant, show the names and ask which one before spending the barcode call.
- **A dish and a drink are two calls.** "What do I cook and what do I serve with
  it" is `search_recipe` then `search_cocktail`; neither knows about the other.
- **Breweries are not recipes.** `search_breweries` is a place directory. To find
  where a city is, or what the weather there will be, hand the city name to
  `geo-and-weather`; this skill has no coordinates.

## Reading the results

- **Nutrition numbers are per 100 grams**, not per pack and not per serving.
  Always say "per 100 g" when you quote one. `energy-kcal_100g` is kilocalories;
  `salt_100g` is salt, which is roughly 2.5× the sodium figure people expect.
  `quantity` is the pack size, so a per-pack figure needs the multiplication
  done and stated.
- **A found product may still carry no nutrition.** A record can come back with a
  name and a brand, `nutriscore_grade` set to `unknown`, and none of the per-100 g
  fields present at all. That is a real product whose data nobody filled in — say
  the values are not recorded rather than treating the product as missing, and
  never fill the gap from memory.
- **`allergens_tags` is language-prefixed**, e.g. `en:milk`, `en:nuts`. Strip the
  prefix before showing it, and see the boundary section above before saying
  anything about safety.
- **Recipes use numbered parallel fields.** `strIngredient1` pairs with
  `strMeasure1`, `strIngredient2` with `strMeasure2`, and so on to 20. Read them
  in pairs and stop at the first empty one — that is the end of the list, not a
  gap. `strInstructions` is one long block of prose; summarise it into steps.
  `strArea` is the cuisine and `strCategory` the course.
- **Several variants can come back at once.** A common name — margarita, mojito —
  returns every catalogued variant in `drinks`, and a broad word returns many
  `meals`. Both lists arrive capped at three, ending with an entry like
  `{"_more": 11}` when more matched. Present one recipe properly, say how many
  others exist, and do not dump the list.
- **Brewery results are not marked as partial.** You asked for a number and you
  got that number; the directory may hold many more. Say "three of the breweries
  listed in X", never "the breweries in X". `brewery_type` values are terms of art:
  `micro`, `brewpub` (brews and serves food), `large`, `contract`, `planning`
  (not open yet), `closed`.

## What this skill does not cover

No medical or dietary advice of any kind — no diagnosis, no "is this good for
me", no calorie goals, no meal plans, no supplement or medication questions, and
no interpretation of a nutrient against a health condition. It also has no
restaurant listings, no food-delivery or price data, no per-serving nutrition for
a home-cooked recipe, no wine or beer reviews, and no nutrition for unpackaged
fresh produce. When the question is any of these, say plainly that this skill
does not have it — and for the medical ones, that a qualified professional is
who should answer — rather than estimating.

## When a tool fails

- **Unavailable on `search_food_products`** means the name-search service is
  down, and the two food tools run on separate hosts — so ask the user for the
  barcode and use `get_food_product_by_barcode`, which is unaffected. Do not call
  the search again in the same turn.
- **Rate limited** on either food tool means the shared per-minute budget for
  this deployment is spent. Answer from what you already have and say the food
  database is busy; retrying immediately fails identically.
- **No result** is an answer. Open Food Facts is volunteer-built and misses a
  great many products, and the recipe catalogue is small and Anglo-centric. Say
  the entry does not exist in the database and offer to try another spelling or
  another product. Never fill the gap with a remembered nutrition table — an
  invented number about food is the worst possible output of this skill.
- **"Returned more recipe data than this tool can read"** is rare and means the
  catalogue itself has grown past the budget for that word. Ask the user for a
  more specific dish or drink name and call once more. Do not tell them their
  word was "an ingredient" — it usually is not.
- **Invalid arguments** tells you what was wrong — usually a barcode with the
  wrong number of digits, or a city name with a state appended. Fix it yourself
  and only ask the user when the missing detail is genuinely theirs.
