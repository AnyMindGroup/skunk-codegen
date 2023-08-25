CREATE TYPE test_enum_type AS ENUM ('T1_ONE', 't2_two', 't3_Three', 'T4_FOUR', 'T5_FIVE', 'T6Six', 'MULTIPLE_WORD_ENUM');

-- some comment
CREATE TABLE test (
  -- ignore this...
  id SERIAL PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  name text,
  name_2 varchar NOT NULL,
  number int,
  template test_enum_type,
  type varchar,
  tla char(3) NOT NULL,
  tla_var varchar(3) NOT NULL,
  numeric_default numeric NOT NULL,
  numeric_24p numeric(24) NOT NULL,
  numeric_16p_2s numeric(16, 2) NOT NULL
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