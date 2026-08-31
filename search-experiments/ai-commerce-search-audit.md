# 100 shoppers, one search box - AI Commerce Search relevance audit

A 703-product synthetic mart catalog (groceries, household, personal care,
apparel) was imported into AI Commerce Search / Retail Search, then queried
the way real, non-technical shoppers actually type - exact names, vague
descriptions, weights, and clothing sizes. Every result was checked by hand
against the real catalog. No real retailer's data or branding was used;
search was run against the Retail API's default `default_search` serving
config with no custom synonyms, boosts, or query-understanding configuration
applied.

**703** products imported · **100** queries run · **4** query styles ·
**7.65 / 10** overall

## Score by query style

| Bucket | Score | Notes |
|---|---|---|
| A - exact name | 10.0 / 10 | "basmati rice", "shampoo" - near-perfect keyword matching |
| B - described need | 3.7 / 10 | "something for my dandruff" - 16 of 25 returned nothing |
| C - weight / volume | 8.0 / 10 | Strong, with "litre" vs "L" normalized inconsistently |
| D - clothing size | 9.0 / 10 | "small kurti" correctly found S - but not consistently |

## The headline finding

Default Retail Search is a strong **keyword** engine, not yet a **semantic**
one out of the box. Queries sharing a literal word with the catalog succeed
almost every time - but **16 of 25** natural-language queries returned zero
results, including cases where the exact word appears in the product
description ("sanitize" -> Hand Sanitizer, "tissue" -> Facial Tissue Box,
"flour" -> Wheat Flour Atta all failed). Closing this gap is what Vertex AI
Search's semantic/embedding layer and query-understanding features are
specifically for - this default configuration hasn't turned them on.

## All 100 queries

Top search result per query, scored 1-10 against what a shopper actually
meant.

### A - Exact product name

| # | Query | Top result | Score | Note |
|---|---|---|---|---|
| Q1 | "basmati rice" | PureHarvest Basmati Rice (25kg) | 10 | Exact keyword match |
| Q2 | "toothpaste" | NatureBliss Toothpaste (100g) | 10 | |
| Q3 | "cola" | CrunchTime Cola Soft Drink (1L) | 10 | |
| Q4 | "shampoo" | GlowCare Anti-Dandruff Shampoo (200ml) | 10 | |
| Q5 | "milk" | MeadowFresh Toned Milk (1L) | 10 | |
| Q6 | "bread" | GoldenGrain White Bread (700g) | 10 | |
| Q7 | "potato chips" | TastyBite Potato Chips (100g) | 10 | |
| Q8 | "butter" | PureHarvest Butter (100g) | 10 | |
| Q9 | "eggs" | CreamyDale Eggs (12 pack) | 10 | |
| Q10 | "detergent powder" | PureSoft Detergent Powder (1kg) | 10 | |
| Q11 | "sugar" | DailyFresh Refined Sugar (5kg) | 10 | |
| Q12 | "salt" | DailyFresh Iodised Salt (1kg) | 10 | |
| Q13 | "cooking oil" | PureHarvest Sunflower Cooking Oil (1L) | 10 | |
| Q14 | "tea" | DailyFresh Black Tea Leaves (250g) | 10 | |
| Q15 | "coffee" | DailyFresh Instant Coffee (100g) | 10 | |
| Q16 | "baby diapers" | TinyCare Baby Diapers (M) | 10 | |
| Q17 | "hand sanitizer" | NatureBliss Hand Sanitizer (500ml) | 10 | |
| Q18 | "ice cream" | CreamyDale Vanilla Ice Cream (1L) | 10 | |
| Q19 | "notebook" | HomeShine Ruled Notebook | 10 | Only 1 exists - correct |
| Q20 | "umbrella" | HomeShine Folding Umbrella | 10 | |
| Q21 | "water bottle" | HomeShine Steel Water Bottle | 9 | Mineral water bottles also ranked, reasonably |
| Q22 | "face wash" | NatureBliss Face Wash (50g) | 10 | |
| Q23 | "hair oil" | PureSoft Hair Oil (200ml) | 10 | |
| Q24 | "dishwash liquid" | PureSoft Dishwash Liquid Gel (1L) | 10 | |
| Q25 | "chocolate" | TastyBite Milk Chocolate Bar (50g) | 10 | |

### B - Described need (natural language)

