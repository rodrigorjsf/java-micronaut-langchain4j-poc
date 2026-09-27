# Reading TheMealDB and TheCocktailDB results

For `search_recipe` and `search_cocktail`.

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
