# Reading Open Food Facts results

For `get_food_product_by_barcode` and `search_food_products`. The boundary in the
skill body — data, not medical advice — still holds for every value below.

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
  prefix before showing it. An empty list means nobody recorded an allergen, never
  that the food is safe — say so, and point to the physical label.
