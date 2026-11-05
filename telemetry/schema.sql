-- One row per install and day: a random id, never tied to a person, plus the versions.
CREATE TABLE IF NOT EXISTS pings (
  day     TEXT NOT NULL,
  id      TEXT NOT NULL,
  version TEXT NOT NULL,
  ide     TEXT NOT NULL,
  PRIMARY KEY (day, id)
);
