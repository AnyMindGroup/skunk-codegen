CREATE TYPE test_enum_type AS ENUM ('T1', 'T2', 'T3', 'T4', 'T5', 'T6');

-- some comment
CREATE TABLE test (
  -- ignore this...
  id SERIAL PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  name text,
  name_2 varchar NOT NULL,
  number int,
  template test_enum_type
);

CREATE TABLE test_ref_only (
  test_id INT NOT NULL REFERENCES test(id) ON DELETE CASCADE
);

CREATE TABLE test_ref (
  test_id INT NOT NULL REFERENCES test(id) ON DELETE CASCADE,
  ref_name VARCHAR NOT NULL
);

CREATE TABLE test_ref_auto_pk (
  id SERIAL PRIMARY KEY,
  test_id INT NOT NULL REFERENCES test(id) ON DELETE CASCADE,
  ref_name VARCHAR NOT NULL
);

CREATE TABLE test_ref_pk (
  id VARCHAR PRIMARY KEY,
  test_id INT NOT NULL REFERENCES test(id) ON DELETE CASCADE,
  ref_name VARCHAR NOT NULL
);