# Search Experiments

Raw input and output data from a set of experiments comparing search/catalog
approaches available on GCP - part of a broader hands-on GCP learning
project. No production system, no real company or product data - everything
here is a synthetic catalog built purely to have realistic ground truth to
test search quality against.

## Why this exists

"Search over a product catalog" is a genuinely different problem depending
on which technology sits underneath it - keyword matching, managed semantic
search, and true embeddings-based vector search all behave differently on
the exact same data. The goal of this folder is to make that difference
visible with real numbers instead of assuming any one approach is "the AI
one" or "the good one." Nothing here is a benchmark of a real product; it's
a self-contained experiment run against synthetic data, meant purely to
build an accurate mental model of how these services actually behave.

## What was tested

A synthetic catalog of 703 products, modeled on a generic local supermarket
(groceries, dairy, bakery, snacks, household, personal care, apparel with
size variants, etc.) - fictional brand names throughout, no real retailer or
company represented anywhere in the data or the results.

100 simulated shopper queries were designed across 4 buckets, meant to
mirror how a real, non-technical person actually searches rather than how a
developer would query a database:

- **A - exact** (25 queries): close-to-literal product names ("basmati
  rice", "toothpaste")
- **B - descriptive** (25 queries): a need described in natural language,
  not a product name ("i need something for my dandruff", "snack for movie
  night")
- **C - weight/quantity** (25 queries): a specific weight or volume mentioned
  ("1kg rice", "500ml shampoo")
- **D - size** (25 queries): apparel/diaper sizing ("XL t-shirt", "size L
  jeans")

These same queries (all 100, or a stratified 25-query subset covering every
bucket) were run against two different search backends pointed at the
identical catalog, and every result was manually scored 1-10 for relevance
against known ground truth (since the catalog is synthetic and fully
understood, "the right answer" for each query is knowable, unlike judging
search quality on a real-world catalog).

## Files in this folder

| File | What it is |
|---|---|
| `catalog-export.json` | The INPUT - all 703 products (id, title, description, category, brand, price, size/weight attribute) as plain JSON. Generated locally, never touched a network call - the ground truth every result below is judged against. |
| `ai-commerce-search-results.txt` | OUTPUT of running all 100 queries against Google's AI Commerce Search (managed retail search product) - top-3 result titles per query. |
| `elasticsearch-results.txt` | OUTPUT of running a stratified 25-query subset (same query numbers/text as a slice of the 100 above) against a self-hosted, untuned Elasticsearch index on the same catalog - top-3 result titles per query. |

## What was actually learned

Scoring both result sets against the same rubric produced a real, checkable
finding, not an assumption:

| Bucket | AI Commerce Search avg | Elasticsearch avg |
|---|---|---|
| A - exact | 10.0 / 10 | 10.0 / 10 |
| B - descriptive | 2.83 / 10 | 7.17 / 10 |
| C - weight | 6.0 / 10 | 8.83 / 10 |
| D - size | 9.14 / 10 | 9.29 / 10 |
| **Overall (25q)** | **7.08** | **8.84** |

A managed "AI search" product is not automatically smarter than an
unconfigured open-source keyword engine. AI Commerce Search's default
configuration returned zero results for natural-language queries like "i
need something for my dandruff" or "flour to make chapati" even though the
literal words "dandruff" and "flour" appear verbatim in product
titles/descriptions - its default matching behaves closer to
phrase/structured matching than free-text token matching. Elasticsearch's
stock analyzer, with zero tuning, caught these because it tokenizes and
matches on any overlapping word by default. The "AI" branding does not by
itself mean semantic understanding is switched on.

This is not a claim that Elasticsearch beats Google's product in general -
both were tested in their default, unconfigured state on purpose, to see
what each gives you for free before any tuning. AI Commerce Search's real
value (learned ranking from real user behavior, personalization,
autocomplete) needs configuration and real traffic this experiment
deliberately didn't provide.

## Full write-up

The complete methodology, setup steps, cost formulas, and scale/quota
research live in each service's own module documentation (source of truth,
kept next to the code it documents rather than duplicated here):

- `src/main/java/com/ashfaq/gcplab/_09_ai_commerce_search/package-info.java`
- `src/main/java/com/ashfaq/gcplab/_10_elasticsearch/package-info.java`
- Published relevance audit report: https://claude.ai/code/artifact/3a3a54b4-7bfa-413d-ad1e-a7a2e6c48544

A third search approach - real embeddings-based vector search (Vertex AI
Vector Search) - is planned as a follow-up experiment on this same catalog,
see `../docs/roadmap.md`.
