-- One row per product, install and day: a random id, never tied to a person, plus the versions.
CREATE TABLE IF NOT EXISTS pings (
  day     TEXT NOT NULL,
  product TEXT NOT NULL DEFAULT 'betterj',
  id      TEXT NOT NULL,
  version TEXT NOT NULL,
  ide     TEXT NOT NULL,
  PRIMARY KEY (day, product, id)
);

-- The nick an install chose to show its author (optional, empty by default); only GET /users with the key reads it.
CREATE TABLE IF NOT EXISTS nicks (
  product  TEXT NOT NULL,
  id       TEXT NOT NULL,
  nick     TEXT NOT NULL,
  version  TEXT NOT NULL,
  last_day TEXT NOT NULL,
  PRIMARY KEY (product, id)
);