| # | Query | Top result | Score | Note |
|---|---|---|---|---|
| Q26 | "something to wash my dishes with" | SparkleClean Dishwash Liquid Gel (250ml) | 10 | Genuine semantic hit |
| Q27 | "i need something for my dandruff" | - | 0 | NO RESULTS - Anti-Dandruff Shampoo exists |
| Q28 | "drink to keep me hydrated in summer" | - | 0 | NO RESULTS - water/juice exist |
| Q29 | "snack for movie night" | - | 0 | NO RESULTS - popcorn/chips exist |
| Q30 | "something to clean my bathroom" | SparkleClean Toilet Cleaner (1L) | 9 | |
| Q31 | "protein supplement for gym" | NatureBliss Whey Protein Powder (2kg) | 10 | |
| Q32 | "soft drink for party" | TastyBite Cola Soft Drink (500ml) | 9 | |
| Q33 | "baby skin care lotion" | SoftHug Baby Lotion (100ml) | 9 | |
| Q34 | "rice for daily cooking" | DailyFresh Basmati Rice (5kg) | 9 | |
| Q35 | "spread for my toast" | - | 0 | NO RESULTS - Butter exists |
| Q36 | "spicy snack mix" | - | 0 | NO RESULTS - desc says "Spicy fried namkeen mixture" |
| Q37 | "something for my dry hair" | - | 0 | NO RESULTS - Hair Oil exists |
| Q38 | "cooking oil for frying" | GoldenGrain Sunflower Cooking Oil (1L) | 9 | |
| Q39 | "medicine for body pain" | - | 0 | NO RESULTS - Pain Relief Spray exists |
| Q40 | "daily vitamins" | NatureBliss Multivitamin Tablets (30 tab) | 9 | |
| Q41 | "something to sanitize my hands" | - | 0 | NO RESULTS - word "sanitize" is literally in title |
| Q42 | "frozen snack for kids" | - | 0 | NO RESULTS - Frozen Nuggets/Paratha exist |
| Q43 | "sweet treat for kids" | - | 0 | NO RESULTS - Chocolate exists |
| Q44 | "flour to make chapati" | - | 0 | NO RESULTS - word "flour" literally in title |
| Q45 | "lentils for dal" | DailyFresh Toor Dal (2kg) | 9 | |
| Q46 | "tissue for my nose" | - | 0 | NO RESULTS - word "tissue" literally in title |
| Q47 | "juice with no added sugar" | PureHarvest Mixed Fruit Juice (200ml) | 10 | Matched description text directly |
| Q48 | "something to write with" | - | 0 | NO RESULTS - Ball Pen Set exists |
| Q49 | "light source for my room" | - | 0 | NO RESULTS - LED Light Bulb exists |
| Q50 | "bag to carry my lunch" | - | 0 | NO RESULTS - word "lunch" literally in title |

### C - Weight / volume mentioned

| # | Query | Top result | Score | Note |
|---|---|---|---|---|
| Q51 | "1kg rice" | PureHarvest Basmati Rice (1kg) | 10 | |
| Q52 | "5kg atta" | PureHarvest Wheat Flour Atta (5kg) | 10 | |
| Q53 | "500ml shampoo" | - | 5 | NO RESULTS - but 500ml genuinely doesn't exist (only 200/340/650ml) |
| Q54 | "1 litre milk" | CreamyDale Toned Milk (1L) | 10 | "litre" correctly normalized to 1L |
| Q55 | "2kg detergent" | SparkleClean Detergent Powder (2kg) | 10 | |
| Q56 | "200g butter" | - | 5 | NO RESULTS - 200g genuinely doesn't exist (only 100/500g) |
| Q57 | "100g coffee" | DailyFresh Instant Coffee (100g) | 10 | |
| Q58 | "1 litre cooking oil" | - | 2 | NO RESULTS - but 1L DOES exist; "litre" not normalized here |
| Q59 | "500g paneer" | PureHarvest Paneer (500g) | 10 | |
| Q60 | "10kg rice bag" | - | 2 | NO RESULTS - but 10kg rice DOES exist; word "bag" broke match |
| Q61 | "2 litre cola" | - | 2 | NO RESULTS - but 2L cola DOES exist; "litre" not normalized |
| Q62 | "1kg sugar" | GoldenGrain Refined Sugar (1kg) | 10 | |
| Q63 | "250g chips" | MunchBox Potato Chips (250g) | 10 | |
| Q64 | "1kg protein powder" | NatureBliss Whey Protein Powder (1kg) | 10 | |
| Q65 | "5 litre water" | DailyFresh Packaged Mineral Water (5L) | 10 | |
| Q66 | "100ml hair oil" | NatureBliss Hair Oil (100ml) | 10 | |
| Q67 | "500ml body wash" | NatureBliss Body Wash (500ml) | 10 | |
| Q68 | "1kg toor dal" | DailyFresh Toor Dal (1kg) | 10 | |
| Q69 | "200ml sanitizer" | NatureBliss Hand Sanitizer (500ml) | 4 | 200ml doesn't exist; returned wrong size as if correct |
| Q70 | "700g bread" | GoldenGrain White Bread (700g) | 10 | |
| Q71 | "1kg namkeen" | GoldenGrain Namkeen Mixture (1kg) | 10 | |
| Q72 | "500g cookies" | GoldenGrain Butter Cookies (500g) | 10 | |
| Q73 | "2kg frozen peas" | PureHarvest Frozen Green Peas (500g) | 3 | 2kg doesn't exist; wrong size shown with no flag |
| Q74 | "100g turmeric powder" | DailyFresh Turmeric Powder (100g) | 10 | |
| Q75 | "250ml floor cleaner" | PureSoft Floor Cleaner (200ml) | 6 | 250ml doesn't exist; reasonable nearby sizes shown |

