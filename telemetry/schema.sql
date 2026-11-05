-- One row per product, install and day: a random id, never tied to a person, plus the versions.
CREATE TABLE IF NOT EXISTS pings (
  day     TEXT NOT NULL,
  product TEXT NOT NULL DEFAULT 'betterj',
  id      TEXT NOT NULL,
  version TEXT NOT NULL,
  ide     TEXT NOT NULL,
  PRIMARY KEY (day, product, id)
);
