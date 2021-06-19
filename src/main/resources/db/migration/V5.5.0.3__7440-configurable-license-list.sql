
ALTER TABLE termsofuseandaccess ADD COLUMN IF NOT EXISTS license_id BIGINT;

DO $$
BEGIN

  BEGIN
    ALTER TABLE termsofuseandaccess ADD CONSTRAINT fk_termsofuseandcesss_license_id foreign key (license_id) REFERENCES license(id);
  EXCEPTION
    WHEN duplicate_object THEN RAISE NOTICE 'Table constraint fk_termsofuseandcesss_license_id already exists';
  END;

  BEGIN
      INSERT INTO license (uri, name, active, iconurl) VALUES ('http://creativecommons.org/publicdomain/zero/1.0', 'CC0', true, '/resources/images/cc0.png');
  EXCEPTION
    WHEN duplicate_object THEN RAISE NOTICE 'CC0 has already been added to the license table';
  END;

END $$;