### D - Clothing / diaper size

| # | Query | Top result | Score | Note |
|---|---|---|---|---|
| Q76 | "XL t-shirt" | ComfortWear Men's Cotton T-Shirt (XL) | 10 | |
| Q77 | "size L jeans" | ComfortWear Men's Denim Jeans (L) | 10 | |
| Q78 | "men's shirt size M" | DailyFit Men's Formal Shirt (M) | 10 | |
| Q79 | "XXL diapers" | TinyCare Baby Diapers (XXL) | 10 | |
| Q80 | "small size kurti" | StyleHub Women's Kurti (S) | 10 | "small" correctly mapped to S |
| Q81 | "medium track pants" | DailyFit Men's Track Pants (M) | 9 | M ranked first correctly, S/L noise in top 3 |
| Q82 | "large leggings" | DailyFit Women's Leggings (XXL) | 5 | "large" mapped to XXL instead of L |
| Q83 | "XL kids t-shirt" | DailyFit Kids T-Shirt (XL) | 10 | |
| Q84 | "men's t-shirt in L" | ComfortWear Men's Cotton T-Shirt (L) | 10 | |
| Q85 | "women's t-shirt XL" | ComfortWear Women's Cotton T-Shirt (XL) | 10 | |
| Q86 | "size S formal shirt" | ComfortWear Men's Formal Shirt (S) | 10 | |
| Q87 | "XL nightwear" | ComfortWear Women's Nightwear Set (XL) | 10 | |
| Q88 | "baby diapers size M" | LittleOnes Baby Diapers (M) | 8 | Correct top result, but XL noise in top 3 |
| Q89 | "innerwear size L" | - | 0 | NO RESULTS - Men's Innerwear Vest (L) exists |
| Q90 | "XXL t-shirt for men" | ComfortWear Men's Cotton T-Shirt (XXL) | 10 | |
| Q91 | "small diapers" | TinyCare Baby Diapers (L) | 2 | "small" mapped to L, M, XL - S never surfaced |
| Q92 | "size XL kurti" | StyleHub Women's Kurti (XL) | 10 | |
| Q93 | "jeans size L" | ComfortWear Men's Denim Jeans (L) | 10 | |
| Q94 | "women's leggings size M" | ComfortWear Women's Leggings (M) | 10 | |
| Q95 | "XL track pants" | DailyFit Men's Track Pants (XL) | 10 | |
| Q96 | "formal shirt XXL" | ComfortWear Men's Formal Shirt (XXL) | 10 | |
| Q97 | "kurti size XL" | ComfortWear Women's Kurti (XL) | 10 | |
| Q98 | "men's vest size M" | StyleHub Men's Innerwear Vest (M) | 10 | |
| Q99 | "diapers size L" | LittleOnes Baby Diapers (L) | 10 | |
| Q100 | "XL women's t-shirt" | ComfortWear Women's Cotton T-Shirt (XL) | 10 | |
