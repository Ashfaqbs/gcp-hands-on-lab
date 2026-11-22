# Module 12 - BigQuery

This module is a plain-English FAQ. Each question is one thing that confused me, with the answer and an Amazon analogy.

## Part 1 - The big picture

### Q: What is BigQuery in one line?

A serverless analytical database. You put data in tables, you ask questions with SQL, and Google runs the query on thousands of machines for you. No servers to size, patch or scale.

### Q: Is it a pipeline like Flink, or a storage layer?

A storage layer with a query engine. It is not a pipeline.

- Flink works on data in motion. Events arrive, get mapped and filtered, and are written somewhere.
- BigQuery works on data at rest. Data sits in tables, and nothing happens until a query runs.

You can transform data inside BigQuery with SQL (views, scheduled queries, `INSERT ... SELECT`), but you or a schedule trigger it. Nothing flows through continuously.

The usual setup uses both:

    App / Kafka / Flink or Dataflow  ->  BigQuery  ->  dashboards, analysts, ML

Flink (or Dataflow) is the pipeline. BigQuery plays the role that Postgres would play at the end of that pipeline, but built for big analysis.

### Q: Is it just a database?

Yes, but a database built for analysis (OLAP), not for running an app (OLTP).

Amazon analogy:
- The checkout counter saves one order in milliseconds. That is OLTP: Cloud SQL, Spanner, Firestore.
- Head office asks "total toy sales by city and month for 3 years". That reads billions of rows. That is OLAP: BigQuery.

If you ran the head-office question on the checkout database, it would be slow and it would slow down the live shop. So the two jobs get two different databases.

### Q: Why does it exist when GCP already has databases?

Those databases are built for fast single-row work. BigQuery is built for scanning huge amounts of data.

- Columnar storage: it reads only the columns the query names, not whole rows.
- Massively parallel: one query is spread across thousands of machines.
- Storage and compute are separate: you pay for each on its own, and neither blocks the other.

It is bad at:
- Looking up one row by id (use Cloud SQL or Firestore).
- Many small updates.
- Low-latency app traffic. Even a simple query takes a fraction of a second or more.

### Q: How is it like ClickHouse?

Same family: analytical, columnar, distributed. The main difference is who runs it. In BigQuery, Google handles scaling, replication and servers. You do not manage a cluster.

One common mix-up: columnar makes reads and aggregations fast, not writes. `SUM(price)` touches only the `price` column. Writes work best in batches or streams, not as many tiny single-row inserts.

## Part 2 - Schema

### Q: Is the schema enforced, schemaless, or dynamic?

Enforced by default. Every table has typed columns, and each column has a mode:

- REQUIRED: a value must be present.
- NULLABLE: a value can be missing.
- REPEATED: the column holds a list (an array).

Flexibility comes from these:
- STRUCT: an object inside a column. One row can hold an order with its line items.
- ARRAY: a list inside a column.
- JSON column type: the schemaless escape hatch. Keep one JSON column for the messy parts and query inside it.
- Autodetect: when loading CSV or JSON files, BigQuery can guess the schema.
- Limited schema changes: you can add columns, and relax REQUIRED to NULLABLE. Renaming a column or changing its type is limited.

There is no single "dynamic schema" switch like in MongoDB. You pick a strict table and put a JSON column in it for the unknown parts.

This is the same pattern as the Retail API Product: fixed root fields plus a free `attributes` map.

## Part 3 - Structure

### Q: How is data organised?

    project > dataset > table

- Project: owns billing and permissions.
- Dataset: like a folder. It sets the region and the access rules.
- Table: holds the rows.

Amazon analogy: the project is the company, the dataset is a department (Toys, Books), and the table is one spreadsheet inside that department.

Warning: the dataset's region is fixed when you create it, and you cannot join tables across regions. Pick one region and stay with it.

## Part 4 - Cost

### Q: What do we pay for?

- Storage: the data you keep. The first 10 GB per month is free.
- Queries: by default, billed per byte scanned. The first 1 TB per month is free.
- Loading data from files is free. Streaming inserts are charged.

Warning: `SELECT *` on a big table scans every column and costs the most. Name only the columns you need.

Cost safety we will use in the lab:
- Dry run: ask BigQuery how many bytes a query would scan, without running it.
- `maximumBytesBilled`: a hard cap. A query that would scan more than the cap fails instead of billing you.

## Our toy store example

- Orders and the catalog stay in an OLTP database such as Cloud SQL.
- Search events, clicks and orders are copied into BigQuery.
- Then an analyst can ask: "which toys get clicked often but never bought?"
